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
    void rendersBoardingPassTemplateWithDeepLinkToCrewTicketForEnMontaje() throws Exception {
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
                .isEqualTo("EventoMax · Montaje en curso (#12) — Cumbre Cloud Native");

        ExtractedParts parts = extractParts(mime.getContent());
        assertThat(parts.html)
                .contains("cid:eventomax-logo")
                .contains("ORDEN #0012")
                .contains("En Montaje")
                .doesNotContain(">EN_MONTAJE<")
                .contains("border-top:2px dashed #CBD5E1")
                .contains("25 Nov 2027 · 20:00")
                .doesNotContain("2027-11-25T20:00")
                .contains("Ticket Operativo de Cuadrilla")
                .contains("Ver Ticket de Cuadrilla en EventoMax →")
                .contains("href=\"http://localhost:4200/productions?productionId=12&amp;action=view-ticket\"")
                .contains("Trace: ee530d97-91dc-4bb1-ab5f-3acfb37a9e9a · Event: 68b64121-af85-4d91-8687-6ca49dbf2c26");

        assertThat(parts.inlineContentIds).contains("<eventomax-logo>");
    }

    @Test
    void rendersDirectDownloadDeepLinkAndAttachesClosurePdfWhenStatusIsCerrado() throws Exception {
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
        mime.saveChanges();

        assertThat(mime.getSubject())
                .isEqualTo("EventoMax · Producción cerrada · Acta PDF disponible (#145) — Festival Innovación Duoc");

        ExtractedParts parts = extractParts(mime.getContent());
        assertThat(parts.html)
                .contains("ORDEN #0145")
                .contains("Cerrado")
                .contains("10 Dic 2027 · 18:30")
                .contains("Acta de Cierre Empresarial en formato PDF")
                .contains("Descargar Acta PDF en EventoMax →")
                .contains("href=\"http://localhost:4200/productions?productionId=145&amp;action=download-pdf\"");

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

        assertThat(parts.html)
                .contains("Cancelado")
                .contains("Gala &lt;VIP&gt; &amp; Co")
                .contains("Salón &quot;A&quot; &lt;Norte&gt;")
                .contains("Por definir")
                .contains("href=\"http://localhost:4200/productions?productionId=7\"")
                .doesNotContain("<VIP>");
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
