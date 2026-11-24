package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import cl.duoc.eventomax.notify.closure.ClosurePdf;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import java.util.UUID;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpEmailSenderTest {

    private CapturingJavaMailSender mailSender;
    private NotificationDeliveryAuditRepository auditRepository;
    private SmtpEmailSender smtpEmailSender;

    @BeforeEach
    void setUp() {
        mailSender = new CapturingJavaMailSender();
        auditRepository = Mockito.mock(NotificationDeliveryAuditRepository.class);
        smtpEmailSender = new SmtpEmailSender(
                mailSender,
                auditRepository,
                "notify@example.invalid",
                "EventoMax Notificaciones"
        );
        ReflectionTestUtils.setField(smtpEmailSender, "portalUrl", "http://localhost:4200/productions");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void rendersExecutiveMontageWithoutUnverifiedTicketOrCta() throws Exception {
        MDC.put("traceId", "ee530d97-91dc-4bb1-ab5f-3acfb37a9e9a");
        MDC.put("correlationId", "68b64121-af85-4d91-8687-6ca49dbf2c26");

        EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                12L,
                "org-duoc-demo-38057",
                "organizer@example.invalid",
                "Cumbre Cloud Native",
                "EN_MONTAJE",
                "2027-11-25T20:00",
                "Centro Parque · Gran Salón"
        );

        smtpEmailSender.send(payload);

        assertThat(mailSender.sentMessages).hasSize(1);
        MimeMessage mime = mailSender.sentMessages.getFirst();
        mime.saveChanges();

        assertThat(mime.getSubject())
                .isEqualTo("EventoMax · Montaje iniciado · Producción #12");

        ExtractedParts parts = extractParts(mime.getContent());
        assertThat(parts.html).contains("cid:eventomax-logo", "Producción #12", "El montaje está en curso", "zona no informada")
                .doesNotContain("Ticket Operativo", "view-ticket", "href=", "Trace:", "org-duoc-demo-38057");
        assertThat(parts.plain).contains("El montaje está en curso", "acceso habitual")
                .doesNotContain("http", "ee530d97", "org-duoc");
        assertThat(mime.isMimeType("multipart/related")).isTrue();
        assertThat(mime.getHeader("X-EventoMax-Trace-Id", null)).isEqualTo("ee530d97-91dc-4bb1-ab5f-3acfb37a9e9a");

        assertThat(parts.inlineContentIds).contains("<eventomax-logo>");
    }

    @Test
    void rendersVerifiedClosureWithoutUnapprovedPortalLink() throws Exception {
        MDC.put("traceId", "trace-close-01");
        MDC.put("correlationId", "evt-close-01");

        UUID documentId = UUID.randomUUID();
        var out = new java.io.ByteArrayOutputStream();
        var document = new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(document, out);
        document.open();
        document.add(new com.lowagie.text.Paragraph("Test closure"));
        document.close();
        byte[] samplePdf = ClosurePdf.identify(out.toByteArray(), 145L, documentId);

        EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                145L,
                "org-duoc-01",
                "organizer@example.invalid",
                "Festival Innovación Duoc",
                "CERRADO",
                "2027-12-10T18:30:00",
                "Espacio Riesco"
        );

        smtpEmailSender.sendClosure(new MessageEnvelope<>("SendProductionStatusEmail", "email-fixture", "2026-11-20T20:00:00Z",
                "trace-close-01", "corr-close-01", payload), UUID.randomUUID(), documentId, samplePdf, ClosurePdf.sha256(samplePdf));

        MimeMessage mime = mailSender.sentMessages.getFirst();
        // JavaMailSenderImpl preserves this ID around saveChanges(); this capturing stub does not send.
        assertThat(mime.getHeader("Message-ID", null)).startsWith("<closure-").endsWith("@example.invalid>");
        mime.saveChanges();

        assertThat(mime.getSubject())
                .isEqualTo("EventoMax · Producción #145 cerrada");

        ExtractedParts parts = extractParts(mime.getContent());
        assertThat(parts.html).contains("Producción #145", "Tu producción ha sido cerrada", "PDF adjunto")
                .doesNotContain("href=", "firmable", "org-duoc-01", "trace-close-01");
        assertThat(parts.plain).contains("PDF adjunto").doesNotContain("http");
        assertThat(mime.isMimeType("multipart/mixed")).isTrue();
        assertThat(parts.pdf).isEqualTo(samplePdf);
        assertThat(mime.getHeader("X-EventoMax-Document-SHA256", null)).isEqualTo(ClosurePdf.sha256(samplePdf));

        assertThat(parts.attachmentFileNames).contains("Acta-Cierre-Produccion-145.pdf");
    }

    @Test
    void escapesHtmlSpecialCharactersAndHandlesMissingDateSafely() throws Exception {
        EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                7L,
                "org & <partners>",
                "organizer@example.invalid",
                " Gala <VIP> & Co ",
                "CANCELADO",
                null,
                "Salón \"A\" <Norte>"
        );

        smtpEmailSender.send(payload);

        MimeMessage mime = mailSender.sentMessages.getFirst();
        mime.saveChanges();
        ExtractedParts parts = extractParts(mime.getContent());

        assertThat(parts.html).contains("Actualizado", "Gala &lt;VIP&gt; &amp; Co", "Salón &quot;A&quot; &lt;Norte&gt;", "Fecha no disponible")
                .doesNotContain("<VIP>", "href=", "org &", "cargos", "despachos");
    }

    private ExtractedParts extractParts(Object content) throws Exception {
        ExtractedParts out = new ExtractedParts();
        collectParts(content, out);
        return out;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingAuthorizedRecipientIsBlockedBeforeSmtp(String recipient) {
        var payload = new EmailProductionStatusPayload(8L, "org-fixture", recipient,
                "Evento de prueba", "CONFIRMADO", "2027-01-01T12:00", "Recinto");
        assertThatThrownBy(() -> smtpEmailSender.send(payload)).isInstanceOf(MissingRecipientContactException.class);
        assertThat(mailSender.sentMessages).isEmpty();
        var audit = ArgumentCaptor.forClass(NotificationDeliveryAudit.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getStatus()).isEqualTo("BLOCKED_NO_CONTACT");
        assertThat(audit.getValue().getRecipient()).isNull();
    }

    @Test
    void legacyDefaultRecipientPropertyCannotRestoreUnsafeFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(SmtpEmailSender.class)
                .withBean(JavaMailSender.class, () -> mailSender)
                .withBean(NotificationDeliveryAuditRepository.class, () -> auditRepository)
                .withPropertyValues("eventomax.notify.mail.default-recipient=wrong@example.invalid",
                        "NOTIFY_DEFAULT_RECIPIENT=wrong@example.invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var payload = new EmailProductionStatusPayload(8L, "org-fixture", null,
                            "Evento de prueba", "CONFIRMADO", "2027-01-01T12:00", "Recinto");
                    assertThatThrownBy(() -> context.getBean(SmtpEmailSender.class).send(payload))
                            .isInstanceOf(MissingRecipientContactException.class);
                    assertThat(mailSender.sentMessages).isEmpty();
                });
    }

    @Test
    void directClosureSendWithoutDurableAttachmentIsBlocked() {
        var payload = new EmailProductionStatusPayload(8L,"org-fixture","organizer@example.invalid",
                "Isolated event","CERRADO","2026-12-01T18:00:00","Fixture venue");
        assertThatThrownBy(() -> smtpEmailSender.send(payload)).isInstanceOf(IllegalStateException.class)
                .hasMessage("DURABLE_CLOSURE_REQUIRED");
        assertThat(mailSender.sentMessages).isEmpty();
    }

    private void collectParts(Object content, ExtractedParts out) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                String[] cids = part.getHeader("Content-ID");
                if (cids != null) {
                    for (String cid : cids) {
                        out.inlineContentIds.add(cid);
                    }
                }
                if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) && part.getFileName() != null) {
                    out.attachmentFileNames.add(part.getFileName());
                    if (part.isMimeType("application/pdf")) out.pdf = part.getInputStream().readAllBytes();
                }
                String contentType = part.getContentType().toLowerCase();
                Object partContent = part.getContent();
                if (partContent instanceof Multipart nested) {
                    collectParts(nested, out);
                } else if (contentType.contains("text/html") && partContent instanceof String s) {
                    out.html = s;
                } else if (contentType.contains("text/plain") && partContent instanceof String s) {
                    out.plain = s;
                }
            }
        }
    }

    private static class ExtractedParts {
        String html = "";
        String plain = "";
        byte[] pdf;
        final List<String> inlineContentIds = new ArrayList<>();
        final List<String> attachmentFileNames = new ArrayList<>();
    }

    private static class CapturingJavaMailSender extends JavaMailSenderImpl {
        final List<MimeMessage> sentMessages = new ArrayList<>();

        @Override
        public void send(MimeMessage mimeMessage) {
            sentMessages.add(mimeMessage);
        }
    }
}
