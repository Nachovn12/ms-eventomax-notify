package cl.duoc.eventomax.notify.controller;

import cl.duoc.eventomax.notify.storage.DocumentNotFoundException;
import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/notify/quotes")
public class NotifyQuoteController {

    private static final Logger log = LoggerFactory.getLogger(NotifyQuoteController.class);

    private final DocumentStorageService documentStorageService;
    private final Path localQuotesDir;

    public NotifyQuoteController(
            @Autowired(required = false) DocumentStorageService documentStorageService,
            @Value("${eventomax.notify.quote.output-dir:./data/quotes}") String outputDirPath) {
        this.documentStorageService = documentStorageService;
        this.localQuotesDir = Path.of(outputDirPath).toAbsolutePath().normalize();
    }

    @GetMapping(value = "/{productionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getQuotePdf(@PathVariable Long productionId) {
        String filename = "quote-production-" + productionId + "-latest.pdf";

        // 1. Intentar mediante DocumentStorageService si está disponible
        if (documentStorageService != null && documentStorageService.documentExists("quotes", filename)) {
            try {
                byte[] content = documentStorageService.retrieveDocument("quotes", filename);
                return createPdfResponse(productionId, content);
            } catch (DocumentNotFoundException e) {
                log.debug("Quote PDF no encontrado en documentStorageService para productionId={}", productionId);
            }
        }

        // 2. Fallback a disco local directo
        Path localFile = localQuotesDir.resolve(filename);
        if (Files.exists(localFile)) {
            try {
                byte[] content = Files.readAllBytes(localFile);
                return createPdfResponse(productionId, content);
            } catch (IOException e) {
                log.error("Error leyendo PDF local de disco: {}", localFile, e);
                return ResponseEntity.internalServerError().build();
            }
        }

        log.warn("Acta PDF no encontrada para productionId={}", productionId);
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<byte[]> createPdfResponse(Long productionId, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quote-production-" + productionId + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(content);
    }
}

