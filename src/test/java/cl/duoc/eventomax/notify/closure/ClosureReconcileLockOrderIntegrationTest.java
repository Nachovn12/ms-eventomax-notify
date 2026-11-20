package cl.duoc.eventomax.notify.closure;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import cl.duoc.eventomax.notify.sender.SmtpEmailSender;
import cl.duoc.eventomax.notify.service.QuoteNotificationService;
import cl.duoc.eventomax.notify.storage.LocalFileSystemDocumentStorageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real store methods, two JDBC transactions and explicit observation of PostgreSQL blocking. */
@Testcontainers
class ClosureReconcileLockOrderIntegrationTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    static DriverManagerDataSource database;
    static JdbcTemplate jdbc;
    @TempDir Path temp;
    final ClosureSettings settings = new ClosureSettings(true, false, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000);
    final ObjectMapper json = new ObjectMapper();
    ObservedDataSource observed;
    ClosureStore store;
    ClosureStore.Delivery claim;
    ClosureStore.Document document;
    FixtureSmtpServer smtp;
    ClosureWorker worker;
    AtomicInteger transportCalls;

    @BeforeAll static void migration() {
        database = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(database);
        jdbc.setQueryTimeout(2);
        Flyway.configure().dataSource(database).schemas("notify").defaultSchema("notify").target("1").load().migrate();
        jdbc.update("INSERT INTO notify.processed_events(event_id,event_type,processed_at) VALUES ('lock-review-v1','FIXTURE',CURRENT_TIMESTAMP)");
        Flyway.configure().dataSource(database).schemas("notify").defaultSchema("notify").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify.processed_events WHERE event_id='lock-review-v1'", Integer.class)).isEqualTo(1);
    }

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE notify.closure_smtp_attempts,notify.closure_deliveries,notify.closure_documents,notify.closure_inbox,notify.notification_delivery_audit RESTART IDENTITY CASCADE");
        observed = new ObservedDataSource(database);
        store = new ClosureStore(new JdbcTemplate(observed), new DataSourceTransactionManager(observed), json, settings);
        var quote = new MessageEnvelope<>("GenerateClosingQuote", "lock-document", "2026-11-20T20:00:00Z", "trace-document", "correlation",
                new QuoteCommandPayload(41L,"fixture","Isolated closure","CERRADO","2026-12-01T18:00:00","Fixture venue","2026-11-20T20:00:00Z"));
        store.acceptDocument(quote);
        document = store.claimDocument().orElseThrow();
        byte[] bytes = ClosurePdfTest.pdf(41, document.id());
        var storage = new LocalFileSystemDocumentStorageService(temp.toString());
        String key = "lock-" + document.id() + ".pdf";
        assertThat(store.prepared(document,key,ClosurePdf.sha256(bytes),bytes.length)).isTrue();
        storage.storeDocumentIfAbsent("closures",key,bytes,"application/pdf");
        assertThat(store.documentReady(document)).isTrue();
        var email = new MessageEnvelope<>("SendProductionStatusEmail","lock-email","2026-11-20T20:00:00Z","trace-email","correlation",
                new EmailProductionStatusPayload(41L,"fixture","organizer@example.invalid","Isolated closure","CERRADO","2026-12-01T18:00:00","Fixture venue"));
        store.acceptEmail(email); store.reconcile();
        claim = store.claimDelivery().orElseThrow();
        smtp = new FixtureSmtpServer();
        var mail = new org.springframework.mail.javamail.JavaMailSenderImpl();
        mail.setHost("127.0.0.1"); mail.setPort(smtp.port());
        for(String keyName:List.of("connectiontimeout","timeout","writetimeout")) mail.getJavaMailProperties().setProperty("mail.smtp."+keyName,"1000");
        var sender = new SmtpEmailSender(mail,mock(NotificationDeliveryAuditRepository.class),"notify@example.invalid","Fixture");
        transportCalls = new AtomicInteger();
        ClosureEmailTransport transport = (e,deliveryId,documentId,pdf,sha) -> {
            transportCalls.incrementAndGet(); sender.sendClosure(e,deliveryId,documentId,pdf,sha);
        };
        var beans = new StaticListableBeanFactory(); beans.addBean("fixtureSmtp",transport);
        worker = new ClosureWorker(store,mock(QuoteNotificationService.class),storage,beans.getBeanProvider(ClosureEmailTransport.class),settings);
        // SMTP has returned acceptance. Only its commit races with lease reconciliation below.
        transport.sendClosure(email,claim.id(),document.id(),bytes,ClosurePdf.sha256(bytes));
        jdbc.update("UPDATE notify.closure_deliveries SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?",claim.id());
    }

    @AfterEach void cleanup() throws Exception { observed.release.countDown(); if(smtp!=null) smtp.close(); }

    @RepeatedTest(10) @Timeout(20)
    void finishOwnsDeliveryBeforeReconcileStarts() throws Exception {
        race(true,false);
        assertAcceptedOnce();
    }

    @RepeatedTest(10) @Timeout(20)
    void recoveryOwnsDeliveryBeforeLateAcceptanceFinishes() throws Exception {
        race(false,false);
        assertAcceptedOnce();
    }

    @Test @Timeout(20)
    void originalSqlIsAPositiveDeadlockControlUnderTheSameBarriers() throws Exception {
        race(true,true);
        assertThat(observed.sqlStates).contains("40P01");
        assertThat(transportCalls.get()).isEqualTo(1);
        assertThat(smtp.submissions.get()).isEqualTo(1);
    }

    @RepeatedTest(10) @Timeout(20)
    void expiredRecoveryAndStaleFinishCannotChangeAnotherLease() {
        // A known negative outcome permits another claim; the old token remains fenced out.
        store.finish(claim,ClosureState.RETRY_WAIT,"FIXTURE_NEGATIVE");
        jdbc.update("UPDATE notify.closure_deliveries SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",claim.id());
        var current = store.claimDelivery().orElseThrow();
        assertThat(current.token()).isNotEqualTo(claim.token());
        // A stale SENDING row is deliberately retained to test exact lease identity, not just delivery_id.
        jdbc.update("UPDATE notify.closure_smtp_attempts SET state='SENDING' WHERE lease_token=?",claim.token());
        store.reconcile();
        assertThat(attemptState(current.token())).isEqualTo("SENDING");
        jdbc.update("UPDATE notify.closure_deliveries SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?",claim.id());
        store.reconcile();
        assertThat(deliveryState()).isEqualTo("UNKNOWN");
        assertThat(attemptState(current.token())).isEqualTo("UNKNOWN");
        assertThat(attemptState(claim.token())).isEqualTo("SENDING");
        store.finish(claim,ClosureState.SMTP_ACCEPTED,null);
        assertThat(deliveryState()).isEqualTo("UNKNOWN");
        assertThat(attemptState(current.token())).isEqualTo("UNKNOWN");
        worker.tick(); worker.sendOne();
        assertThat(deliveryState()).isEqualTo("UNKNOWN");
        assertThat(attemptState(current.token())).isEqualTo("UNKNOWN");
        assertThat(transportCalls.get()).isEqualTo(1);
        assertThat(smtp.submissions.get()).isEqualTo(1);
        store.finish(current,ClosureState.SMTP_ACCEPTED,null);
        store.reconcile(); worker.tick();
        assertThat(deliveryState()).isEqualTo("SMTP_ACCEPTED");
        assertThat(attemptState(current.token())).isEqualTo("SMTP_ACCEPTED");
        assertThat(attemptState(claim.token())).isEqualTo("SENDING");
        assertThat(transportCalls.get()).isEqualTo(1);
        assertThat(smtp.submissions.get()).isEqualTo(1);
    }

    private void race(boolean finishFirst,boolean legacyControl) throws Exception {
        observed.finishFirst = finishFirst;
        var executor = Executors.newFixedThreadPool(2);
        Future<?> finish = null, recovery = null;
        try {
            if(finishFirst) {
                finish = executor.submit(() -> observed.as(Role.FINISH,() -> store.finish(claim,ClosureState.SMTP_ACCEPTED,null)));
                assertThat(observed.ownerLocked.await(3,TimeUnit.SECONDS)).as("finish holds the delivery row").isTrue();
                recovery = executor.submit(() -> observed.as(Role.RECONCILE,() -> reconcile(legacyControl)));
                assertThat(observed.recoveryEntered.await(3,TimeUnit.SECONDS)).isTrue();
                awaitBlocked(observed.recoveryPid.get(),observed.finishPid.get());
            } else {
                recovery = executor.submit(() -> observed.as(Role.RECONCILE,() -> store.reconcile()));
                assertThat(observed.ownerLocked.await(3,TimeUnit.SECONDS)).as("reconciliation holds the recovered row").isTrue();
                finish = executor.submit(() -> observed.as(Role.FINISH,() -> store.finish(claim,ClosureState.SMTP_ACCEPTED,null)));
                assertThat(observed.finishEntered.await(3,TimeUnit.SECONDS)).isTrue();
                awaitBlocked(observed.finishPid.get(),observed.recoveryPid.get());
            }
            observed.release.countDown();
            var failures = new ArrayList<Throwable>();
            for(var future:List.of(finish,recovery)) {
                try { future.get(10,TimeUnit.SECONDS); }
                catch(ExecutionException ex) { failures.add(ex.getCause()); }
            }
            if(!legacyControl) {
                assertThat(observed.sqlStates).as("production methods must not deadlock").doesNotContain("40P01");
                assertThat(failures).isEmpty();
            }
        } finally { observed.release.countDown(); executor.shutdownNow(); }
    }

    private void reconcile(boolean legacyControl) {
        if(!legacyControl) {store.reconcile();return;}
        // Exact obsolete order, retained only as a positive control proving the barriers find the original bug.
        var tx = new TransactionTemplate(new DataSourceTransactionManager(observed));tx.setTimeout(5);
        var statements = new JdbcTemplate(observed);statements.setQueryTimeout(5);
        tx.executeWithoutResult(status -> {
            statements.update("""
                    UPDATE notify.closure_smtp_attempts a SET state='UNKNOWN',error_code='LEASE_EXPIRED',finished_at=CURRENT_TIMESTAMP
                    FROM notify.closure_deliveries j WHERE a.delivery_id=j.id AND a.lease_token=j.lease_token
                    AND a.state='SENDING' AND j.state='SENDING' AND j.lease_until<=CURRENT_TIMESTAMP
                    """);
            statements.update("UPDATE notify.closure_deliveries SET state='UNKNOWN',error_code='LEASE_EXPIRED' WHERE state='SENDING' AND lease_until<=CURRENT_TIMESTAMP");
        });
    }

    private void awaitBlocked(int waiter,int blocker) {
        assertThat(waiter).isPositive();assertThat(blocker).isPositive();assertThat(waiter).isNotEqualTo(blocker);
        long until = System.nanoTime()+Duration.ofSeconds(2).toNanos();
        do {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT CAST(? AS integer)=ANY(pg_blocking_pids(?))",Boolean.class,blocker,waiter))) return;
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        } while(System.nanoTime()<until);
        fail("PostgreSQL did not confirm the required transaction blocking; no concurrency claim can be made");
    }

    private void assertAcceptedOnce() {
        assertThat(deliveryState()).isEqualTo("SMTP_ACCEPTED");
        assertThat(attemptState(claim.token())).isEqualTo("SMTP_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify.closure_smtp_attempts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify.notification_delivery_audit",Integer.class)).isEqualTo(1);
        store.reconcile(); worker.tick(); worker.sendOne();
        assertThat(deliveryState()).isEqualTo("SMTP_ACCEPTED");
        assertThat(transportCalls.get()).isEqualTo(1);assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    private String deliveryState() {return jdbc.queryForObject("SELECT state FROM notify.closure_deliveries WHERE id=?",String.class,claim.id());}
    private String attemptState(UUID token) {return jdbc.queryForObject("SELECT state FROM notify.closure_smtp_attempts WHERE delivery_id=? AND lease_token=?",String.class,claim.id(),token);}

    enum Role { FINISH,RECONCILE }
    /** JDBC instrumentation only; actual SQL and transactions still execute on PostgreSQL. */
    static final class ObservedDataSource extends AbstractDataSource {
        final DataSource delegate;
        final ThreadLocal<Role> role = new ThreadLocal<>();
        final CountDownLatch ownerLocked = new CountDownLatch(1),release = new CountDownLatch(1),
                recoveryEntered = new CountDownLatch(1),finishEntered = new CountDownLatch(1);
        final AtomicInteger finishPid = new AtomicInteger(),recoveryPid = new AtomicInteger();
        final AtomicBoolean finishHook = new AtomicBoolean(),recoveryHook = new AtomicBoolean();
        final Queue<String> sqlStates = new ConcurrentLinkedQueue<>();
        volatile boolean finishFirst;
        ObservedDataSource(DataSource delegate) {this.delegate=delegate;}
        void as(Role value,Runnable work) {role.set(value);try{work.run();}finally{role.remove();}}
        @Override public Connection getConnection() throws SQLException {return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password) throws SQLException {return wrap(delegate.getConnection(user,password));}
        private Connection wrap(Connection raw) throws SQLException {
            int pid;
            try(var s=raw.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")) {r.next();pid=r.getInt(1);}
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                Object value=invoke(raw,method,args);
                if(value instanceof Statement statement && (method.getName().equals("prepareStatement")||method.getName().equals("createStatement"))) {
                    String prepared=args!=null && args.length>0 && args[0] instanceof String ? (String)args[0] : null;
                    Class<?> type=value instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                        String sql=prepared!=null ? prepared : a!=null && a.length>0 && a[0] instanceof String ? (String)a[0] : "";
                        boolean execute=m.getName().equals("executeUpdate")||m.getName().equals("executeLargeUpdate");
                        Role current=role.get();
                        boolean f=execute && current==Role.FINISH && sql.startsWith("UPDATE notify.closure_deliveries SET state=?") && finishHook.compareAndSet(false,true);
                        boolean r=execute && current==Role.RECONCILE && sql.contains("closure_smtp_attempts") && recoveryHook.compareAndSet(false,true);
                        if(f){finishPid.set(pid);finishEntered.countDown();}
                        if(r){recoveryPid.set(pid);recoveryEntered.countDown();}
                        Object result=invoke(statement,m,a);
                        if((f&&finishFirst)||(r&&!finishFirst)) {
                            ownerLocked.countDown();
                            if(!release.await(4,TimeUnit.SECONDS)) throw new AssertionError("transaction barrier not released");
                        }
                        return result;
                    });
                }
                return value;
            });
        }
        private Object invoke(Object target,Method method,Object[] args) throws Throwable {
            try{return method.invoke(target,args);}
            catch(InvocationTargetException ex){
                if(ex.getCause() instanceof SQLException sql && sql.getSQLState()!=null)sqlStates.add(sql.getSQLState());
                throw ex.getCause();
            }
        }
    }
}
