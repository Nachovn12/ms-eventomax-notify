package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LoggingEmailSenderTest {

    @Test
    @DisplayName("LoggingEmailSender no lanza excepción")
    void send_doesNotThrow() {
        var sender = new LoggingEmailSender();
        var payload = new EmailProductionStatusPayload(
                300L, "org-003", "Obra Teatro", "CANCELLED",
                "2026-12-25T21:00:00", "Concepción, Chile"
        );

        assertDoesNotThrow(() -> sender.send(payload));
    }
}
