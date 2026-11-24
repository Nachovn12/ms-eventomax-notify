package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import cl.duoc.eventomax.notify.closure.ClosureEmailTransport;
import cl.duoc.eventomax.notify.closure.ClosurePdf;
import cl.duoc.eventomax.notify.closure.ClosurePreflightException;
import cl.duoc.eventomax.notify.closure.RecipientCheck;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import java.util.UUID;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import cl.duoc.eventomax.notify.email.presentation.*;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "eventomax.notify.email.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender, ClosureEmailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    private static final String LOGO_CONTENT_ID = "eventomax-logo";
    private static final String LOGO_RESOURCE_PATH = "branding/eventomax-logo-email.png";
    private final JavaMailSender mailSender;
    private final NotificationDeliveryAuditRepository auditRepository;
    private final String fromEmail;
    private final String fromName;

    @Value("${eventomax.notify.mail.portal-url:}")
    private String portalUrl = "";
    @Value("${eventomax.notify.mail.portal-allowed-hosts:}")
    private String portalAllowedHosts = "";
    @Value("${eventomax.notify.mail.portal-e2e-approved:false}")
    private boolean portalE2eApproved;
    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();
    private final PortalLinkPolicy portalLinks = new PortalLinkPolicy();
    @Value("${eventomax.notify.closure.max-pdf-bytes:10000000}")
    private int closureMaxPdfBytes = 10_000_000;

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

        if ("CERRADO".equals(normalizeStatus(payload.status()))) {
            throw new IllegalStateException("DURABLE_CLOSURE_REQUIRED");
        }
        try {
            validateHeaders(fromEmail, fromName, recipient, traceId, eventId);
            var logoResource = logoResource();
            var rendered = presentation(payload, false, logoResource.exists());
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_RELATED, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(recipient.trim());
            helper.setSubject(rendered.subject());

            // Cabeceras RFC 2822 para trazabilidad
            message.addHeader("X-EventoMax-Production-Id", String.valueOf(payload.productionId()));
            if (traceId != null && !traceId.isBlank()) {
                message.addHeader("X-EventoMax-Trace-Id", traceId);
            }
            if (eventId != null && !eventId.isBlank()) {
                message.addHeader("X-EventoMax-Event-Id", eventId);
            }

            helper.setText(rendered.plain(), rendered.html());
            if (logoResource.exists()) helper.addInline(LOGO_CONTENT_ID, logoResource, "image/png");

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

    @Override
    public void sendClosure(MessageEnvelope<EmailProductionStatusPayload> envelope, UUID deliveryId,
                            UUID documentId, byte[] pdf, String sha256) {
        var p = envelope.payload();
        if (!"CERRADO".equals(normalizeStatus(p.status())) || !RecipientCheck.valid(p.recipientEmail())) {
            throw new ClosurePreflightException("NO_AUTHORIZED_CLOSURE_CONTACT");
        }
        try { ClosurePdf.verify(pdf, p.productionId(), documentId, sha256, pdf.length, closureMaxPdfBytes); }
        catch (RuntimeException ex) { throw new ClosurePreflightException("INVALID_CLOSURE_ATTACHMENT"); }
        MimeMessage message = mailSender.createMimeMessage();
        try {
            validateHeaders(fromEmail, fromName, p.recipientEmail(), envelope.eventId(), envelope.traceId(), envelope.correlationId());
            var logo = logoResource();
            var rendered = presentation(p, true, logo.exists());
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(p.recipientEmail().trim());
            helper.setSubject(rendered.subject());
            helper.setText(rendered.plain(), rendered.html());
            if (logo.exists()) helper.addInline(LOGO_CONTENT_ID, logo, "image/png");
            helper.addAttachment("Acta-Cierre-Produccion-" + p.productionId() + ".pdf",
                    new ByteArrayResource(pdf), "application/pdf");
            message.setHeader("X-EventoMax-Production-Id", p.productionId().toString());
            message.setHeader("X-EventoMax-Event-Id", envelope.eventId());
            message.setHeader("X-EventoMax-Trace-Id", envelope.traceId());
            message.setHeader("X-EventoMax-Correlation-Id", envelope.correlationId());
            message.setHeader("X-EventoMax-Delivery-Id", deliveryId.toString());
            message.setHeader("X-EventoMax-Document-SHA256", sha256);
            String domain = new jakarta.mail.internet.InternetAddress(fromEmail, true).getAddress().split("@", 2)[1];
            message.setHeader("Message-ID", "<closure-" + deliveryId + "@" + domain + ">");
        } catch (MessagingException | UnsupportedEncodingException | IllegalArgumentException ex) {
            throw new ClosurePreflightException("CLOSURE_MIME_INVALID");
        }
        // No DB writes here: acceptance followed by audit failure is UNKNOWN, never an automatic resend.
        mailSender.send(message);
        log.info("CLOSURE_SMTP_RETURNED productionId={}, deliveryId={}", p.productionId(), deliveryId);
    }

    private EmailTemplateRenderer.RenderedEmail presentation(EmailProductionStatusPayload payload, boolean verifiedPdf, boolean logo) {
        return renderer.render(EmailPresentationModel.from(payload, verifiedPdf, logo,
                portalLinks.link(portalUrl, portalAllowedHosts, portalE2eApproved, payload.productionId())));
    }

    protected ClassPathResource logoResource() { return new ClassPathResource(LOGO_RESOURCE_PATH); }

    private static void validateHeaders(String... values) throws MessagingException {
        for (String value : values) {
            if (value != null && value.codePoints().anyMatch(Character::isISOControl)) {
                throw new MessagingException("INVALID_EMAIL_HEADER");
            }
        }
    }

    private String normalizeStatus(String rawStatus) {
        return rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
    }
}
