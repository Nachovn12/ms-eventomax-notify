package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import cl.duoc.eventomax.notify.sender.CrewTicketSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CrewTicketNotificationServiceTest {

    @Mock
    private CrewTicketSender crewTicketSender;

    private CrewTicketNotificationService service;

    @BeforeEach
    void setUp() {
        service = new CrewTicketNotificationService(crewTicketSender);
    }

    @Test
    void processCrewTicket_shouldDelegateToSender() {
        CrewTicketPayload payload = new CrewTicketPayload(100L, "Prod", "2026-10-01", "Loc", "EN_MONTAJE");
        MessageEnvelope<CrewTicketPayload> envelope = new MessageEnvelope<>(
                "GenerateCrewTicket", "evt-1", "time", "trace", "corr", payload
        );

        service.processCrewTicket(envelope);

        verify(crewTicketSender, times(1)).send(payload);
    }
}
