package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementación de {@link EmailSender} que simula el envío de emails mediante logging
 * para el modo académico de demostración ({@code EVENTOMAX_NOTIFY_EMAIL_PROVIDER=logging}).
 * Registra explícitamente la operación como simulada, sin efectuar despacho SMTP real.
 */
@Component
@ConditionalOnProperty(name = "eventomax.notify.email.provider", havingValue = "logging")
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final NotificationDeliveryAuditRepository auditRepository;

    @org.springframework.beans.factory.annotation.Value("${eventomax.notify.mail.default-recipient:}")
    private String defaultRecipientEmail;

    public LoggingEmailSender() {
        this(null);
    }

    @Autowired
    public LoggingEmailSender(@Autowired(required = false) NotificationDeliveryAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public void send(EmailProductionStatusPayload payload) {
        String effectiveRecipient = payload.recipientEmail();
        if ((effectiveRecipient == null || effectiveRecipient.isBlank()) && defaultRecipientEmail != null && !defaultRecipientEmail.isBlank()) {
            effectiveRecipient = defaultRecipientEmail.trim();
        }
        log.info("[EMAIL SIMULADO - MODO ACADEMICO] Operación registrada en simulación local (sin envío SMTP real): "
                        + "productionId={}, status={}, organizerId={}, recipientEmail={}",
                payload.productionId(),
                payload.status(),
                payload.organizerId(),
                effectiveRecipient);

        if (auditRepository != null && payload.productionId() != null) {
            try {
                String eventId = MDC.get("correlationId");
                if (eventId == null || eventId.isBlank()) {
                    eventId = String.valueOf(payload.productionId());
                }
                auditRepository.save(new NotificationDeliveryAudit(
                        payload.productionId(),
                        eventId,
                        "EMAIL",
                        effectiveRecipient,
                        "SIMULATED_LOGGING",
                        "Operación simulada en modo académico (logging); no se efectuó despacho SMTP real."
                ));
            } catch (Exception e) {
                log.debug("No se pudo persistir auditoría de simulación de email para productionId={}", payload.productionId(), e);
            }
        }
    }
}
