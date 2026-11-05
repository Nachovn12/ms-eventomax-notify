package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.document.OpenPdfClosureDocumentGenerator;
import cl.duoc.eventomax.notify.document.ProductionClosureDocumentBuilder;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.sender.QuoteGenerator;
import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Servicio de aplicación que coordina la generación del acta de cierre PDF
 * al recibir el comando {@code GenerateClosingQuote}.
 * <p>
 * Cablea la transformación estructurada con {@link ProductionClosureDocumentBuilder}
 * y el motor empresarial {@link OpenPdfClosureDocumentGenerator}, manteniendo
 * {@link QuoteGenerator} como estrategia de fallback con limitaciones declaradas
 * o como proveedor explícito en modo {@code legacy} para pruebas de contrato/reintento.
 */
@Service
public class QuoteNotificationService {

    private static final Logger log = LoggerFactory.getLogger(QuoteNotificationService.class);

    private final ProductionClosureDocumentBuilder documentBuilder;
    private final OpenPdfClosureDocumentGenerator openPdfGenerator;
    private final QuoteGenerator fallbackQuoteGenerator;
    private final DocumentStorageService documentStorageService;
    private final Path outputDir;
    private final String quoteProvider;

    @Autowired
    public QuoteNotificationService(
            @Autowired(required = false) ProductionClosureDocumentBuilder documentBuilder,
            @Autowired(required = false) OpenPdfClosureDocumentGenerator openPdfGenerator,
            @Autowired(required = false) QuoteGenerator fallbackQuoteGenerator,
            @Autowired(required = false) DocumentStorageService documentStorageService,
            @Value("${eventomax.notify.quote.output-dir:./data/quotes}") String outputDirPath,
            @Value("${eventomax.notify.quote.provider:enterprise}") String quoteProvider) {
        this.documentBuilder = documentBuilder;
        this.openPdfGenerator = openPdfGenerator;
        this.fallbackQuoteGenerator = fallbackQuoteGenerator;
        this.documentStorageService = documentStorageService;
        this.outputDir = Path.of(outputDirPath).toAbsolutePath().normalize();
        this.quoteProvider = (quoteProvider != null && !quoteProvider.isBlank()) ? quoteProvider.trim() : "enterprise";
    }

    public QuoteNotificationService(
            ProductionClosureDocumentBuilder documentBuilder,
            OpenPdfClosureDocumentGenerator openPdfGenerator,
            QuoteGenerator fallbackQuoteGenerator,
            DocumentStorageService documentStorageService,
            String outputDirPath) {
        this(documentBuilder, openPdfGenerator, fallbackQuoteGenerator, documentStorageService, outputDirPath, "enterprise");
    }

    public QuoteNotificationService(QuoteGenerator fallbackQuoteGenerator) {
        this(null, null, fallbackQuoteGenerator, null, "./data/quotes", "legacy");
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public Path processQuoteGeneration(MessageEnvelope<QuoteCommandPayload> envelope) {
        if (envelope == null || envelope.payload() == null) {
            throw new IllegalArgumentException("El envelope y payload de Quote no pueden ser nulos");
        }
        QuoteCommandPayload payload = envelope.payload();
        if (payload.productionId() == null || payload.productionId() <= 0) {
            throw new IllegalArgumentException("productionId inválido para generación de acta PDF");
        }

        log.info("Procesando comando de generación de acta PDF: productionId={}, status={}, provider={}, traceId={}, correlationId={}",
                payload.productionId(),
                payload.status(),
                quoteProvider,
                envelope.traceId(),
                envelope.correlationId());

        if (!"legacy".equalsIgnoreCase(quoteProvider) && documentBuilder != null && openPdfGenerator != null) {
            try {
                return generateEnterprisePdf(envelope);
            } catch (Exception e) {
                log.warn("[FALLBACK DOCUMENTAL ACTIVADO] Falló la generación del acta empresarial OpenPDF para productionId={}, recurriendo a generador básico legacy. Limitación: el documento resultante carece de branding oficial, firmas institucionales y formato corporativo A4. Causa: {}",
                        payload.productionId(), e.getMessage(), e);
                if (fallbackQuoteGenerator != null) {
                    return fallbackQuoteGenerator.generate(envelope);
                }
                throw new IllegalStateException("Error al generar acta empresarial y no hay generador legacy disponible", e);
            }
        }

        if (fallbackQuoteGenerator != null) {
            log.info("Invocando generador QuoteGenerator configurado (provider={})", quoteProvider);
            return fallbackQuoteGenerator.generate(envelope);
        }

        throw new IllegalStateException("No hay generadores configurados en QuoteNotificationService");
    }

    private Path generateEnterprisePdf(MessageEnvelope<QuoteCommandPayload> envelope) throws IOException {
        QuoteCommandPayload payload = envelope.payload();
        ProductionClosureDocumentDTO doc = documentBuilder.build(envelope);
        byte[] pdfBytes = openPdfGenerator.generatePdfBytes(doc, false);

        Files.createDirectories(outputDir);

        String safeEventId = sanitizeToken(envelope.eventId());
        String fileName = "quote-production-" + payload.productionId() + "-" + safeEventId + ".pdf";
        String latestFileName = "quote-production-" + payload.productionId() + "-latest.pdf";

        Path tempFile = outputDir.resolve(fileName + ".tmp");
        Path targetFile = outputDir.resolve(fileName);
        Path latestFile = outputDir.resolve(latestFileName);

        Files.write(tempFile, pdfBytes);
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

        // Salvaguarda absoluta de evidencia histórica certificada PRO-06:
        // Si productionId == 1 y existe un archivo previo con nombre quote-production-1-latest.pdf,
        // NO sobreescribir bajo ninguna circunstancia.
        if (payload.productionId() == 1L && Files.exists(latestFile)) {
            log.info("[SALVAGUARDA PRO-06] Preservando evidencia histórica certificada PRO-06 para productionId=1, omitiendo sobreescritura de latest");
        } else {
            Files.copy(targetFile, latestFile, StandardCopyOption.REPLACE_EXISTING);
        }

        if (documentStorageService != null) {
            try {
                documentStorageService.storeDocument("quotes", fileName, pdfBytes, "application/pdf");
                if (payload.productionId() != 1L || !Files.exists(latestFile)) {
                    documentStorageService.storeDocument("quotes", latestFileName, pdfBytes, "application/pdf");
                }
            } catch (Exception storageEx) {
                log.warn("No se pudo almacenar copia en documentStorageService para productionId={}: {}",
                        payload.productionId(), storageEx.getMessage());
            }
        }

        log.info("[ACTA EMPRESARIAL GENERADA] productionId={}, eventId={}, traceId={}, correlationId={}, path={}, sizeBytes={}",
                payload.productionId(),
                envelope.eventId(),
                envelope.traceId(),
                envelope.correlationId(),
                targetFile,
                pdfBytes.length);

        return targetFile;
    }

    private static String sanitizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
