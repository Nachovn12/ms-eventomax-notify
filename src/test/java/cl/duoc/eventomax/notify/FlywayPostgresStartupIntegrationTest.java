package cl.duoc.eventomax.notify;

import cl.duoc.eventomax.notify.model.CrewTicket;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import cl.duoc.eventomax.notify.model.ProcessedEvent;
import cl.duoc.eventomax.notify.repository.CrewTicketRepository;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import cl.duoc.eventomax.notify.repository.ProcessedEventRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=notify",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.default-schema=notify",
        "spring.flyway.schemas=notify",
        "spring.flyway.create-schemas=true"
})
@EnableAutoConfiguration(exclude = RabbitAutoConfiguration.class)
@Testcontainers
class FlywayPostgresStartupIntegrationTest {

    private static final String APP_DB_USER = "eventomax_notify_app";
    private static final String APP_DB_PASSWORD = "notify_app_test_secret";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("eventomax_notify")
            .withUsername("postgres_admin")
            .withPassword("postgres_admin_pwd");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE SCHEMA IF NOT EXISTS notify");
            stmt.execute("DO $$ BEGIN " +
                    "IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '" + APP_DB_USER + "') THEN " +
                    "CREATE ROLE " + APP_DB_USER + " WITH LOGIN PASSWORD '" + APP_DB_PASSWORD + "'; " +
                    "END IF; " +
                    "END $$");
            stmt.execute("REVOKE CREATE ON DATABASE eventomax_notify FROM PUBLIC");
            stmt.execute("GRANT CONNECT ON DATABASE eventomax_notify TO " + APP_DB_USER);
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA notify TO " + APP_DB_USER);
        } catch (Exception ex) {
            throw new IllegalStateException("Error preparando esquema notify y permisos en PostgreSQL Testcontainer", ex);
        }

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> APP_DB_USER);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private CrewTicketRepository crewTicketRepository;

    @Autowired
    private NotificationDeliveryAuditRepository notificationDeliveryAuditRepository;

    @Test
    @DisplayName("Spring Boot 4.1.1 ejecuta Flyway V1 en esquema notify antes de ddl-auto=validate en PostgreSQL")
    void flywayMigratesNotifySchemaBeforeHibernateValidation() {
        assertNotNull(flyway, "El bean Flyway debe estar autoconfigurado por spring-boot-starter-flyway");
        assertNotNull(flyway.info().current(), "La migración V1 debe haberse aplicado al iniciar el contexto");
        assertEquals("1", flyway.info().current().getVersion().getVersion());

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notify.flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );
        assertEquals(1, historyCount, "flyway_schema_history debe registrar versión 1 exitosa en el esquema notify");

        List<String> tablesInNotifySchema = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'notify' " +
                        "AND table_name IN ('processed_events', 'crew_tickets', 'notification_delivery_audit') " +
                        "ORDER BY table_name",
                String.class
        );
        assertEquals(
                List.of("crew_tickets", "notification_delivery_audit", "processed_events"),
                tablesInNotifySchema,
                "Las tres tablas de Notify deben existir en el esquema notify"
        );
    }

    @Test
    @DisplayName("Repositorios JPA persisten y consultan en processed_events, crew_tickets y notification_delivery_audit bajo esquema notify")
    void repositoriesPersistAndQueryInNotifySchema() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 13, 30, 0);

        ProcessedEvent event = new ProcessedEvent(
                "evt-flyway-it-001",
                "PRODUCTION_CONFIRMED",
                now,
                "trace-flyway-it-001",
                "corr-flyway-it-001"
        );
        processedEventRepository.saveAndFlush(event);
        assertTrue(processedEventRepository.existsById("evt-flyway-it-001"));

        CrewTicket ticket = new CrewTicket(
                501L,
                "evt-crew-it-001",
                "Festival Cloud Native EP2",
                now.plusDays(5),
                "Movistar Arena",
                "EMITIDO",
                "Montaje de escenario principal"
        );
        CrewTicket savedTicket = crewTicketRepository.saveAndFlush(ticket);
        assertNotNull(savedTicket.getId());
        assertTrue(crewTicketRepository.findTopByProductionIdOrderByIdDesc(501L).isPresent());

        NotificationDeliveryAudit audit = new NotificationDeliveryAudit(
                501L,
                "evt-crew-it-001",
                "CREW_TICKET",
                "cuadrilla@eventomax.cl",
                "DELIVERED",
                null
        );
        NotificationDeliveryAudit savedAudit = notificationDeliveryAuditRepository.saveAndFlush(audit);
        assertNotNull(savedAudit.getId());
        assertFalse(notificationDeliveryAuditRepository.findByEventId("evt-crew-it-001").isEmpty());
    }
}
