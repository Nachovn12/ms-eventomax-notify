package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "eventomax.notify.email.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    private static final String LOGO_CONTENT_ID = "eventomax-logo";
    private static final String LOGO_RESOURCE_PATH = "branding/eventomax-logo-email.png";
    private static final String[] SHORT_MONTHS_ES = {
            "Ene", "Feb", "Mar", "Abr", "May", "Jun",
            "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"
    };

    private final JavaMailSender mailSender;
    private final NotificationDeliveryAuditRepository auditRepository;
    private final String fromEmail;
    private final String fromName;

    @Value("${eventomax.notify.mail.default-recipient:}")
    private String defaultRecipientEmail;

    @Value("${eventomax.notify.mail.portal-url:http://localhost:4200/productions}")
    private String portalUrl = "http://localhost:4200/productions";

    @Autowired(required = false)
    private DocumentStorageService documentStorageService;

    @Value("${eventomax.notify.quote.output-dir:./data/quotes}")
    private String quoteOutputDir = "./data/quotes";

    @Value("${eventomax.notify.mail.closure-pdf-wait-max-ms:2500}")
    private long closurePdfWaitMaxMs = 2500L;

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
        if ((recipient == null || recipient.isBlank()) && defaultRecipientEmail != null && !defaultRecipientEmail.isBlank()) {
            recipient = defaultRecipientEmail.trim();
        }
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
            String normalizedStatus = normalizeStatus(payload.status());
            StatusPresentation meta = resolveStatusPresentation(payload.status());
            String actionUrl = buildActionUrl(payload.productionId(), normalizedStatus);
            byte[] closurePdfBytes = "CERRADO".equals(normalizedStatus)
                    ? resolveClosurePdfWithRetry(payload.productionId())
                    : null;
            if (closurePdfBytes != null) {
                meta = new StatusPresentation(
                        meta.label(),
                        meta.badgeBg(),
                        meta.badgeBorder(),
                        meta.badgeText(),
                        meta.subjectPrefix(),
                        meta.headline(),
                        "Nuestro motor documental generó el Acta de Cierre Empresarial en formato PDF firmable (adjunta en este correo y lista para descarga directa en EventoMax).",
                        meta.ctaLabel()
                );
            }
            String cleanProductionName = safeOrFallback(payload.productionName(), "Producción EventoMax");

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail, fromName);
            helper.setTo(recipient.trim());
            helper.setSubject(String.format("EventoMax · %s (#%d) — %s",
                    meta.subjectPrefix(), payload.productionId(), cleanProductionName));

            // Cabeceras RFC 2822 para trazabilidad
            message.addHeader("X-EventoMax-Production-Id", String.valueOf(payload.productionId()));
            if (traceId != null && !traceId.isBlank()) {
                message.addHeader("X-EventoMax-Trace-Id", traceId);
            }
            if (eventId != null && !eventId.isBlank()) {
                message.addHeader("X-EventoMax-Event-Id", eventId);
            }

            String htmlBody = buildHtmlContent(payload, meta, actionUrl, traceId, eventId);
            String textBody = buildTextContent(payload, meta, actionUrl, traceId, eventId);
            helper.setText(textBody, htmlBody);

            ClassPathResource logoResource = new ClassPathResource(LOGO_RESOURCE_PATH);
            if (logoResource.exists()) {
                helper.addInline(LOGO_CONTENT_ID, logoResource, "image/png");
            }

            if (closurePdfBytes != null) {
                String attachmentName = "Acta-Cierre-Produccion-" + payload.productionId() + ".pdf";
                helper.addAttachment(attachmentName, new ByteArrayResource(closurePdfBytes), "application/pdf");
                log.info("[SMTP_ATTACHMENT_ADDED] Adjuntada {} ({} bytes) al correo de cierre para productionId={}",
                        attachmentName, closurePdfBytes.length, payload.productionId());
            }

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

    private String buildTextContent(EmailProductionStatusPayload p, StatusPresentation meta, String actionUrl, String traceId, String eventId) {
        return String.format("""
                EVENTOMAX · OPERACIONES DE EVENTOS — ORDEN #%04d
                ================================================
                Estado Operativo : %s (%s)
                Evento           : %s
                Fecha y Hora     : %s
                Organizador      : %s
                Ubicación        : %s
                ------------------------------------------------
                %s
                %s: %s
                ------------------------------------------------
                Trace: %s · Event: %s
                """,
                p.productionId(),
                meta.label(),
                meta.headline(),
                safeOrFallback(p.productionName(), "Producción EventoMax"),
                formatScheduledAt(p.scheduledAt()),
                safeOrFallback(p.organizerId(), "No especificado"),
                safeOrFallback(p.location(), "Por confirmar"),
                meta.calloutBody(),
                meta.ctaLabel(),
                actionUrl,
                safeOrFallback(traceId, "N/A"),
                safeOrFallback(eventId, "N/A")
        );
    }

    private String buildHtmlContent(EmailProductionStatusPayload p, StatusPresentation meta, String actionUrl, String traceId, String eventId) {
        String orderFolio = String.format("ORDEN #%04d", p.productionId());
        String formattedDate = formatScheduledAt(p.scheduledAt());

        return String.format("""
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>EventoMax · %s</title>
                </head>
                <body style="margin:0; padding:0; background-color:#F1F5F9; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; color:#0F172A; -webkit-font-smoothing:antialiased;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#F1F5F9; padding:28px 16px;">
                        <tr>
                            <td align="center">
                                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:600px; background-color:#FFFFFF; border:1px solid #E2E8F0; border-radius:16px; overflow:hidden; box-shadow:0 4px 16px rgba(15,23,42,0.05); text-align:left;">
                                    <tr>
                                        <td style="padding:24px 28px 18px 28px;">
                                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                                                <tr>
                                                    <td style="vertical-align:middle;">
                                                        <img src="cid:%s" alt="EventoMax Operaciones de Eventos" width="156" height="52" style="display:block; border:0; width:156px; height:52px;" />
                                                    </td>
                                                    <td align="right" style="vertical-align:middle; font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace; font-size:12px; font-weight:600; color:#64748B; letter-spacing:0.4px;">
                                                        %s
                                                    </td>
                                                </tr>
                                            </table>
                                            <div style="margin-top:22px;">
                                                <span style="display:inline-block; background-color:%s; color:%s; border:1px solid %s; font-size:12px; font-weight:700; padding:3px 10px; border-radius:6px; line-height:1.4;">
                                                    %s
                                                </span>
                                                <h2 style="margin:10px 0 4px 0; font-size:20px; font-weight:800; color:#0F172A; line-height:1.3;">
                                                    %s
                                                </h2>
                                                <p style="margin:0; font-size:13px; color:#64748B; line-height:1.5;">
                                                    %s
                                                </p>
                                            </div>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="padding:0 28px;">
                                            <div style="border-top:2px dashed #CBD5E1; height:1px; line-height:1px; font-size:1px;">&nbsp;</div>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="padding:20px 28px 24px 28px;">
                                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                                                <tr>
                                                    <td width="50%%" style="padding-bottom:16px; padding-right:12px; vertical-align:top;">
                                                        <div style="font-size:12px; color:#94A3B8; text-transform:uppercase; font-weight:700; letter-spacing:0.4px;">Fecha y Hora</div>
                                                        <div style="font-size:14px; color:#0F172A; font-weight:700; margin-top:3px; line-height:1.4;">%s</div>
                                                    </td>
                                                    <td width="50%%" style="padding-bottom:16px; vertical-align:top;">
                                                        <div style="font-size:12px; color:#94A3B8; text-transform:uppercase; font-weight:700; letter-spacing:0.4px;">Organizador</div>
                                                        <div style="font-size:14px; color:#0F172A; font-weight:600; margin-top:3px; line-height:1.4;">%s</div>
                                                    </td>
                                                </tr>
                                                <tr>
                                                    <td colspan="2" style="padding-bottom:18px; vertical-align:top;">
                                                        <div style="font-size:12px; color:#94A3B8; text-transform:uppercase; font-weight:700; letter-spacing:0.4px;">Ubicación / Recinto</div>
                                                        <div style="font-size:14px; color:#0F172A; font-weight:600; margin-top:3px; line-height:1.4;">%s</div>
                                                    </td>
                                                </tr>
                                            </table>
                                            <div style="padding:12px 14px; background-color:#F8FAFC; border-radius:8px; font-size:13px; color:#334155; border:1px solid #E2E8F0; line-height:1.55;">
                                                %s
                                            </div>
                                            <div style="margin-top:18px;">
                                                <a href="%s" style="display:inline-block; color:#024FFC; font-size:13px; font-weight:700; text-decoration:none;">
                                                    %s
                                                </a>
                                            </div>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="background-color:#F8FAFC; border-top:1px solid #E2E8F0; padding:14px 28px; font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace; font-size:12px; color:#94A3B8; line-height:1.5;">
                                            Trace: %s · Event: %s
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """,
                escapeHtml(orderFolio),
                LOGO_CONTENT_ID,
                escapeHtml(orderFolio),
                meta.badgeBg(),
                meta.badgeText(),
                meta.badgeBorder(),
                escapeHtml(meta.label()),
                escapeHtml(safeOrFallback(p.productionName(), "Producción EventoMax")),
                escapeHtml(meta.headline()),
                escapeHtml(formattedDate),
                escapeHtml(safeOrFallback(p.organizerId(), "No especificado")),
                escapeHtml(safeOrFallback(p.location(), "Por confirmar")),
                escapeHtml(meta.calloutBody()),
                escapeHtml(actionUrl),
                escapeHtml(meta.ctaLabel()),
                escapeHtml(safeOrFallback(traceId, "N/A")),
                escapeHtml(safeOrFallback(eventId, "N/A"))
        );
    }

    private String normalizeStatus(String rawStatus) {
        return rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
    }

    private String buildActionUrl(Long productionId, String normalizedStatus) {
        String base = safeOrFallback(portalUrl, "http://localhost:4200/productions");
        String separator = base.contains("?") ? "&" : "?";
        StringBuilder url = new StringBuilder(base)
                .append(separator)
                .append("productionId=")
                .append(productionId);
        if ("CERRADO".equals(normalizedStatus)) {
            url.append("&action=download-pdf");
        } else if ("EN_MONTAJE".equals(normalizedStatus)) {
            url.append("&action=view-ticket");
        }
        return url.toString();
    }

    private byte[] resolveClosurePdfWithRetry(Long productionId) {
        String filename = "quote-production-" + productionId + "-latest.pdf";
        long deadline = System.currentTimeMillis() + Math.max(0L, closurePdfWaitMaxMs);

        while (true) {
            byte[] found = tryReadClosurePdfOnce(filename);
            if (found != null && found.length > 0) {
                return found;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.debug("Acta PDF aún no disponible al despachar correo para productionId={}", productionId);
        return null;
    }

    private byte[] tryReadClosurePdfOnce(String filename) {
        if (documentStorageService != null) {
            try {
                if (documentStorageService.documentExists("quotes", filename)) {
                    return documentStorageService.retrieveDocument("quotes", filename);
                }
            } catch (Exception e) {
                log.debug("Lectura de DocumentStorageService omitida para {}: {}", filename, e.getMessage());
            }
        }
        if (quoteOutputDir != null && !quoteOutputDir.isBlank()) {
            try {
                Path localFile = Path.of(quoteOutputDir).toAbsolutePath().normalize().resolve(filename);
                if (Files.exists(localFile)) {
                    return Files.readAllBytes(localFile);
                }
            } catch (Exception e) {
                log.debug("Lectura local de Acta PDF omitida para {}: {}", filename, e.getMessage());
            }
        }
        return null;
    }

    private StatusPresentation resolveStatusPresentation(String rawStatus) {
        String normalized = normalizeStatus(rawStatus);
        return switch (normalized) {
            case "CONFIRMADO" -> new StatusPresentation(
                    "Confirmado",
                    "#EFF6FF",
                    "#BFDBFE",
                    "#1D4ED8",
                    "Producción confirmada",
                    "Producción confirmada y agendada",
                    "La planificación comercial y la reserva de equipamiento técnico han sido validadas. Cuando inicie el despliegue en recinto se habilitará el Ticket Operativo de Cuadrilla.",
                    "Revisar producción en EventoMax →"
            );
            case "EN_MONTAJE" -> new StatusPresentation(
                    "En Montaje",
                    "#FFFBEB",
                    "#FDE68A",
                    "#B45309",
                    "Montaje en curso",
                    "Despliegue técnico y montaje en recinto",
                    "El Ticket Operativo de Cuadrilla ya se encuentra disponible para consulta en tiempo real dentro del panel de Gestión Operativa de la producción.",
                    "Ver Ticket de Cuadrilla en EventoMax →"
            );
            case "CERRADO" -> new StatusPresentation(
                    "Cerrado",
                    "#ECFDF5",
                    "#A7F3D0",
                    "#047857",
                    "Producción cerrada · Acta PDF disponible",
                    "Producción finalizada exitosamente",
                    "Nuestro motor documental generó el Acta de Cierre Empresarial en formato PDF firmable. Puedes descargarla directamente desde el detalle de la producción.",
                    "Descargar Acta PDF en EventoMax →"
            );
            case "CANCELADO" -> new StatusPresentation(
                    "Cancelado",
                    "#FEF2F2",
                    "#FECACA",
                    "#B91C1C",
                    "Producción cancelada",
                    "Producción anulada en el sistema",
                    "No se ejecutarán despachos de cuadrilla ni cargos adicionales sobre esta orden. El historial permanece disponible en el módulo de Auditoría.",
                    "Consultar trazabilidad en EventoMax →"
            );
            default -> {
                String humanized = humanizeStatus(rawStatus);
                yield new StatusPresentation(
                        humanized,
                        "#EFF6FF",
                        "#BFDBFE",
                        "#1D4ED8",
                        "Actualización de producción",
                        "Actualización operativa registrada en EventoMax",
                        "Se ha registrado una transición de estado en la orden operativa del evento dentro de la plataforma.",
                        "Abrir producción en EventoMax →"
                );
            }
        };
    }

    private String formatScheduledAt(String scheduledAt) {
        if (scheduledAt == null || scheduledAt.isBlank()) {
            return "Por definir";
        }
        String trimmed = scheduledAt.trim();
        try {
            LocalDateTime ldt = LocalDateTime.parse(trimmed);
            return formatLocalDateTime(ldt);
        } catch (Exception ignored) {
        }
        try {
            OffsetDateTime odt = OffsetDateTime.parse(trimmed);
            return formatLocalDateTime(odt.toLocalDateTime());
        } catch (Exception ignored) {
        }
        try {
            Instant instant = Instant.parse(trimmed);
            return formatLocalDateTime(LocalDateTime.ofInstant(instant, ZoneOffset.UTC)) + " UTC";
        } catch (Exception ignored) {
        }
        return trimmed;
    }

    private String formatLocalDateTime(LocalDateTime ldt) {
        String month = SHORT_MONTHS_ES[ldt.getMonthValue() - 1];
        return String.format("%02d %s %d · %02d:%02d",
                ldt.getDayOfMonth(),
                month,
                ldt.getYear(),
                ldt.getHour(),
                ldt.getMinute());
    }

    private String humanizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "Actualizado";
        }
        String[] words = rawStatus.trim().replace('_', ' ').toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private String safeOrFallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record StatusPresentation(
            String label,
            String badgeBg,
            String badgeBorder,
            String badgeText,
            String subjectPrefix,
            String headline,
            String calloutBody,
            String ctaLabel
    ) {
    }
}
