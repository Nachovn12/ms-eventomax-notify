package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import cl.duoc.eventomax.notify.model.CrewTicket;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import cl.duoc.eventomax.notify.repository.CrewTicketRepository;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseCrewTicketSenderTest {

    @Mock
    private CrewTicketRepository crewTicketRepository;

    @Mock
    private NotificationDeliveryAuditRepository auditRepository;

    private DatabaseCrewTicketSender sender;

    @BeforeEach
    void setUp() {
        sender = new DatabaseCrewTicketSender(crewTicketRepository, auditRepository);
    }

    @Test
    @DisplayName("Guarda un ticket de cuadrilla nuevo y registra auditoría de entrega")
    void send_newTicket_shouldSaveTicketAndAudit() {
        CrewTicketPayload payload = new CrewTicketPayload(
                10L,
                "Festival Rock",
                "2026-11-20T18:00:00",
                "Estadio Nacional",
                "EN_MONTAJE"
        );
        String eventId = "evt-crew-101";

        when(crewTicketRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(crewTicketRepository.save(any(CrewTicket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        sender.send(payload, eventId);

        ArgumentCaptor<CrewTicket> ticketCaptor = ArgumentCaptor.forClass(CrewTicket.class);
        verify(crewTicketRepository).save(ticketCaptor.capture());
        CrewTicket savedTicket = ticketCaptor.getValue();

        assertEquals(10L, savedTicket.getProductionId());
        assertEquals(eventId, savedTicket.getEventId());
        assertEquals("Festival Rock", savedTicket.getProductionName());
        assertEquals("Estadio Nacional", savedTicket.getLocation());
        assertEquals("EN_MONTAJE", savedTicket.getStatus());
        assertNotNull(savedTicket.getInstructions());

        ArgumentCaptor<NotificationDeliveryAudit> auditCaptor = ArgumentCaptor.forClass(NotificationDeliveryAuditRepository.class != null ? NotificationDeliveryAudit.class : null);
        verify(auditRepository).save(auditCaptor.capture());
        NotificationDeliveryAudit audit = auditCaptor.getValue();

        assertEquals(10L, audit.getProductionId());
        assertEquals(eventId, audit.getEventId());
        assertEquals("CREW_TICKET", audit.getNotificationType());
        assertEquals("PERSISTED", audit.getStatus());
    }

    @Test
    @DisplayName("Actualiza ticket existente con el mismo eventId")
    void send_existingTicket_shouldUpdate() {
        CrewTicketPayload payload = new CrewTicketPayload(
                10L,
                "Festival Rock Actualizado",
                "2026-11-20T19:00:00",
                "Movistar Arena",
                "EN_MONTAJE"
        );
        String eventId = "evt-crew-101";

        CrewTicket existing = new CrewTicket(
                10L, eventId, "Festival Rock", null, "Estadio Nacional", "SOLICITADO", "Instrucciones previas"
        );

        when(crewTicketRepository.findByEventId(eventId)).thenReturn(Optional.of(existing));
        when(crewTicketRepository.save(any(CrewTicket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        sender.send(payload, eventId);

        verify(crewTicketRepository).save(existing);
        assertEquals("Festival Rock Actualizado", existing.getProductionName());
        assertEquals("Movistar Arena", existing.getLocation());
    }

    @Test
    @DisplayName("Rechaza payload nulo o sin productionId")
    void send_nullPayload_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> sender.send(null, "evt-1"));
        assertThrows(IllegalArgumentException.class, () -> sender.send(new CrewTicketPayload(null, "P", "2026-01-01", "L", "S"), "evt-1"));
    }
}

