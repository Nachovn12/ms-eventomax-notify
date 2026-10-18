package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfQuoteGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Genera un archivo PDF 1.4 real en disco con los datos de Production y sin valores financieros inventados")
    void generate_shouldCreateRealPdfWithProductionDataAndNoInventedFinancials() throws Exception {
        PdfQuoteGenerator generator = new PdfQuoteGenerator(tempDir.toString());

        QuoteCommandPayload payload = new QuoteCommandPayload(
                501L,
                "org-ep2-demo",
                "Festival Cierre EventoMax",
                "CERRADO",
                "2026-12-20T21:00",
                "Movistar Arena",
                "2026-12-21T02:15:00Z"
        );
        MessageEnvelope<QuoteCommandPayload> envelope = new MessageEnvelope<>(
                "GenerateClosingQuote",
                "evt-quote-501",
                "2026-12-21T02:15:01Z",
                "trace-quote-501",
                "corr-quote-501",
                payload
        );

        Path pdfPath = generator.generate(envelope);

        assertNotNull(pdfPath);
        assertTrue(Files.exists(pdfPath), "El archivo PDF debe existir en disco");
        Path latestPath = tempDir.resolve("quote-production-501-latest.pdf");
        assertTrue(Files.exists(latestPath), "La copia latest por productionId debe existir en disco");

        byte[] bytes = Files.readAllBytes(pdfPath);
        assertTrue(bytes.length > 400, "El PDF debe tener contenido binario real");

        String rawContent = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(rawContent.startsWith("%PDF-1.4\n"), "Debe iniciar con cabecera estándar %PDF-1.4");
        assertTrue(rawContent.contains("/Type /Catalog"), "Debe contener catálogo PDF");
        assertTrue(rawContent.contains("/Type /Page"), "Debe contener definición de página PDF");
        assertTrue(rawContent.contains("xref\n"), "Debe contener tabla xref PDF");
        assertTrue(rawContent.trim().endsWith("%%EOF"), "Debe finalizar con marcador %%EOF");

        assertTrue(rawContent.contains("501"), "Debe incluir el ID de producción real");
        assertTrue(rawContent.contains("Festival Cierre EventoMax"), "Debe incluir el nombre de la producción");
        assertTrue(rawContent.contains("org-ep2-demo"), "Debe incluir el organizador");
        assertTrue(rawContent.contains("CERRADO"), "Debe incluir el estado final CERRADO");
        assertTrue(rawContent.contains("Movistar Arena"), "Debe incluir la ubicación");
        assertTrue(rawContent.contains("evt-quote-501"), "Debe incluir el eventId");
        assertTrue(rawContent.contains("trace-quote-501"), "Debe incluir el traceId");
        assertTrue(rawContent.contains("corr-quote-501"), "Debe incluir el correlationId");
        assertTrue(rawContent.contains("sin montos simulados"), "Debe declarar explícitamente la no invención de montos financieros");
        assertFalse(rawContent.contains("$"), "No debe inventar montos monetarios con símbolo $");
    }

    @Test
    @DisplayName("Rechaza envelope o payload inválido")
    void generate_invalidInput_shouldThrowException() {
        PdfQuoteGenerator generator = new PdfQuoteGenerator(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> generator.generate(null));
    }
}

