package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

@Component
@Primary
@ConditionalOnProperty(name = "eventomax.notify.email.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final NotificationDeliveryAuditRepository auditRepository;
    private final String fromEmail;
    private final String fromName;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            NotificationDeliveryAuditRepository auditRepository,
            @Value("${eventomax.notify.mail.from:no-reply@eventomax.cl}") String fromEmail,
            @Value("${eventomax.notify.mail.from-name:EventoMax Notificaciones}") String fromName) {
        this.mailSender = mailSender;
        this.auditRepository = auditRepository;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void send(EmailProductionStatusPayload payload) {
        String eventId = MDC.get("correlationId");
        if (eventId == null || eventId.isBlank()) {
            eventId = String.valueOf(payload.productionId());
        }
        String traceId = MDC.get("traceId");

        // Regla 7 & 8: No convertir automáticamente organizerId a correo ni inventar destinatarios.
        String recipient = payload.recipientEmail();
        if (recipient == null || recipient.isBlank()) {
            log.warn("[EMAIL_DELIVERY_BLOCKED] Producción #{}: No existe contacto autorizado registrado para organizerId='{}'. Registro observable generado.",
                    payload.productionId(), payload.organizerId());

            try {
                auditRepository.save(new NotificationDeliveryAudit(
                        payload.productionId(),
                        eventId,
                        "EMAIL",
                        null,
                        "BLOCKED_NO_CONTACT",
                        "No existe contacto autorizado registrado para organizerId=" + payload.organizerId()
                ));
            } catch (Exception e) {
                log.error("Error guardando auditoría de fallo de contacto", e);
            }

            throw new MissingRecipientContactException(
                    "Fallo de entrega: No existe contacto registrado para el organizador " + payload.organizerId());
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail, fromName);
            helper.setTo(recipient.trim());
            helper.setSubject(String.format("[EventoMax] Actualización Producción #%d: %s - %s",
                    payload.productionId(), payload.status(), payload.productionName()));

            // Cabeceras RFC 2822 para trazabilidad
            message.addHeader("X-EventoMax-Production-Id", String.valueOf(payload.productionId()));
            if (traceId != null && !traceId.isBlank()) {
                message.addHeader("X-EventoMax-Trace-Id", traceId);
            }
            if (eventId != null && !eventId.isBlank()) {
                message.addHeader("X-EventoMax-Event-Id", eventId);
            }

            String htmlBody = buildHtmlContent(payload, traceId, eventId);
            String textBody = buildTextContent(payload, traceId, eventId);
            helper.setText(textBody, htmlBody);

            // Despacho SMTP
            mailSender.send(message);

            log.info("[SMTP_ACCEPTED] Correo despachado y aceptado por el servidor SMTP: recipient='{}', productionId={}, status='{}', traceId='{}'",
                    recipient, payload.productionId(), payload.status(), traceId);

            auditRepository.save(new NotificationDeliveryAudit(
                    payload.productionId(),
                    eventId,
                    "EMAIL",
                    recipient.trim(),
                    "ACCEPTED_BY_SMTP",
                    "Aceptado por servidor SMTP para entrega a " + recipient.trim()
            ));

        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("[SMTP_ERROR] Error al construir el mensaje MIME para productionId={}", payload.productionId(), e);
            auditRepository.save(new NotificationDeliveryAudit(
                    payload.productionId(),
                    eventId,
                    "EMAIL",
                    recipient.trim(),
                    "MIME_CONSTRUCTION_FAILED",
                    e.getMessage()
            ));
            throw new IllegalStateException("Error al construir el correo MIME", e);

        } catch (MailException e) {
            log.error("[SMTP_DELIVERY_FAILED] Fallo de transporte SMTP para productionId={}: {}", payload.productionId(), e.getMessage());
            auditRepository.save(new NotificationDeliveryAudit(
                    payload.productionId(),
                    eventId,
                    "EMAIL",
                    recipient.trim(),
                    "SMTP_TRANSPORT_FAILED",
                    e.getMessage()
            ));
            throw e;
        }
    }

    private String buildTextContent(EmailProductionStatusPayload p, String traceId, String eventId) {
        return String.format("""
                EVENTOMAX - NOTIFICACIÓN DE ESTADO DE PRODUCCIÓN
                ================================================
                ID Producción    : %d
                Nombre Evento    : %s
                Organizador ID   : %s
                Nuevo Estado     : %s
                Fecha Programada : %s
                Ubicación        : %s
                ------------------------------------------------
                Trazabilidad:
                Trace ID         : %s
                Event ID         : %s
                ------------------------------------------------
                Este es un mensaje automático del sistema EventoMax Cloud Native.
                """,
                p.productionId(),
                p.productionName(),
                p.organizerId(),
                p.status(),
                p.scheduledAt() != null ? p.scheduledAt() : "Por definir",
                p.location(),
                traceId != null ? traceId : "N/A",
                eventId != null ? eventId : "N/A"
        );
    }

    private String buildHtmlContent(EmailProductionStatusPayload p, String traceId, String eventId) {
        return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Notificación EventoMax</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #333; line-height: 1.5; }
                        .container { max-width: 600px; margin: 20px auto; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; }
                        .header { background: #1e293b; color: #fff; padding: 20px; text-align: center; }
                        .content { padding: 24px; }
                        .badge { display: inline-block; padding: 4px 10px; border-radius: 4px; font-weight: bold; background: #dbeafe; color: #1e40af; }
                        .details { margin: 20px 0; border-collapse: collapse; width: 100%%; }
                        .details td { padding: 8px 12px; border-bottom: 1px solid #f1f5f9; }
                        .details td.label { font-weight: bold; color: #64748b; width: 35%%; }
                        .footer { background: #f8fafc; padding: 16px; font-size: 12px; color: #94a3b8; text-align: center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h2>EventoMax Cloud Native</h2>
                        </div>
                        <div class="content">
                            <h3>Actualización de Estado de Producción</h3>
                            <p>Se ha registrado un cambio en el ciclo de vida de la producción solicitada:</p>
                            <table class="details">
                                <tr><td class="label">ID Producción:</td><td><strong>#%d</strong></td></tr>
                                <tr><td class="label">Nombre Evento:</td><td>%s</td></tr>
                                <tr><td class="label">Organizador:</td><td>%s</td></tr>
                                <tr><td class="label">Estado Actual:</td><td><span class="badge">%s</span></td></tr>
                                <tr><td class="label">Fecha Programada:</td><td>%s</td></tr>
                                <tr><td class="label">Recinto / Ubicación:</td><td>%s</td></tr>
                                <tr><td class="label">Trace ID:</td><td><code>%s</code></td></tr>
                                <tr><td class="label">Event ID:</td><td><code>%s</code></td></tr>
                            </table>
                        </div>
                        <div class="footer">
                            Mensaje transaccional generado automáticamente por ms-eventomax-notify.
                        </div>
                    </div>
                </body>
                </html>
                """,
                p.productionId(),
                escapeHtml(p.productionName()),
                escapeHtml(p.organizerId()),
                escapeHtml(p.status()),
                escapeHtml(p.scheduledAt() != null ? p.scheduledAt() : "Por definir"),
                escapeHtml(p.location()),
                escapeHtml(traceId != null ? traceId : "N/A"),
                escapeHtml(eventId != null ? eventId : "N/A")
        );
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

