package cl.duoc.eventomax.notify.closure;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.service.QuoteNotificationService;
import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import cl.duoc.eventomax.notify.storage.LocalFileSystemDocumentStorageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD verification of safe rollout controls:
 * 1. Cohort allowlist validation and SQL filtering before locks in claimDelivery().
 * 2. Independent worker pause (tick, generateOne, sendOne guard).
 * 3. Concurrent worker SKIP LOCKED safety with PostgreSQL.
 */
@Testcontainers
class ClosureCohortSecurityTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    @TempDir
    Path temp;

    final ObjectMapper json = new ObjectMapper();
    ClosureStore store;
    DocumentStorageService storage;
    QuoteNotificationService generator;
    ClosureWorker worker;

    @BeforeAll
    static void initDb() {
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas("notify").defaultSchema("notify").load().migrate();
    }

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE notify.closure_smtp_attempts, notify.closure_deliveries, notify.closure_documents, notify.closure_inbox, notify.notification_delivery_audit RESTART IDENTITY CASCADE");
        storage = new LocalFileSystemDocumentStorageService(temp.resolve("storage").toString());
        generator = mock(QuoteNotificationService.class);
    }

    // --- A. ClosureSettings Configuration & Validation ---

    @Test
    void settings_defaults_workerEnabledFalse_and_cohortEmpty() {
        var s = new ClosureSettings(true, false, true, false, Duration.ofMinutes(2), Duration.ofMinutes(15), 5, 10000000, List.of());
        assertThat(s.workerEnabled()).isFalse();
        assertThat(s.dispatchEnabled()).isFalse();
        assertThat(s.authorizedProductions()).isEmpty();
    }

    @Test
    void settings_rejects_negative_or_zero_production_ids() {
        assertThatThrownBy(() -> new ClosureSettings(true, true, true, true, Duration.ofMinutes(2), Duration.ofMinutes(15), 5, 10000000, List.of(-1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");

        assertThatThrownBy(() -> new ClosureSettings(true, true, true, true, Duration.ofMinutes(2), Duration.ofMinutes(15), 5, 10000000, List.of(0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    void settings_rejects_duplicate_production_ids() {
        assertThatThrownBy(() -> new ClosureSettings(true, true, true, true, Duration.ofMinutes(2), Duration.ofMinutes(15), 5, 10000000, List.of(10L, 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate production ID in cohort: 10");
    }

    @Test
    void settings_accepts_positive_unique_production_ids() {
        var s = new ClosureSettings(true, true, true, true, Duration.ofMinutes(2), Duration.ofMinutes(15), 5, 10000000, List.of(10L, 20L));
        assertThat(s.authorizedProductions()).containsExactly(10L, 20L);
        assertThat(s.authorizedProductionsSet()).containsExactlyInAnyOrder(10L, 20L);
    }

    // --- A.1. Spring Boot ConfigurationProperties Binding Tests ---

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClosureSettings.class)
    static class BindingTestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingTestConfig.class);

    @Test
    void springBinding_empty_produces_empty_cohort() {
        contextRunner.withPropertyValues("eventomax.notify.closure.authorized-productions=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(ClosureSettings.class);
                    assertThat(settings.authorizedProductions()).isEmpty();
                });
    }

    @Test
    void springBinding_valid_produces_cohort_list() {
        contextRunner.withPropertyValues("eventomax.notify.closure.authorized-productions=10, 20")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(ClosureSettings.class);
                    assertThat(settings.authorizedProductions()).containsExactly(10L, 20L);
                });
    }

    @Test
    void springBinding_duplicates_fails_startup() {
        contextRunner.withPropertyValues("eventomax.notify.closure.authorized-productions=10,10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Duplicate production ID in cohort: 10");
                });
    }

    @Test
    void springBinding_malformed_fails_startup() {
        contextRunner.withPropertyValues("eventomax.notify.closure.authorized-productions=10,abc")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NumberFormatException.class);
                });
    }

    @Test
    void springBinding_zero_or_negative_fails_startup() {
        contextRunner.withPropertyValues("eventomax.notify.closure.authorized-productions=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Authorized production IDs must be strictly positive: 0");
                });

        contextRunner.withPropertyValues("eventomax.notify.closure.authorized-productions=-5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Authorized production IDs must be strictly positive: -5");
                });
    }

    // --- B. Cohort Filtering in claimDelivery ---

    @Test
    void claimDelivery_cohortEmpty_dispatchEnabled_returnsEmpty() {
        var settings = new ClosureSettings(true, true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of());
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "READY");

        var claim = store.claimDelivery();
        assertThat(claim).isEmpty();

        // Ensure delivery remains untouched in READY
        var state = jdbc.queryForObject("SELECT state FROM notify.closure_deliveries WHERE production_id=10", String.class);
        var attempts = jdbc.queryForObject("SELECT attempts FROM notify.closure_deliveries WHERE production_id=10", Integer.class);
        assertThat(state).isEqualTo("READY");
        assertThat(attempts).isEqualTo(0);
    }

    @Test
    void claimDelivery_dispatchDisabled_returnsEmpty_evenWithCohort() {
        var settings = new ClosureSettings(true, true, true, false, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "READY");

        var claim = store.claimDelivery();
        assertThat(claim).isEmpty();
    }

    @Test
    void claimDelivery_authorizedProduction_claimedSuccessfully() {
        var settings = new ClosureSettings(true, true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "READY");

        var claim = store.claimDelivery();
        assertThat(claim).isPresent();
        assertThat(claim.get().productionId()).isEqualTo(10L);

        // Verify state is now SENDING, attempt incremented and attempt record inserted
        var state = jdbc.queryForObject("SELECT state FROM notify.closure_deliveries WHERE production_id=10", String.class);
        var attempts = jdbc.queryForObject("SELECT attempts FROM notify.closure_deliveries WHERE production_id=10", Integer.class);
        assertThat(state).isEqualTo("SENDING");
        assertThat(attempts).isEqualTo(1);

        int attemptRecords = jdbc.queryForObject("SELECT COUNT(*) FROM notify.closure_smtp_attempts WHERE delivery_id=?", Integer.class, claim.get().id());
        assertThat(attemptRecords).isEqualTo(1);
    }

    @Test
    void claimDelivery_excludedProduction_remainsUntouched() {
        var settings = new ClosureSettings(true, true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(99L, "READY");

        var claim = store.claimDelivery();
        assertThat(claim).isEmpty();

        var state = jdbc.queryForObject("SELECT state FROM notify.closure_deliveries WHERE production_id=99", String.class);
        var attempts = jdbc.queryForObject("SELECT attempts FROM notify.closure_deliveries WHERE production_id=99", Integer.class);
        var leaseUntil = jdbc.queryForObject("SELECT lease_until FROM notify.closure_deliveries WHERE production_id=99", String.class);
        assertThat(state).isEqualTo("READY");
        assertThat(attempts).isEqualTo(0);
        assertThat(leaseUntil).isNull();
    }

    @Test
    void claimDelivery_mixedCohort_claimsOnlyAuthorizedInOrder() {
        var settings = new ClosureSettings(true, true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(20L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "READY");
        createDelivery(20L, "READY");
        createDelivery(30L, "READY");

        var claim1 = store.claimDelivery();
        assertThat(claim1).isPresent();
        assertThat(claim1.get().productionId()).isEqualTo(20L);

        // Subsequent claim yields empty because 10 and 30 are not authorized
        var claim2 = store.claimDelivery();
        assertThat(claim2).isEmpty();
    }

    @Test
    void claimDelivery_excludedStates_neverClaimed() {
        var settings = new ClosureSettings(true, true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "UNKNOWN");
        assertThat(store.claimDelivery()).isEmpty();

        jdbc.update("UPDATE notify.closure_deliveries SET state='FAILED_PERMANENT' WHERE production_id=10");
        assertThat(store.claimDelivery()).isEmpty();

        jdbc.update("UPDATE notify.closure_deliveries SET state='SMTP_ACCEPTED' WHERE production_id=10");
        assertThat(store.claimDelivery()).isEmpty();
    }

    @Test
    void claimDelivery_retryWait_claimedWhenDue() {
        var settings = new ClosureSettings(true, true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "RETRY_WAIT");
        jdbc.update("UPDATE notify.closure_deliveries SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE production_id=10");

        var claim = store.claimDelivery();
        assertThat(claim).isPresent();
        assertThat(claim.get().productionId()).isEqualTo(10L);
    }

    @Test
    void claimDelivery_concurrentWorkers_skipLockedGuaranteesSingleClaim() throws Exception {
        var settings = new ClosureSettings(true, true, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "READY");

        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var f1 = executor.submit(() -> {
                barrier.await();
                return store.claimDelivery();
            });
            var f2 = executor.submit(() -> {
                barrier.await();
                return store.claimDelivery();
            });

            var r1 = f1.get(5, TimeUnit.SECONDS);
            var r2 = f2.get(5, TimeUnit.SECONDS);

            int presentCount = (r1.isPresent() ? 1 : 0) + (r2.isPresent() ? 1 : 0);
            assertThat(presentCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // --- C. Independent Worker Pause ---

    @Test
    void worker_paused_tickDoesZeroWork_zeroReconcile_zeroGenerations_zeroDispatches() {
        var settings = new ClosureSettings(true, false, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "READY");
        // Create an expired lease delivery that reconcile would otherwise transition
        jdbc.update("UPDATE notify.closure_deliveries SET state='SENDING', lease_until=CURRENT_TIMESTAMP - INTERVAL '10 seconds' WHERE production_id=10");

        var beans = new StaticListableBeanFactory();
        var transport = mock(ClosureEmailTransport.class);
        beans.addBean("mockTransport", transport);
        worker = new ClosureWorker(store, generator, storage, beans.getBeanProvider(ClosureEmailTransport.class), settings);

        // Run tick() while workerEnabled = false
        worker.tick();

        // State must remain SENDING (NOT transitioned to UNKNOWN by reconcile)
        var state = jdbc.queryForObject("SELECT state FROM notify.closure_deliveries WHERE production_id=10", String.class);
        assertThat(state).isEqualTo("SENDING");
        verifyNoInteractions(transport);
        verifyNoInteractions(generator);
    }

    @Test
    void worker_paused_directCalls_generateOne_and_sendOne_areGuarded() {
        var settings = new ClosureSettings(true, false, true, true, Duration.ofSeconds(30), Duration.ofMinutes(15), 3, 10000000, List.of(10L));
        store = new ClosureStore(jdbc, new DataSourceTransactionManager(dataSource), json, settings);

        createDelivery(10L, "READY");

        var beans = new StaticListableBeanFactory();
        var transport = mock(ClosureEmailTransport.class);
        beans.addBean("mockTransport", transport);
        worker = new ClosureWorker(store, generator, storage, beans.getBeanProvider(ClosureEmailTransport.class), settings);

        // Invocaciones directas no deben hacer nada cuando workerEnabled = false
        worker.generateOne();
        worker.sendOne();

        var state = jdbc.queryForObject("SELECT state FROM notify.closure_deliveries WHERE production_id=10", String.class);
        assertThat(state).isEqualTo("READY");
        verifyNoInteractions(transport);
        verifyNoInteractions(generator);
    }

    // --- Helpers ---

    private void createDelivery(long productionId, String state) {
        UUID docId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var quoteEnvelope = new MessageEnvelope<>("GenerateClosingQuote", "cmd-quote-" + productionId, "2026-11-20T20:00:00Z", "trace-1", "corr-1",
                new QuoteCommandPayload(productionId, "org-1", "Event " + productionId, "CERRADO", "2026-12-01T18:00:00", "Venue", "2026-11-20T20:00:00Z"));
        var emailEnvelope = new MessageEnvelope<>("SendProductionStatusEmail", "cmd-email-" + productionId, "2026-11-20T20:00:00Z", "trace-1", "corr-1",
                new EmailProductionStatusPayload(productionId, "org-1", "test@example.com", "Event " + productionId, "CERRADO", "2026-12-01T18:00:00", "Venue"));

        jdbc.update("""
                INSERT INTO notify.closure_documents
                (id, production_id, organizer_id, command_event_id, envelope_json, state, storage_key, sha256, size_bytes)
                VALUES (?, ?, 'org-1', ?, ?, 'READY', 'quote.pdf', ?, 1024)
                """, docId, productionId, "cmd-quote-" + productionId, json.writeValueAsString(quoteEnvelope), sha);

        jdbc.update("""
                INSERT INTO notify.closure_deliveries
                (id, production_id, organizer_id, command_event_id, envelope_json, state, document_id, attempts, document_deadline)
                VALUES (?, ?, 'org-1', ?, ?, ?, ?, 0, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, deliveryId, productionId, "cmd-email-" + productionId, json.writeValueAsString(emailEnvelope), state, docId);
    }
}
