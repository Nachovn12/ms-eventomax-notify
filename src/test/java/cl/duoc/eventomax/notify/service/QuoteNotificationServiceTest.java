package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.document.OpenPdfClosureDocumentGenerator;
import cl.duoc.eventomax.notify.document.ProductionClosureDocumentBuilder;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO.*;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.sender.QuoteGenerator;
import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteNotificationServiceTest {

    @Mock
    private ProductionClosureDocumentBuilder documentBuilder;

    @Mock
    private OpenPdfClosureDocumentGenerator openPdfGenerator;

    @Mock
    private QuoteGenerator fallbackQuoteGenerator;

    @Mock
    private DocumentStorageService documentStorageService;

    private MessageEnvelope<QuoteCommandPayload> createTestEnvelope(Long productionId, String eventId) {
        QuoteCommandPayload payload = new QuoteCommandPayload(
                productionId,
                "org-ep2-demo",
                "Gala de Cierre EP2",
                "CERRADO",
                "2026-10-20T20:00:00",
                "Espacio Riesco",
                "2026-10-21T01:00:00Z"
        );
        return new MessageEnvelope<>(
                "GenerateClosingQuote",
                eventId,
                "2026-10-21T01:00:01Z",
                "trace-b3-001",
                "corr-b3-001",
                payload
        );
    }

    private ProductionClosureDocumentDTO createDummyDocumentDTO(Long productionId) {
        var metadata = new DocumentMetadataDTO(
                "EMX-ACTA-PRD-" + productionId,
                "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN",
                "1.0",
                Instant.now(),
                "ms-eventomax-notify"
        );

        var production = new ProductionIdentityDTO(
                productionId,
                "Gala de Cierre EP2",
                "CERRADO",
                LocalDateTime.parse("2026-10-20T20:00:00"),
                "Espacio Riesco",
                LocalDateTime.parse("2026-10-19T12:00:00"),
                LocalDateTime.parse("2026-10-20T23:30:00"),
                "2026-10-21 01:00:00 UTC"
        );

        var organizer = new OrganizerIdentityDTO(
                "org-ep2-demo",
                "or******@eventomax.cl",
                "Organizador Demo",
                null
        );

        var timeline = List.of(
                new OperationalTimelineItemDTO(1, null, "SOLICITADO", Instant.now(), "actor-1", "evt-1"),
                new OperationalTimelineItemDTO(2, "SOLICITADO", "CERRADO", Instant.now(), "actor-2", "evt-2")
        );

        var equipment = List.of(
                new ReservedEquipmentRecordDTO(1L, "Generador 50kVA", 1, "RESERVED")
        );

        var services = List.of(
                new ContractedServiceRecordDTO(1L, "Iluminación Escenario", new BigDecimal("150.00"), LocalDateTime.now())
        );

        var crewTicket = new CrewTicketRecordDTO(
                1L,
                "ticket-uuid-001",
                "EN_MONTAJE",
                "Instrucciones operativas de prueba",
                LocalDateTime.now()
        );

        var adminDecl = new AdministrativeDeclarationDTO(
                "Declaración administrativa de cierre.",
                "Observación operativa.",
                "Nota legal de no certificación tributaria."
        );

        var trace = new TechnicalTraceabilityDTO(
                "cmd-evt-001",
                "closing-evt-001",
                "trace-b3-001",
                "corr-b3-001",
                "actor-final"
        );

        var conformity = new ConformityStatusDTO(
                "PENDIENTE DE CONFORMIDAD",
                "PENDIENTE DE CONFORMIDAD",
                false
        );

        return new ProductionClosureDocumentDTO(
                metadata, production, organizer, timeline, equipment, services,
                crewTicket, adminDecl, trace, conformity,
                CatalogAvailabilityState.AVAILABLE
        );
    }

    @Test
    @DisplayName("1. Modo legacy directo: cuando no hay builder/openPdfGenerator delega al generador legacy")
    void processQuoteGeneration_withLegacyGeneratorOnly_shouldDelegateToLegacy() {
        var service = new QuoteNotificationService(fallbackQuoteGenerator);
        var envelope = createTestEnvelope(300L, "evt-quote-legacy-1");
        Path expectedPath = Path.of("data/quotes/quote-production-300-evt-quote-legacy-1.pdf");
        when(fallbackQuoteGenerator.generate(envelope)).thenReturn(expectedPath);

        Path result = service.processQuoteGeneration(envelope);

        assertEquals(expectedPath, result);
        verify(fallbackQuoteGenerator, times(1)).generate(envelope);
    }

    @Test
    @DisplayName("2. Modo empresarial completo: construye DTO, genera PDF OpenPDF y persiste archivos")
    void processQuoteGeneration_withEnterpriseComponents_shouldGenerateEnterprisePdf(@TempDir Path tempDir) throws IOException {
        var service = new QuoteNotificationService(
                documentBuilder,
                openPdfGenerator,
                fallbackQuoteGenerator,
                documentStorageService,
                tempDir.toString()
        );
        var envelope = createTestEnvelope(500L, "evt-b3-enterprise");
        var docDto = createDummyDocumentDTO(500L);
        byte[] expectedPdfBytes = "%PDF-1.7 Enterprise Mock PDF Content".getBytes(StandardCharsets.UTF_8);

        when(documentBuilder.build(envelope)).thenReturn(docDto);
        when(openPdfGenerator.generatePdfBytes(docDto, false)).thenReturn(expectedPdfBytes);

        Path result = service.processQuoteGeneration(envelope);

        assertTrue(Files.exists(result));
        assertEquals("quote-production-500-evt-b3-enterprise.pdf", result.getFileName().toString());
        assertArrayEquals(expectedPdfBytes, Files.readAllBytes(result));

        Path latestPath = tempDir.resolve("quote-production-500-latest.pdf");
        assertTrue(Files.exists(latestPath));
        assertArrayEquals(expectedPdfBytes, Files.readAllBytes(latestPath));

        verify(fallbackQuoteGenerator, never()).generate(any());
        verify(documentStorageService, times(1)).storeDocument("quotes", "quote-production-500-evt-b3-enterprise.pdf", expectedPdfBytes, "application/pdf");
        verify(documentStorageService, times(1)).storeDocument("quotes", "quote-production-500-latest.pdf", expectedPdfBytes, "application/pdf");
    }

    @Test
    @DisplayName("3. Resiliencia y fallback: si el motor OpenPDF falla, recurre a generador legacy registrando advertencia")
    void processQuoteGeneration_whenEnterpriseFails_shouldTriggerFallbackWithWarning(@TempDir Path tempDir) {
        var service = new QuoteNotificationService(
                documentBuilder,
                openPdfGenerator,
                fallbackQuoteGenerator,
                documentStorageService,
                tempDir.toString()
        );
        var envelope = createTestEnvelope(500L, "evt-b3-fallback");
        var docDto = createDummyDocumentDTO(500L);
        Path legacyPath = tempDir.resolve("quote-production-500-legacy.pdf");

        when(documentBuilder.build(envelope)).thenReturn(docDto);
        when(openPdfGenerator.generatePdfBytes(docDto, false)).thenThrow(new RuntimeException("Simulated OpenPDF Layout Exception"));
        when(fallbackQuoteGenerator.generate(envelope)).thenReturn(legacyPath);

        Path result = service.processQuoteGeneration(envelope);

        assertEquals(legacyPath, result);
        verify(fallbackQuoteGenerator, times(1)).generate(envelope);
    }

    @Test
    @DisplayName("4. Si tanto el motor empresarial como el fallback fallan, propaga excepción limpia")
    void processQuoteGeneration_whenBothFail_shouldThrowException(@TempDir Path tempDir) {
        var service = new QuoteNotificationService(
                documentBuilder,
                openPdfGenerator,
                fallbackQuoteGenerator,
                documentStorageService,
                tempDir.toString()
        );
        var envelope = createTestEnvelope(500L, "evt-b3-fatal");
        var docDto = createDummyDocumentDTO(500L);

        when(documentBuilder.build(envelope)).thenReturn(docDto);
        when(openPdfGenerator.generatePdfBytes(docDto, false)).thenThrow(new RuntimeException("OpenPDF Fatal"));
        when(fallbackQuoteGenerator.generate(envelope)).thenThrow(new RuntimeException("Legacy Fatal"));

        assertThrows(RuntimeException.class, () -> service.processQuoteGeneration(envelope));
    }

    @Test
    @DisplayName("5. Salvaguarda PRO-06: para productionId=1 no sobreescribe quote-production-1-latest.pdf preexistente")
    void processQuoteGeneration_forProduction1_whenLatestExists_shouldPreserveLatest(@TempDir Path tempDir) throws IOException {
        var service = new QuoteNotificationService(
                documentBuilder,
                openPdfGenerator,
                fallbackQuoteGenerator,
                documentStorageService,
                tempDir.toString()
        );
        var envelope = createTestEnvelope(1L, "evt-b3-prod1-test");
        var docDto = createDummyDocumentDTO(1L);

        // Crear archivo latest preexistente protegido (simulando certificación PRO-06)
        byte[] historicalCertBytes = "PRO-06 CERTIFIED HISTORICAL EVIDENCE SHA256".getBytes(StandardCharsets.UTF_8);
        Path latestPath = tempDir.resolve("quote-production-1-latest.pdf");
        Files.write(latestPath, historicalCertBytes);

        byte[] newPdfBytes = "%PDF-1.7 New Generated Acta".getBytes(StandardCharsets.UTF_8);
        when(documentBuilder.build(envelope)).thenReturn(docDto);
        when(openPdfGenerator.generatePdfBytes(docDto, false)).thenReturn(newPdfBytes);

        Path result = service.processQuoteGeneration(envelope);

        assertTrue(Files.exists(result));
        assertArrayEquals(newPdfBytes, Files.readAllBytes(result));

        // Verificar que quote-production-1-latest.pdf NO fue sobreescrito
        assertArrayEquals(historicalCertBytes, Files.readAllBytes(latestPath));

        // Verificar que en documentStorageService NO se intentó sobreescribir latest
        verify(documentStorageService, never()).storeDocument("quotes", "quote-production-1-latest.pdf", newPdfBytes, "application/pdf");
    }

    @Test
    @DisplayName("6. Configuración explícita provider=legacy: delega directamente a QuoteGenerator incluso si existen componentes empresariales")
    void processQuoteGeneration_whenProviderIsLegacy_shouldDelegateDirectlyToQuoteGenerator(@TempDir Path tempDir) {
        var service = new QuoteNotificationService(
                documentBuilder,
                openPdfGenerator,
                fallbackQuoteGenerator,
                documentStorageService,
                tempDir.toString(),
                "legacy"
        );
        var envelope = createTestEnvelope(600L, "evt-b3-explicit-legacy");
        Path expectedPath = tempDir.resolve("quote-production-600-evt-b3-explicit-legacy.pdf");
        when(fallbackQuoteGenerator.generate(envelope)).thenReturn(expectedPath);

        Path result = service.processQuoteGeneration(envelope);

        assertEquals(expectedPath, result);
        verify(fallbackQuoteGenerator, times(1)).generate(envelope);
        verify(documentBuilder, never()).build(any());
    }
}
