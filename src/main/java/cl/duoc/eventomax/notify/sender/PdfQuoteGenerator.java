package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementación real de {@link QuoteGenerator} que construye y almacena en disco
 * un documento PDF 1.4 estándar (ISO 32000-1) con el Acta de Cierre Operativo de la producción.
 * <p>
 * Utiliza exclusivamente los datos reales del evento de producción sin inventar montos financieros.
 */
@Component
public class PdfQuoteGenerator implements QuoteGenerator {

    private static final Logger log = LoggerFactory.getLogger(PdfQuoteGenerator.class);

    private final Path outputDir;

    public PdfQuoteGenerator(
            @Value("${eventomax.notify.quote.output-dir:./data/quotes}") String outputDirPath) {
        this.outputDir = Path.of(outputDirPath).toAbsolutePath().normalize();
    }

    public Path getOutputDir() {
        return outputDir;
    }

    @Override
    public Path generate(MessageEnvelope<QuoteCommandPayload> envelope) {
        if (envelope == null || envelope.payload() == null) {
            throw new IllegalArgumentException("El envelope y payload de Quote no pueden ser nulos");
        }
        QuoteCommandPayload payload = envelope.payload();
        if (payload.productionId() == null || payload.productionId() <= 0) {
            throw new IllegalArgumentException("productionId inválido para generación de PDF");
        }

        String safeEventId = sanitizeToken(envelope.eventId());
        String fileName = "quote-production-" + payload.productionId() + "-" + safeEventId + ".pdf";
        String latestFileName = "quote-production-" + payload.productionId() + "-latest.pdf";

        try {
            Files.createDirectories(outputDir);
            byte[] pdfBytes = buildPdfDocument(envelope);

            Path tempFile = outputDir.resolve(fileName + ".tmp");
            Path targetFile = outputDir.resolve(fileName);
            Path latestFile = outputDir.resolve(latestFileName);

            Files.write(tempFile, pdfBytes);
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(targetFile, latestFile, StandardCopyOption.REPLACE_EXISTING);

            log.info("[QUOTE PDF GENERADO] productionId={}, eventId={}, traceId={}, correlationId={}, path={}, sizeBytes={}",
                    payload.productionId(),
                    envelope.eventId(),
                    envelope.traceId(),
                    envelope.correlationId(),
                    targetFile,
                    pdfBytes.length);

            return targetFile;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Error al escribir el acta PDF para productionId=" + payload.productionId(), e);
        }
    }

    static byte[] buildPdfDocument(MessageEnvelope<QuoteCommandPayload> envelope) {
        QuoteCommandPayload p = envelope.payload();
        String closedAt = (p.closedAt() != null && !p.closedAt().isBlank())
                ? p.closedAt()
                : envelope.timestamp();

        List<String> lines = List.of(
                "EVENTOMAX - ACTA DE CIERRE Y CONSTANCIA DOCUMENTAL (EMX-25)",
                "------------------------------------------------------------------------",
                "1. IDENTIFICACION DE LA PRODUCCION",
                "   ID Produccion      : " + p.productionId(),
                "   Nombre Produccion  : " + safeText(p.productionName()),
                "   Organizador ID     : " + safeText(p.organizerId()),
                "   Estado Final       : " + safeText(p.status()),
                "   Fecha Programada   : " + safeText(p.scheduledAt()),
                "   Ubicacion / Recinto: " + safeText(p.location()),
                "   Fecha/Hora Cierre  : " + safeText(closedAt),
                "------------------------------------------------------------------------",
                "2. TRAZABILIDAD DEL COMANDO ASINCRONO (RABBITMQ)",
                "   Comando            : " + safeText(envelope.type()),
                "   Event ID           : " + safeText(envelope.eventId()),
                "   Timestamp Emision  : " + safeText(envelope.timestamp()),
                "   Trace ID           : " + safeText(envelope.traceId()),
                "   Correlation ID     : " + safeText(envelope.correlationId()),
                "------------------------------------------------------------------------",
                "3. DECLARACION FINANCIERA Y OPERATIVA",
                "   Valores financieros: No registrados en el modelo de Producciones.",
                "   Este documento constituye el acta oficial de cierre operativo",
                "   emitida con los datos reales de la produccion sin montos simulados."
        );

        StringBuilder streamBuilder = new StringBuilder();
        streamBuilder.append("BT\n");
        streamBuilder.append("/F2 13 Tf\n");
        streamBuilder.append("50 780 Td\n");
        streamBuilder.append("18 TL\n");

        for (int i = 0; i < lines.size(); i++) {
            if (i == 1) {
                streamBuilder.append("/F1 10 Tf\n");
            }
            streamBuilder.append("(").append(escapePdfText(lines.get(i))).append(") Tj\n");
            if (i < lines.size() - 1) {
                streamBuilder.append("T*\n");
            }
        }
        streamBuilder.append("ET\n");
        byte[] contentBytes = streamBuilder.toString().getBytes(StandardCharsets.ISO_8859_1);

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        List<Integer> offsets = new ArrayList<>();

        writeAscii(out, "%PDF-1.4\n");

        // Object 1: Catalog
        offsets.add(out.size());
        writeAscii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        // Object 2: Pages
        offsets.add(out.size());
        writeAscii(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

        // Object 3: Page (A4 595x842 pt)
        offsets.add(out.size());
        writeAscii(out, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                + "/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>\nendobj\n");

        // Object 4: Font Helvetica
        offsets.add(out.size());
        writeAscii(out, "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");

        // Object 5: Font Helvetica-Bold
        offsets.add(out.size());
        writeAscii(out, "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");

        // Object 6: Content Stream
        offsets.add(out.size());
        writeAscii(out, "6 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
        out.writeBytes(contentBytes);
        writeAscii(out, "endstream\nendobj\n");

        // Cross-reference table (xref)
        int xrefOffset = out.size();
        int totalObjects = offsets.size() + 1;
        writeAscii(out, "xref\n0 " + totalObjects + "\n");
        writeAscii(out, "0000000000 65535 f \n");
        for (int offset : offsets) {
            writeAscii(out, String.format("%010d 00000 n \n", offset));
        }

        // Trailer
        writeAscii(out, "trailer\n<< /Size " + totalObjects + " /Root 1 0 R >>\n");
        writeAscii(out, "startxref\n" + xrefOffset + "\n%%EOF\n");

        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String safeText(String value) {
        return value == null ? "N/A" : value;
    }

    private static String escapePdfText(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static String sanitizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

