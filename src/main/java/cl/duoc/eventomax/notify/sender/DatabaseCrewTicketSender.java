package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import cl.duoc.eventomax.notify.model.CrewTicket;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import cl.duoc.eventomax.notify.repository.CrewTicketRepository;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
@Primary
@ConditionalOnProperty(name = "eventomax.notify.crew.provider", havingValue = "database", matchIfMissing = true)
public class DatabaseCrewTicketSender implements CrewTicketSender {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCrewTicketSender.class);

    private final CrewTicketRepository crewTicketRepository;
    private final NotificationDeliveryAuditRepository auditRepository;

    public DatabaseCrewTicketSender(CrewTicketRepository crewTicketRepository,
                                    NotificationDeliveryAuditRepository auditRepository) {
        this.crewTicketRepository = crewTicketRepository;
        this.auditRepository = auditRepository;
    }

    @Override
    @Transactional
    public void send(CrewTicketPayload payload) {
        String generatedEventId = "crew-evt-" + UUID.randomUUID().toString().substring(0, 8);
        send(payload, generatedEventId);
    }

    @Override
    @Transactional
    public void send(CrewTicketPayload payload, String eventId) {
        if (payload == null) {
            throw new IllegalArgumentException("CrewTicketPayload no puede ser nulo");
        }
        if (payload.productionId() == null) {
            throw new IllegalArgumentException("productionId no puede ser nulo");
        }

        String actualEventId = (eventId != null && !eventId.isBlank())
                ? eventId
                : "crew-evt-" + UUID.randomUUID().toString().substring(0, 8);

        LocalDateTime scheduledAt = parseScheduledAt(payload.scheduledAt());
        String instructions = "Hoja de ruta para montaje de equipamiento de la producción '"
                + (payload.productionName() != null ? payload.productionName() : "Producción #" + payload.productionId())
                + "' en recinto: " + (payload.location() != null ? payload.location() : "Sin ubicación especificada")
                + ". Estado operacional: " + (payload.status() != null ? payload.status() : "EN_MONTAJE")
                + ". Verificar dotación técnica y conexiones de energía/sonido.";

        CrewTicket ticket = crewTicketRepository.findByEventId(actualEventId)
                .orElseGet(() -> new CrewTicket(
                        payload.productionId(),
                        actualEventId,
                        payload.productionName() != null ? payload.productionName() : "Producción #" + payload.productionId(),
                        scheduledAt,
                        payload.location() != null ? payload.location() : "Sin ubicación",
                        payload.status() != null ? payload.status() : "EN_MONTAJE",
                        instructions
                ));

        ticket.setProductionName(payload.productionName() != null ? payload.productionName() : ticket.getProductionName());
        ticket.setScheduledAt(scheduledAt);
        ticket.setLocation(payload.location() != null ? payload.location() : ticket.getLocation());
        ticket.setStatus(payload.status() != null ? payload.status() : ticket.getStatus());
        ticket.setInstructions(instructions);

        CrewTicket saved = crewTicketRepository.save(ticket);
        log.info("[CREW TICKET GUARDADO] id={}, productionId={}, eventId={}, status='{}', location='{}'",
                saved.getId(), saved.getProductionId(), saved.getEventId(), saved.getStatus(), saved.getLocation());

        NotificationDeliveryAudit audit = new NotificationDeliveryAudit(
                payload.productionId(),
                actualEventId,
                "CREW_TICKET",
                "crew-operations@eventomax.local",
                "PERSISTED",
                "Ticket persistido exitosamente en base de datos. ID: " + saved.getId()
        );
        auditRepository.save(audit);
    }

    private LocalDateTime parseScheduledAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e1) {
            try {
                return OffsetDateTime.parse(raw).toLocalDateTime();
            } catch (Exception e2) {
                try {
                    return Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDateTime();
                } catch (Exception e3) {
                    return LocalDateTime.now();
                }
            }
        }
    }
}

