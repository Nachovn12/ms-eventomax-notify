package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.closure.*;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.repository.NotificationDeliveryAuditRepository;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Capture only: no sockets, credentials or external services. All exported data are synthetic. */
class ExecutiveEmailMimeTest {
    static final UUID DELIVERY=UUID.fromString("00000000-0000-4000-8000-000000000145");
    static final UUID DOCUMENT=UUID.fromString("00000000-0000-4000-8000-000000000146");
    final Capture capture=new Capture();
    SmtpEmailSender sender(boolean logo) {
        var sender=new SmtpEmailSender(capture,mock(NotificationDeliveryAuditRepository.class),"notify@example.invalid","EventoMax") {
            @Override protected ClassPathResource logoResource() {
                return new ClassPathResource(logo ? "branding/eventomax-logo-email.png" : "branding/missing-fixture.png");
            }
        };
        ReflectionTestUtils.setField(sender,"portalUrl","https://portal.example.invalid/productions");
        ReflectionTestUtils.setField(sender,"portalAllowedHosts","portal.example.invalid");
        // No E2E approval: valid configuration alone never enables the CTA.
        return sender;
    }
    EmailProductionStatusPayload payload(String state) {
        return new EmailProductionStatusPayload(145L,"synthetic-organizer","organizer@example.invalid",
                "Jornada corporativa · Encuentro de equipos",state,"2027-07-15T18:00:00","Centro de eventos · Salón Sur");
    }
    byte[] pdf() throws Exception {
        var out=new java.io.ByteArrayOutputStream();
        var pdf=new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(pdf,out);pdf.open();
        pdf.add(new com.lowagie.text.Paragraph("Synthetic closing record. No operational data."));pdf.close();
        return ClosurePdf.identify(out.toByteArray(),145,DOCUMENT);
    }
    void send(SmtpEmailSender sender,String state,byte[] bytes) {
        if(state.equals("CERRADO")) sender.sendClosure(new MessageEnvelope<>("SendProductionStatusEmail","synthetic-event",
                "2026-11-20T20:00:00Z","synthetic-trace","synthetic-correlation",payload(state)),DELIVERY,DOCUMENT,bytes,ClosurePdf.sha256(bytes));
        else sender.send(payload(state));
    }
    @ParameterizedTest @ValueSource(strings={"CONFIRMADO","EN_MONTAJE","CERRADO"})
    void exportsSanitizedMimeAndHtmlFromActualTransportComposition(String state) throws Exception {
        byte[] bytes=pdf(); send(sender(true),state,bytes);
        var mime=capture.message;
        assertThat(mime.getAllRecipients()).hasSize(1);
        assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("organizer@example.invalid");
        if(state.equals("CERRADO")) {
            assertThat(mime.getHeader("Message-ID",null)).isEqualTo("<closure-"+DELIVERY+"@example.invalid>");
            assertThat(mime.getHeader("X-EventoMax-Document-SHA256",null)).isEqualTo(ClosurePdf.sha256(bytes));
            assertThat(mime.getHeader("X-EventoMax-Trace-Id",null)).isEqualTo("synthetic-trace");
        }
        String id=mime.getMessageID();
        mime.setSentDate(Date.from(Instant.parse("2026-11-20T20:00:00Z")));
        mime.saveChanges();
        // Sanitize generated hostname from ordinary fixture IDs; preserve the durable ID.
        mime.setHeader("Message-ID",id==null ? "<preview-"+state.toLowerCase(Locale.ROOT)+"@example.invalid>" : id);
        var parts=new ArrayList<Part>();collect(mime,parts);
        var html=parts.stream().filter(p->is(p,"text/html")).findFirst().orElseThrow().getContent().toString();
        var plain=parts.stream().filter(p->is(p,"text/plain")).findFirst().orElseThrow().getContent().toString();
        assertThat(parts.stream().filter(p->is(p,"multipart/alternative"))).hasSize(1);
        assertThat(parts.stream().filter(p->header(p,"Content-ID").equals("<eventomax-logo>"))).hasSize(1);
        assertThat(html+plain).doesNotContain("synthetic-organizer","synthetic-trace","href=","https://","localhost");
        var attachments=parts.stream().filter(p->is(p,"application/pdf")).toList();
        assertThat(attachments).hasSize(state.equals("CERRADO") ? 1 : 0);
        if(!attachments.isEmpty()) assertThat(attachments.getFirst().getInputStream().readAllBytes()).isEqualTo(bytes);
        Path output=Path.of("target","notify-2c-preview");Files.createDirectories(output);
        String name=state.toLowerCase(Locale.ROOT);
        try(var stream=Files.newOutputStream(output.resolve(name+".eml"))) {mime.writeTo(stream);}
        Files.writeString(output.resolve(name+".html"),html.replace("cid:eventomax-logo","eventomax-logo-email.png"),StandardCharsets.UTF_8);
        Files.writeString(output.resolve(name+".txt"),plain,StandardCharsets.UTF_8);
        try(var stream=new ClassPathResource("branding/eventomax-logo-email.png").getInputStream()) {
            Files.copy(stream,output.resolve("eventomax-logo-email.png"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Test void missingLogoOmitsBothCidAndInlinePart() throws Exception {
        sender(false).send(payload("CONFIRMADO"));capture.message.saveChanges();
        var parts=new ArrayList<Part>();collect(capture.message,parts);
        assertThat(parts.stream().filter(p->is(p,"image/png"))).isEmpty();
        for(var part:parts) if(is(part,"text/html")) assertThat(part.getContent().toString()).contains("EventoMax").doesNotContain("cid:");
    }
    @ParameterizedTest @ValueSource(strings={"\r\nBcc: attacker@example.invalid","trace\nInjected: yes","trace\u0000"})
    void ordinaryHeaderInjectionCannotReachSmtp(String injected) {
        MDC.put("traceId",injected);
        try { assertThatThrownBy(()->sender(true).send(payload("CONFIRMADO"))).isInstanceOf(IllegalStateException.class); }
        finally { MDC.clear(); }
        assertThat(capture.message).isNull();
    }
    @Test void invalidClosurePdfAndInjectedHeadersNeverProduceAnAcceptedSend() throws Exception {
        var sender=sender(true);byte[] valid=pdf();
        assertThatThrownBy(()->sender.sendClosure(new MessageEnvelope<>("SendProductionStatusEmail","event\r\nInjected: yes",
                "2026-11-20T20:00:00Z","trace","correlation",payload("CERRADO")),DELIVERY,DOCUMENT,valid,ClosurePdf.sha256(valid)))
                .isInstanceOf(ClosurePreflightException.class);
        assertThatThrownBy(()->send(sender,"CERRADO",new byte[]{1,2,3})).isInstanceOf(ClosurePreflightException.class);
        assertThat(capture.message).isNull();
    }
    @Test void invalidClosureRecipientRemainsBlocked() throws Exception {
        byte[] bytes=pdf();var p=payload("CERRADO");
        for(String recipient:new String[]{null,"","a@example.invalid,b@example.invalid","x\r\nBcc:x@example.invalid"}) {
            var invalid=new EmailProductionStatusPayload(p.productionId(),p.organizerId(),recipient,p.productionName(),p.status(),p.scheduledAt(),p.location());
            assertThatThrownBy(()->sender(true).sendClosure(new MessageEnvelope<>("SendProductionStatusEmail","event","2026-11-20T20:00:00Z",
                    "trace","correlation",invalid),DELIVERY,DOCUMENT,bytes,ClosurePdf.sha256(bytes))).isInstanceOf(ClosurePreflightException.class);
        }
        assertThat(capture.message).isNull();
    }
    static void collect(Part part,List<Part> parts) throws Exception {
        parts.add(part);if(part.getContent() instanceof Multipart multi)
            for(int i=0;i<multi.getCount();i++) collect(multi.getBodyPart(i),parts);
    }
    static boolean is(Part part,String type) {try{return part.isMimeType(type);}catch(MessagingException ex){throw new IllegalStateException(ex);}}
    static String header(Part part,String name) {try{var values=part.getHeader(name);return values==null?"":values[0];}catch(MessagingException ex){throw new IllegalStateException(ex);}}
    static class Capture extends JavaMailSenderImpl {
        MimeMessage message;
        @Override public void send(MimeMessage message) {this.message=message;}
    }
}
