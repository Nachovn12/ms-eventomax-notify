package cl.duoc.eventomax.notify.closure;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ClosurePdfTest {
    static byte[] pdf(long productionId, UUID documentId) throws Exception {
        var out = new ByteArrayOutputStream();
        var doc = new Document();
        PdfWriter.getInstance(doc, out);
        doc.open(); doc.add(new Paragraph("Isolated closure fixture")); doc.close();
        return ClosurePdf.identify(out.toByteArray(), productionId, documentId);
    }
    @Test void verifiesBytesAndProductionAndDocumentIdentity() throws Exception {
        UUID id = UUID.randomUUID(); byte[] pdf = pdf(8, id); String sha = ClosurePdf.sha256(pdf);
        assertThatCode(() -> ClosurePdf.verify(pdf, 8, id, sha, pdf.length, 100000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ClosurePdf.verify(pdf, 9, id, sha, pdf.length, 100000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClosurePdf.verify(pdf, 8, UUID.randomUUID(), sha, pdf.length, 100000)).isInstanceOf(IllegalArgumentException.class);
        pdf[30] ^= 1;
        assertThatThrownBy(() -> ClosurePdf.verify(pdf, 8, id, sha, pdf.length, 100000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesNonPdfEvenWithCorrectHash() {
        byte[] bytes = "not a pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ClosurePdf.verify(bytes, 8, UUID.randomUUID(), ClosurePdf.sha256(bytes), bytes.length, 100000))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void recipientHasToBeOneExplicitMailbox() {
        assertThat(RecipientCheck.valid("fixture@example.invalid")).isTrue();
        for (String invalid : new String[]{"", " ", "organizer-id", "a@example.invalid,b@example.invalid", "a@example.invalid\r\nBcc: x@example.invalid"}) {
            assertThat(RecipientCheck.valid(invalid)).isFalse();
        }
        assertThat(RecipientCheck.valid(null)).isFalse();
    }
    @Test void refusesInvalidWorkerLimits() {
        assertThatThrownBy(() -> new ClosureSettings(true, true, true, false, Duration.ZERO, Duration.ofMinutes(15), 5, 10000000, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ambiguousTransportNeverBecomesRetryable() {
        assertThat(SmtpOutcome.classify(new org.springframework.mail.MailSendException("fixture timeout", new java.net.SocketTimeoutException())))
                .isEqualTo(ClosureState.UNKNOWN);
        assertThat(SmtpOutcome.classify(new java.net.ConnectException("fixture refused"))).isEqualTo(ClosureState.RETRY_WAIT);
        assertThat(SmtpOutcome.classify(new org.springframework.mail.MailAuthenticationException("fixture rejected")))
                .isEqualTo(ClosureState.FAILED_PERMANENT);
    }
}
