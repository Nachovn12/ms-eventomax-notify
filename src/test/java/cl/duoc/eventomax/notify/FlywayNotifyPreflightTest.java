package cl.duoc.eventomax.notify;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class FlywayNotifyPreflightTest {

    @Test
    @DisplayName("Preflight 2: Flyway nativo aplica V1__create_notify_tables.sql en base descartable")
    void testFlywayV1NotifyOnDisposable() throws Exception {
        File pwFile = new File("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/postgres_password.txt");
        Assumptions.assumeTrue(pwFile.exists(), "Skipping local PostgreSQL disposable preflight in CI environment");
        String password = Files.readString(pwFile.toPath()).trim();
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/eventomax_test_notify_disposable";

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "postgres", password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();

        MigrateResult result = flyway.migrate();
        assertTrue(result.success);
        String currentVersion = (result.targetSchemaVersion != null)
                ? result.targetSchemaVersion
                : (flyway.info().current() != null ? flyway.info().current().getVersion().getVersion() : null);
        assertEquals("1", currentVersion);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", password);
             Statement stmt = conn.createStatement()) {

            // Verificar tabla crew_tickets
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_name = 'crew_tickets' AND column_name = 'event_id'")) {
                assertTrue(rs.next(), "Columna event_id debe existir en crew_tickets");
            }

            // Verificar tabla notification_delivery_audit
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_name = 'notification_delivery_audit' AND column_name = 'recipient'")) {
                assertTrue(rs.next(), "Columna recipient debe existir en notification_delivery_audit");
            }

            // Verificar tabla processed_events
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_name = 'processed_events' AND column_name = 'event_id'")) {
                assertTrue(rs.next(), "Columna event_id debe existir en processed_events");
            }

            // Verificar flyway_schema_history version 1 y checksum entero CRC32
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT version, description, success, checksum FROM flyway_schema_history WHERE version = '1'")) {
                assertTrue(rs.next());
                assertEquals("create notify tables", rs.getString("description"));
                assertTrue(rs.getBoolean("success"));
                assertNotNull(rs.getObject("checksum"), "Checksum CRC32 debe estar registrado");
            }
        }
    }
}

