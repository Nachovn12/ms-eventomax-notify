package cl.duoc.eventomax.notify.closure;

import cl.duoc.eventomax.notify.client.catalog.CatalogReservationClient;
import cl.duoc.eventomax.notify.document.OpenPdfClosureDocumentGenerator;
import cl.duoc.eventomax.notify.document.ProductionClosureDocumentBuilder;
import cl.duoc.eventomax.notify.idempotency.InMemoryProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailCommandListener;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandListener;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import cl.duoc.eventomax.notify.sender.SmtpEmailSender;
import cl.duoc.eventomax.notify.service.EmailNotificationService;
import cl.duoc.eventomax.notify.service.QuoteNotificationService;
import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import cl.duoc.eventomax.notify.storage.LocalFileSystemDocumentStorageService;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real, disposable PostgreSQL + RabbitMQ, SMTP restricted to an in-process loopback fixture. */
@Testcontainers
class DurableClosureIntegrationTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.3.6-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "fixture").withEnv("RABBITMQ_DEFAULT_PASS", "fixture")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();
    final ClosureSettings settings = new ClosureSettings(true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000);
    ClosureStore store;
    DocumentStorageService storage;
    QuoteNotificationService generator;
    FixtureSmtpServer smtp;
    SmtpEmailSender sender;
    ClosureWorker worker;

    @BeforeAll static void migrateOnlyDisposableDatabase() {
        assertThat(PG.isRunning()).isTrue();
        assertThat(PG.getMappedPort(5432)).isNotEqualTo(5432);
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas("notify").defaultSchema("notify").target("1").load().migrate();
        jdbc.update("INSERT INTO notify.processed_events(event_id,event_type,processed_at) VALUES ('pre-v2-marker','HISTORICAL',CURRENT_TIMESTAMP)");
        Flyway.configure().dataSource(dataSource).schemas("notify").defaultSchema("notify").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify.processed_events WHERE event_id='pre-v2-marker'", Integer.class)).isEqualTo(1);
    }
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE notify.closure_smtp_attempts, notify.closure_deliveries, notify.closure_documents, notify.closure_inbox, notify.notification_delivery_audit RESTART IDENTITY CASCADE");
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);
        storage = new LocalFileSystemDocumentStorageService(temp.resolve("storage").toString());
        var builder = new ProductionClosureDocumentBuilder(id -> CatalogReservationClient.CatalogReservationResult.available(
                List.of(new CatalogReservationClient.CatalogReservationItem(3L, "Subwoofer Activo JBL VTX G28", 1, "RESERVED"))));
        generator = new QuoteNotificationService(builder, new OpenPdfClosureDocumentGenerator(), null, storage, temp.resolve("unused").toString());
        smtp = new FixtureSmtpServer();
        var mail = new JavaMailSenderImpl();
        mail.setHost("127.0.0.1"); mail.setPort(smtp.port());
        mail.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "1000");
        mail.getJavaMailProperties().setProperty("mail.smtp.timeout", "700");
        mail.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "1000");
        sender = new SmtpEmailSender(mail, mock(NotificationDeliveryAuditRepository.class), "notify@example.invalid", "Fixture");
        worker = worker(store, storage);
    }
    @AfterEach void cleanup() throws Exception { smtp.close(); }
    ClosureWorker worker(ClosureStore registry, DocumentStorageService backend) {
        var beans = new StaticListableBeanFactory(); beans.addBean("fixtureSmtp", sender);
        return new ClosureWorker(registry, generator, backend, beans.getBeanProvider(ClosureEmailTransport.class), settings);
    }
    MessageEnvelope<EmailProductionStatusPayload> email(long id, String event) {
        return new MessageEnvelope<>("SendProductionStatusEmail", event, "2026-11-20T20:00:00Z", "trace-email", "correlation-fixture",
                new EmailProductionStatusPayload(id, "org-fixture", "organizer@example.invalid", "Isolated event", "CERRADO", "2026-12-01T18:00:00", "Fixture venue"));
    }
    MessageEnvelope<QuoteCommandPayload> quote(long id, String event) {
        return new MessageEnvelope<>("GenerateClosingQuote", event, "2026-11-20T20:00:00Z", "trace-document", "correlation-fixture",
                new QuoteCommandPayload(id, "org-fixture", "Isolated event", "CERRADO", "2026-12-01T18:00:00", "Fixture venue", "2026-11-20T20:00:00Z"));
    }
    void ready(long id) {
        store.acceptDocument(quote(id, "quote-" + id)); worker.generateOne(); store.reconcile();
        assertThat(store.document(id).orElseThrow().state()).isEqualTo("READY");
    }
    String state(long id) { return jdbc.queryForObject("SELECT state FROM notify.closure_deliveries WHERE production_id=?", String.class, id); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM notify." + table, Integer.class); }

    @Test void emailBeforeDocumentWaitsWithoutSmtpThenSendsExactVerifiedAttachment() throws Exception {
        store.acceptEmail(email(8, "email-8")); worker.sendOne();
        assertThat(state(8)).isEqualTo("WAITING_DOCUMENT"); assertThat(smtp.submissions.get()).isZero();
        ready(8); worker.sendOne();
        assertThat(state(8)).isEqualTo("SMTP_ACCEPTED");
        assertThat(smtp.submissions.get()).isEqualTo(1);
        var doc = store.document(8).orElseThrow();
        var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(smtp.messages.getFirst()));
        byte[] attachment = attachment(message.getContent());
        assertThat(ClosurePdf.sha256(attachment)).isEqualTo(doc.sha());
        assertThat(attachment).isEqualTo(storage.retrieveDocumentExact("closures", doc.key()));
        assertThat(message.getHeader("X-EventoMax-Event-Id", null)).isEqualTo("email-8");
        assertThat(message.getHeader("X-EventoMax-Correlation-Id", null)).isEqualTo("correlation-fixture");
        assertThat(count("notification_delivery_audit")).isEqualTo(1);
    }
    @Test void documentBeforeEmailIsJoinedAfterCommit() {
        ready(8); assertThat(smtp.submissions.get()).isZero();
        store.acceptEmail(email(8, "email-8")); store.reconcile(); worker.sendOne();
        assertThat(state(8)).isEqualTo("SMTP_ACCEPTED"); assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @Test void sameAndDifferentEventIdsProduceOneBusinessDeliveryAndOneDocument() {
        store.acceptEmail(email(8, "email-8")); store.acceptEmail(email(8, "email-8")); store.acceptEmail(email(8, "email-other-id"));
        store.acceptDocument(quote(8, "quote-8")); store.acceptDocument(quote(8, "quote-8")); store.acceptDocument(quote(8, "quote-other-id"));
        worker.tick(); worker.tick();
        assertThat(count("closure_inbox")).isEqualTo(4); assertThat(count("closure_documents")).isEqualTo(1);
        assertThat(count("closure_deliveries")).isEqualTo(1); assertThat(count("closure_smtp_attempts")).isEqualTo(1);
        assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void concurrentConsumersAndWorkersCannotDoubleSend(boolean differentCommandIds) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2);
            List<Callable<Void>> calls = List.of(
                    () -> { barrier.await(); store.acceptEmail(email(8, "same-event")); return null; },
                    () -> { barrier.await(); store.acceptEmail(email(8, differentCommandIds ? "other-event" : "same-event")); return null; });
            for (var future : executor.invokeAll(calls)) future.get(10, TimeUnit.SECONDS);
            ready(8);
            var secondWorker = worker(new ClosureStore(jdbc,new DataSourceTransactionManager(dataSource),json,settings),storage);
            var sends = List.<Callable<Void>>of(() -> { worker.sendOne(); return null; }, () -> { secondWorker.sendOne(); return null; });
            for (var future : executor.invokeAll(sends)) future.get(10, TimeUnit.SECONDS);
            assertThat(count("closure_deliveries")).isEqualTo(1); assertThat(smtp.submissions.get()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void crashBeforeCommitRollsBackInboxAndIntent() {
        var manager = failingCommit(false);
        var failing = new ClosureStore(jdbc, manager, json, settings);
        assertThatThrownBy(() -> failing.acceptEmail(email(8, "before-commit"))).isInstanceOf(TransactionSystemException.class);
        assertThat(count("closure_inbox")).isZero(); assertThat(count("closure_deliveries")).isZero();
        store.acceptEmail(email(8, "before-commit")); ready(8); worker.sendOne();
        assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @Test void crashAfterCommitRedeliveryFindsExistingInbox() {
        var failing = new ClosureStore(jdbc, failingCommit(true), json, settings);
        assertThatThrownBy(() -> failing.acceptEmail(email(8, "after-commit"))).isInstanceOf(TransactionSystemException.class);
        assertThat(count("closure_inbox")).isEqualTo(1); assertThat(count("closure_deliveries")).isEqualTo(1);
        store.acceptEmail(email(8, "after-commit")); ready(8); worker.sendOne();
        assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @Test @Timeout(15)
    void contendedInboxInsertHasBoundedDatabaseWait() throws Exception {
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO notify.closure_inbox(consumer,event_id,production_id,envelope_json,envelope_sha256) VALUES ('EMAIL','locked-email',8,'{}',?)")) {
                statement.setString(1,"0".repeat(64));statement.executeUpdate();
                long began=System.nanoTime();
                assertThatThrownBy(()->store.acceptEmail(email(8,"locked-email")))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(Duration.ofNanos(System.nanoTime()-began)).isLessThan(Duration.ofSeconds(12));
            } finally {connection.rollback();}
        }
        assertThat(count("closure_inbox")).isZero();assertThat(count("closure_deliveries")).isZero();
    }
    DataSourceTransactionManager failingCommit(boolean afterCommit) {
        var fail = new AtomicBoolean(true);
        var manager = new DataSourceTransactionManager(dataSource) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                if (afterCommit) super.doCommit(status);
                if (fail.getAndSet(false)) throw new TransactionSystemException("fixture commit interruption");
                if (!afterCommit) super.doCommit(status);
            }
        };
        manager.setRollbackOnCommitFailure(true);
        return manager;
    }
    @Test void newWorkerRecoversPendingIntentsAfterRestart() {
        store.acceptEmail(email(8, "email-8")); store.acceptDocument(quote(8, "quote-8"));
        var restarted = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper(), settings);
        worker(restarted, storage).tick();
        assertThat(state(8)).isEqualTo("SMTP_ACCEPTED"); assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @Test void orphanStoredBeforeReadyCommitIsVerifiedAndRecovered() {
        store.acceptDocument(quote(8, "quote-8")); var doc = store.claimDocument().orElseThrow();
        byte[] bytes = generator.generateDurableClosure(quote(8, "quote-8"), doc.id());
        String key = "orphan-" + doc.id() + ".pdf";
        assertThat(store.prepared(doc, key, ClosurePdf.sha256(bytes), bytes.length)).isTrue();
        storage.storeDocumentIfAbsent("closures", key, bytes, "application/pdf");
        jdbc.update("UPDATE notify.closure_documents SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        store.reconcile(); worker.generateOne();
        var recovered = store.document(8).orElseThrow();
        assertThat(recovered.state()).isEqualTo("READY"); assertThat(recovered.key()).isEqualTo(key);
        assertThat(recovered.sha()).isEqualTo(ClosurePdf.sha256(bytes));
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING", "CORRUPT", "WRONG_PRODUCTION", "WRONG_DOCUMENT"})
    void invalidPdfCannotReachSmtp(String failure) throws Exception {
        store.acceptEmail(email(8, "email-8")); ready(8); var doc = store.document(8).orElseThrow();
        Path path = temp.resolve("storage/closures").resolve(doc.key());
        if (failure.equals("MISSING")) Files.delete(path);
        else {
            byte[] bytes = failure.equals("CORRUPT") ? "corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : ClosurePdfTest.pdf(failure.equals("WRONG_PRODUCTION") ? 9 : 8,
                    failure.equals("WRONG_DOCUMENT") ? UUID.randomUUID() : doc.id());
            Files.write(path, bytes);
            // A matching checksum alone must not authorize a different production/document.
            jdbc.update("UPDATE notify.closure_documents SET sha256=?,size_bytes=? WHERE id=?", ClosurePdf.sha256(bytes), bytes.length, doc.id());
        }
        worker.sendOne(); assertThat(state(8)).isEqualTo("FAILED_DOCUMENT"); assertThat(smtp.submissions.get()).isZero();
    }
    @Test void storageFailureIsDurableAndRetryRecoversWithoutLegacyFallback() {
        store.acceptEmail(email(8, "email-8")); store.acceptDocument(quote(8, "quote-8"));
        var broken = new DocumentStorageService() {
            public String storeDocument(String c,String f,byte[] b,String t) { throw new AssertionError("legacy path"); }
            public byte[] retrieveDocument(String c,String f) { return storage.retrieveDocument(c,f); }
            public byte[] retrieveDocumentExact(String c,String f) { return storage.retrieveDocumentExact(c,f); }
            public boolean documentExists(String c,String f) { return storage.documentExists(c,f); }
            public boolean storeDocumentIfAbsent(String c,String f,byte[] b,String t) { throw new IllegalStateException("fixture outage"); }
        };
        worker(store, broken).generateOne();
        assertThat(store.document(8).orElseThrow().state()).isEqualTo("RETRY_WAIT");
        assertThat(smtp.submissions.get()).isZero();
        jdbc.update("UPDATE notify.closure_documents SET next_attempt_at=CURRENT_TIMESTAMP");
        worker.tick(); assertThat(state(8)).isEqualTo("SMTP_ACCEPTED");
    }
    @Test void smtpPermanentRejectionIsTerminal() {
        store.acceptEmail(email(8, "email-8")); ready(8); smtp.mode = FixtureSmtpServer.Mode.REJECT_PERMANENT;
        worker.sendOne(); worker.tick();
        assertThat(state(8)).isEqualTo("FAILED_PERMANENT"); assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @Test void explicitTransientRejectionRetriesWithStableMessageId() throws Exception {
        store.acceptEmail(email(8, "email-8")); ready(8); smtp.mode = FixtureSmtpServer.Mode.REJECT_TRANSIENT;
        worker.sendOne(); assertThat(state(8)).isEqualTo("RETRY_WAIT");
        smtp.mode = FixtureSmtpServer.Mode.ACCEPT;
        jdbc.update("UPDATE notify.closure_deliveries SET next_attempt_at=CURRENT_TIMESTAMP");
        worker.sendOne(); assertThat(state(8)).isEqualTo("SMTP_ACCEPTED");
        var first = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(smtp.messages.get(0)));
        var second = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(smtp.messages.get(1)));
        assertThat(first.getMessageID()).isEqualTo(second.getMessageID()); assertThat(smtp.submissions.get()).isEqualTo(2);
    }
    @Test void ambiguousSmtpResultNeverAutomaticallyResends() {
        store.acceptEmail(email(8, "email-8")); ready(8); smtp.mode = FixtureSmtpServer.Mode.AMBIGUOUS;
        worker.sendOne(); assertThat(state(8)).isEqualTo("UNKNOWN");
        smtp.mode = FixtureSmtpServer.Mode.ACCEPT;
        worker.tick(); worker.sendOne();
        assertThat(state(8)).isEqualTo("UNKNOWN"); assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @Test void auditFailureAfterAcceptedSmtpBecomesUnknownWithoutResend() {
        store.acceptEmail(email(8, "email-8")); ready(8);
        jdbc.execute("ALTER TABLE notify.notification_delivery_audit ADD CONSTRAINT fixture_reject_audit CHECK (production_id<>8)");
        try {
            worker.sendOne(); assertThat(state(8)).isEqualTo("UNKNOWN");
            assertThat(count("notification_delivery_audit")).isZero();
            worker.tick(); assertThat(smtp.submissions.get()).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE notify.notification_delivery_audit DROP CONSTRAINT fixture_reject_audit"); }
    }
    @Test void expiredSendingLeaseIsUnknownEvenIfWorkerDiedBeforeSocketWrite() {
        store.acceptEmail(email(8, "email-8")); ready(8); store.claimDelivery().orElseThrow();
        jdbc.update("UPDATE notify.closure_deliveries SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        worker.tick(); assertThat(state(8)).isEqualTo("UNKNOWN"); assertThat(smtp.submissions.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM notify.closure_smtp_attempts", String.class)).isEqualTo("UNKNOWN");
    }
    @Test void missingContactIsRecordedPermanentlyAndNeverSubstituted() {
        var e = email(8, "email-8"); var p = e.payload();
        store.acceptEmail(new MessageEnvelope<>(e.type(),e.eventId(),e.timestamp(),e.traceId(),e.correlationId(),
                new EmailProductionStatusPayload(8L,p.organizerId(),null,p.productionName(),p.status(),p.scheduledAt(),p.location())));
        ready(8); worker.sendOne(); assertThat(state(8)).isEqualTo("FAILED_PERMANENT"); assertThat(smtp.submissions.get()).isZero();
    }
    @Test void documentDeadlineFailureIsExplicitAndDoesNotSendAvailabilityClaim() {
        store.acceptEmail(email(8, "email-8"));
        jdbc.update("UPDATE notify.closure_deliveries SET document_deadline=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        worker.tick(); assertThat(state(8)).isEqualTo("FAILED_DOCUMENT"); assertThat(smtp.submissions.get()).isZero();
    }
    @Test void conflictingContactOrEventReuseRollsBackWithoutReplacingFirstIntent() {
        var e = email(8,"email-8"); store.acceptEmail(e);
        var wrong = new EmailProductionStatusPayload(8L,"org-fixture","other@example.invalid","Isolated event","CERRADO","2026-12-01T18:00:00","Fixture venue");
        assertThatThrownBy(() -> store.acceptEmail(new MessageEnvelope<>(e.type(),"new-event",e.timestamp(),e.traceId(),e.correlationId(),wrong)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.acceptEmail(email(9,"email-8"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("closure_inbox")).isEqualTo(1); assertThat(count("closure_deliveries")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"CONFIRMADO","EN_MONTAJE"})
    void ordinaryStatusStillUsesExistingSmtpWithoutClosureRecord(String state) {
        sender.send(new EmailProductionStatusPayload(8L,"org-fixture","organizer@example.invalid","Isolated event",state,"2026-12-01T18:00:00","Fixture venue"));
        assertThat(smtp.submissions.get()).isEqualTo(1); assertThat(count("closure_deliveries")).isZero();
    }
    @Test void pausedDispatcherCannotSend() {
        store.acceptEmail(email(8,"email-8")); ready(8);
        var beans = new StaticListableBeanFactory(); beans.addBean("smtp",sender);
        var paused = new ClosureSettings(true,true,false,settings.lease(),settings.documentWait(),3,10000000);
        new ClosureWorker(store,generator,storage,beans.getBeanProvider(ClosureEmailTransport.class),paused).sendOne();
        assertThat(state(8)).isEqualTo("READY"); assertThat(smtp.submissions.get()).isZero();
    }
    @Test void migrationIsAdditiveAndDoesNotDuplicateHistory() {
        Flyway.configure().dataSource(dataSource).schemas("notify").defaultSchema("notify").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify.flyway_schema_history WHERE success=true AND version IN ('1','2')",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify.processed_events WHERE event_id='pre-v2-marker'",Integer.class)).isEqualTo(1);
    }
    @Test void staleGenerationCannotPublishReadyAfterAnotherLeaseClaimsTheDocument() {
        store.acceptDocument(quote(8,"quote-8")); var stale=store.claimDocument().orElseThrow();
        jdbc.update("UPDATE notify.closure_documents SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        store.reconcile(); var current=store.claimDocument().orElseThrow();
        assertThat(current.token()).isNotEqualTo(stale.token());
        assertThat(store.prepared(stale,"stale.pdf","0".repeat(64),1024)).isFalse();
        assertThat(store.documentReady(stale)).isFalse();
        assertThat(store.document(8).orElseThrow().state()).isEqualTo("GENERATING");
    }
    @Test void jdbcConnectionFailureAfterSmtpAcceptanceIsNeverClassifiedAsSafeSmtpRetry() {
        store.acceptEmail(email(8,"email-8")); ready(8);
        var failing=new ClosureStore(jdbc,new DataSourceTransactionManager(dataSource),json,settings) {
            @Override public void finish(Delivery delivery,ClosureState state,String code) {
                if(state==ClosureState.SMTP_ACCEPTED) throw new org.springframework.jdbc.CannotGetJdbcConnectionException(
                        "fixture database outage",new java.sql.SQLException("fixture connection failure",new java.net.ConnectException("fixture refused")));
                super.finish(delivery,state,code);
            }
        };
        worker(failing,storage).sendOne();
        assertThat(state(8)).isEqualTo("UNKNOWN"); worker.tick(); assertThat(smtp.submissions.get()).isEqualTo(1);
    }
    @Test void repeatedExplicitTransientRejectionsStopAtAttemptLimit() {
        store.acceptEmail(email(8,"email-8")); ready(8); smtp.mode=FixtureSmtpServer.Mode.REJECT_TRANSIENT;
        for(int i=0;i<settings.maxAttempts();i++) {
            jdbc.update("UPDATE notify.closure_deliveries SET next_attempt_at=CURRENT_TIMESTAMP"); worker.sendOne();
        }
        worker.tick(); assertThat(state(8)).isEqualTo("FAILED_PERMANENT");
        assertThat(smtp.submissions.get()).isEqualTo(settings.maxAttempts());
    }
    @Test void existingDownloadRouteUsesRegistryAndNeverAnOlderAliasForPendingDocument() throws Exception {
        byte[] historical=ClosurePdfTest.pdf(99,UUID.randomUUID());
        storage.storeDocument("quotes","quote-production-99-latest.pdf",historical,"application/pdf");
        storage.storeDocument("quotes","quote-production-8-latest.pdf",historical,"application/pdf");
        var controller=new cl.duoc.eventomax.notify.controller.NotifyQuoteController(storage,temp.resolve("unused").toString());
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"closureStore",store);
        assertThat(controller.getQuotePdf(99L).getBody()).isEqualTo(historical);
        store.acceptDocument(quote(8,"quote-8"));
        assertThat(controller.getQuotePdf(8L).getStatusCode().value()).isEqualTo(404);
        worker.generateOne();
        var response=controller.getQuotePdf(8L); var doc=store.document(8).orElseThrow();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ClosurePdf.sha256(response.getBody())).isEqualTo(doc.sha());
    }
    @Test void realRabbitQuoteAckDoesNotWaitForPdfGeneration() throws Exception {
        var factory=new ConnectionFactory();factory.setHost(RABBIT.getHost());factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername("fixture");factory.setPassword("fixture");
        try(var connection=factory.newConnection();var channel=connection.createChannel()) {
            channel.exchangeDeclare("cmd.direct","direct",true);
            channel.queueDeclare("q.cmd.quote",true,false,false,null);channel.queueBind("q.cmd.quote","cmd.direct","quote.gen");
            channel.confirmSelect();channel.basicPublish("cmd.direct","quote.gen",null,json.writeValueAsBytes(quote(8,"rabbit-quote")));
            channel.waitForConfirmsOrDie(5000);var received=channel.basicGet("q.cmd.quote",false);assertThat(received).isNotNull();
            var observed=mock(Channel.class,org.mockito.AdditionalAnswers.delegatesTo(channel));
            doAnswer(inv->{
                assertThat(store.document(8).orElseThrow().state()).isEqualTo("PENDING");
                assertThat(count("closure_inbox")).isEqualTo(1);channel.basicAck(inv.getArgument(0),false);return null;
            }).when(observed).basicAck(anyLong(),eq(false));
            var listener=new QuoteCommandListener(json,generator,new InMemoryProcessedEventStore(),RetryTemplate.builder().maxAttempts(1).build());
            listener.setClosureStore(store);
            listener.handleQuoteCommand(new org.springframework.amqp.core.Message(received.getBody(),new org.springframework.amqp.core.MessageProperties()),observed,received.getEnvelope().getDeliveryTag());
            verify(observed).basicAck(received.getEnvelope().getDeliveryTag(),false);
            assertThat(smtp.submissions.get()).isZero();
        }
    }
    @Test void realRabbitAckObservesCommittedInboxAndInvalidMessageGoesToDlq() throws Exception {
        var factory = new ConnectionFactory(); factory.setHost(RABBIT.getHost()); factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername("fixture"); factory.setPassword("fixture");
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            channel.exchangeDeclare("cmd.direct","direct",true); channel.exchangeDeclare("cmd.dead.dlx","direct",true);
            channel.queueDeclare("q.cmd.email",true,false,false,Map.of("x-dead-letter-exchange","cmd.dead.dlx","x-dead-letter-routing-key","q.cmd.email.dlq"));
            channel.queueDeclare("q.cmd.email.dlq",true,false,false,null);
            channel.queueBind("q.cmd.email","cmd.direct","email.send"); channel.queueBind("q.cmd.email.dlq","cmd.dead.dlx","q.cmd.email.dlq");
            channel.confirmSelect(); channel.basicPublish("cmd.direct","email.send",null,json.writeValueAsBytes(email(8,"rabbit-email"))); channel.waitForConfirmsOrDie(5000);
            var received = channel.basicGet("q.cmd.email",false); assertThat(received).isNotNull();
            var observed = mock(Channel.class, org.mockito.AdditionalAnswers.delegatesTo(channel));
            doAnswer(inv -> {
                assertThat(count("closure_inbox")).isEqualTo(1); assertThat(count("closure_deliveries")).isEqualTo(1);
                channel.basicAck(inv.getArgument(0),false); return null;
            }).when(observed).basicAck(anyLong(),eq(false));
            var listener = new EmailCommandListener(json,new EmailNotificationService(sender),new InMemoryProcessedEventStore(),RetryTemplate.builder().maxAttempts(1).build());
            listener.setClosureStore(store);
            listener.handleEmailCommand(new org.springframework.amqp.core.Message(received.getBody(),new org.springframework.amqp.core.MessageProperties()),observed,received.getEnvelope().getDeliveryTag());
            verify(observed).basicAck(received.getEnvelope().getDeliveryTag(),false); assertThat(smtp.submissions.get()).isZero();
            channel.basicPublish("cmd.direct","email.send",null,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); channel.waitForConfirmsOrDie(5000);
            var invalid = channel.basicGet("q.cmd.email",false);
            listener.handleEmailCommand(new org.springframework.amqp.core.Message(invalid.getBody(),new org.springframework.amqp.core.MessageProperties()),channel,invalid.getEnvelope().getDeliveryTag());
            // Passive declare follows the NACK on the same channel; poll only this disposable DLQ.
            com.rabbitmq.client.GetResponse dead = null;
            for (int i=0;i<50 && dead==null;i++) { dead=channel.basicGet("q.cmd.email.dlq",true); if(dead==null) Thread.sleep(20); }
            assertThat(dead).isNotNull(); assertThat(channel.isOpen()).isTrue();
        }
    }
    private byte[] attachment(Object content) throws Exception {
        if (content instanceof Multipart multipart) for(int i=0;i<multipart.getCount();i++) {
            var part=multipart.getBodyPart(i);
            if(Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return part.getInputStream().readAllBytes();
            byte[] found=attachment(part.getContent()); if(found!=null) return found;
        }
        return null;
    }
}
