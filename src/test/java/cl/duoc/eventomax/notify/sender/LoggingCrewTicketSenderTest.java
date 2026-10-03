package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LoggingCrewTicketSenderTest {

    @Test
    void send_shouldNotThrowException() {
        LoggingCrewTicketSender sender = new LoggingCrewTicketSender();
        CrewTicketPayload payload = new CrewTicketPayload(100L, "Prod", "2026-10-01", "Loc", "EN_MONTAJE");

        assertDoesNotThrow(() -> sender.send(payload));
    }
}
