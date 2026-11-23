package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.closure.ClosurePdf;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO.*;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class OpenPdfClosureDocumentGeneratorTest {

    private static final List<String> FORBIDDEN_CLAIMS_UPPER = List.of(
            "CERTIFICADO OFICIAL",
            "FIRMA ELECTRÓNICA",
            "FIRMANTE 1",
            "FIRMANTE 2",
            "LEY 19.799",
            "19.799",
            "TIMBRE ELECTRÓNICO",
            "PDF417",
            "CSV",
            "HOJA DE RUTA EJECUTADA",
            "CERRADO · AUDITADO",
            "KRAFT",
            "SIN INCIDENCIAS",
            "NINGUNA REGISTRADA"
    );

    private OpenPdfClosureDocumentGenerator generator;
    private Path outputDir;
    private Path visualDir;

    @BeforeEach
    void setUp() throws IOException {
        generator = new OpenPdfClosureDocumentGenerator();
        outputDir = Path.of("target", "test-output");
        visualDir = outputDir.resolve("design-c-visual");
        Files.createDirectories(outputDir);
        Files.createDirectories(visualDir);
    }

    private ProductionClosureDocumentDTO createCompleteFictitiousFixture() {
        var metadata = new DocumentMetadataDTO(
                "EMX-ACTA-PRD-101",
                ProductionClosureDocumentBuilder.DOCUMENT_TITLE,
                "2.0",
                Instant.parse("2026-11-22T21:14:08Z"),
                "ms-eventomax-notify"
        );

        var production = new ProductionIdentityDTO(
                101L,
                "Cumbre Cloud Native Ficticia 2026 (Datos de Prueba)",
                "CERRADO",
                LocalDateTime.parse("2026-12-10T19:00:00"),
                "Centro de Eventos Ficticio Parque Sur — Santiago",
                LocalDateTime.parse("2026-11-20T14:22:10"),
                LocalDateTime.parse("2026-11-22T21:14:05"),
                "2026-11-22 21:14:05 UTC"
        );

        var organizer = new OrganizerIdentityDTO(
                "00000000-0000-0000-0000-000000000001:a1b2c3d4-e5f6-4708-8901-000000000099",
                "an*******@ejemplo.invalid",
                "Contacto Respaldo Ficticio",
                null,
                "Andrea Morales Ficticia",
                "ENTRA_GRAPH"
        );

        var timeline = List.of(
                new OperationalTimelineItemDTO(1, null, "SOLICITADO", Instant.parse("2026-11-20T14:22:10Z"), "00000000-0000-0000-0000-000000000001:a1b2c3d4-e5f6-4708-8901-000000000099", "d56e5b82-2cee-4d26-b767-4eadd3d00001", "Andrea Morales Ficticia"),
                new OperationalTimelineItemDTO(2, "SOLICITADO", "CONFIRMADO", Instant.parse("2026-11-20T15:05:31Z"), "00000000-0000-0000-0000-000000000001:7d30a1b2-c3d4-4567-8901-0000000042df", "7ce03628-eaf3-4241-bffe-f37634ac0002", "Carlos Paredes Ficticio"),
                new OperationalTimelineItemDTO(3, "CONFIRMADO", "EN_MONTAJE", Instant.parse("2026-11-21T15:40:10Z"), "00000000-0000-0000-0000-000000000001:7d30a1b2-c3d4-4567-8901-0000000042df", "f96cef4a-5091-4d06-8027-a8b1319c0003", "Carlos Paredes Ficticio"),
                new OperationalTimelineItemDTO(4, "EN_MONTAJE", "EN_EJECUCION", Instant.parse("2026-11-22T18:10:02Z"), "00000000-0000-0000-0000-000000000001:7d30a1b2-c3d4-4567-8901-0000000042df", "22cf4618-3728-435c-a67b-e55517d20004", "Carlos Paredes Ficticio"),
                new OperationalTimelineItemDTO(5, "EN_EJECUCION", "CERRADO", Instant.parse("2026-11-22T21:14:05Z"), "00000000-0000-0000-0000-000000000001:7d30a1b2-c3d4-4567-8901-0000000042df", "274e40d9-cb8e-4205-93a7-c62605d20005", "Carlos Paredes Ficticio")
        );

        var equipment = List.of(
                new ReservedEquipmentRecordDTO(1L, "Consola Digital Ficticia 32 Canales FOH", 2, "RESERVED", 401L),
                new ReservedEquipmentRecordDTO(2L, "Arreglo Lineal Acústico Ficticio 12\"", 6, "RESERVED", 402L),
                new ReservedEquipmentRecordDTO(5L, "Generador Eléctrico Ficticio 50 kVA", 1, "RELEASED", 403L)
        );

        var services = List.of(
                new ContractedServiceRecordDTO(1L, "Ingeniería de Sonido en Vivo FOH (Prueba)", new BigDecimal("950000.00"), LocalDateTime.parse("2026-11-20T15:05:00")),
                new ContractedServiceRecordDTO(3L, "Diseño y Operación de Iluminación Robótica (Prueba)", new BigDecimal("680000.00"), LocalDateTime.parse("2026-11-20T15:05:00"))
        );

        var crewTicket = new CrewTicketRecordDTO(
                19L,
                "33d5f659-8c07-44ec-b413-456abc5f0019",
                "EN_MONTAJE",
                "Instrucciones ficticias de montaje: instalar arreglo lineal FOH, consola digital y distribución eléctrica principal. Validar puntos de anclaje previos a apertura.",
                LocalDateTime.parse("2026-11-21T15:40:12")
        );

        var adminDecl = new AdministrativeDeclarationDTO(
                "La producción ha alcanzado su estado administrativo CERRADO en la plataforma EventoMax.",
                "El cierre administrativo refleja el estado registrado en plataforma y no acredita por sí solo la ejecución física del evento.",
                ProductionClosureDocumentBuilder.SCOPE_NOTICE
        );

        var trace = new TechnicalTraceabilityDTO(
                "678c5962-6568-4db9-9e1a-4a8fb7a60011",
                "274e40d9-cb8e-4205-93a7-c62605d20005",
                "b3c914d3-dd99-4c6a-9429-50ee613d0011",
                "b060074a-db80-40fc-ba21-a0dabd220011",
                "00000000-0000-0000-0000-000000000001:7d30a1b2-c3d4-4567-8901-0000000042df",
                "Carlos Paredes Ficticio"
        );

        var conformity = new ConformityStatusDTO(
                "REGISTRADO EN PLATAFORMA",
                ProductionClosureDocumentBuilder.ORGANIZER_CONFORMITY_STATUS,
                false
        );

        var crewAssignment = new CrewAssignmentDTO("crew-prueba-sur", "Cuadrilla Técnica Ficticia Sur");

        return new ProductionClosureDocumentDTO(
                metadata, production, organizer, timeline, equipment, services,
                crewTicket, adminDecl, trace, conformity,
                CatalogAvailabilityState.AVAILABLE,
                crewAssignment
        );
    }

    @Test
    @DisplayName("Diseño C estándar (Fixture 1): Página 1 Acta Ejecutiva limpia + Página 2 Anexo Técnico sin afirmaciones falsas")
    void testDesignCCompleteFixture_twoPagesExecutiveAndAnnex() throws IOException {
        ProductionClosureDocumentDTO fixture = createCompleteFictitiousFixture();
        Path pdfFile = visualDir.resolve("fixture-1-normal-completa.pdf");
        generator.generatePdfFile(fixture, pdfFile, false);

        byte[] pdfBytes = Files.readAllBytes(pdfFile);
        assertTrue(pdfBytes.length > 5000);

        // Compatibilidad completa con ClosurePdf.identify y ClosurePdf.verify
        UUID documentId = UUID.randomUUID();
        byte[] identified = ClosurePdf.identify(pdfBytes, 101L, documentId);
        assertDoesNotThrow(() -> ClosurePdf.verify(
                identified, 101L, documentId, ClosurePdf.sha256(identified), identified.length, 10_000_000));

        try (PdfReader reader = new PdfReader(pdfBytes)) {
            Rectangle pageSize = reader.getPageSize(1);
            assertEquals(PageSize.A4.getWidth(), pageSize.getWidth(), 1.0f);
            assertEquals(PageSize.A4.getHeight(), pageSize.getHeight(), 1.0f);
            assertEquals(2, reader.getNumberOfPages(), "El caso estándar Diseño C debe producir exactamente 2 páginas (P1 Ejecutiva + P2 Anexo Técnico)");
        }

        try (PDDocument pdDoc = Loader.loadPDF(pdfBytes)) {
            String page1 = extractPageText(pdDoc, 1);
            String page2 = extractPageText(pdDoc, 2);
            String fullText = page1 + "\n" + page2;

            // --- PÁGINA 1: ACTA EJECUTIVA ---
            assertTrue(page1.contains("ACTA DE CIERRE OPERATIVO"));
            assertTrue(page1.contains("Registro de cierre de producción"));
            assertTrue(page1.contains("FOLIO N° 000101"));
            assertTrue(page1.contains("EMX-ACTA-PRD-101"));
            assertTrue(page1.contains("Estado: CERRADO"));
            assertTrue(page1.contains("Cumbre Cloud Native Ficticia 2026"));
            String page1Normalized = page1.replaceAll("\\s+", " ");
            assertTrue(page1Normalized.contains("Andrea Morales Ficticia (Resuelto · Microsoft Graph)"));
            assertTrue(page1Normalized.contains("Correo informado en el comando (enmascarado)"));
            assertTrue(page1Normalized.contains("an*******@ejemplo.invalid"));
            assertFalse(page1Normalized.contains("Correo de contacto"), "No debe atribuir el correo del comando directamente como correo de contacto del organizador");
            assertFalse(page1Normalized.contains("Canal de contacto"), "No debe etiquetar el correo del comando como canal de contacto verificado del organizador");
            assertTrue(page1Normalized.contains("Carlos Paredes Ficticio"));
            assertTrue(page1.contains("Ingeniería de Sonido en Vivo FOH (Prueba)"));
            assertTrue(page1.contains("950,000.00"));
            assertTrue(page1.contains("1,630,000.00"));
            assertTrue(page1.contains("Consola Digital Ficticia 32 Canales FOH"));
            assertTrue(page1.contains("Reservado (activo en catálogo)"));
            assertTrue(page1.contains("Liberado"));
            assertTrue(page1.contains("RES-401"));
            assertTrue(page1.contains("RES-403"));
            assertTrue(page1.contains("Cuadrilla Técnica Ficticia Sur"));
            assertTrue(page1.contains("crew-prueba-sur"));
            assertTrue(page1.contains("Ticket #19"));
            assertTrue(page1.contains("Conformidad no gestionada en esta versión"));
            assertTrue(page1.contains("Este documento registra información operativa de EventoMax"));
            assertTrue(page1.contains("Página 1 de 2"));

            // Página 1 NO debe exponer UUIDs/OIDs técnicos ni moneda inventada
            assertFalse(page1.contains("a1b2c3d4-e5f6-4708-8901-000000000099"), "Página 1 no debe mostrar UUID del organizador");
            assertFalse(page1.contains("7d30a1b2-c3d4-4567-8901-0000000042df"), "Página 1 no debe mostrar OID del actor de cierre");
            assertFalse(page1.contains("678c5962-6568-4db9-9e1a-4a8fb7a60011"), "Página 1 no debe contener RabbitMQ eventId");
            assertFalse(page1.contains("274e40d9-cb8e-4205-93a7-c62605d20005"), "Página 1 no debe contener Kafka eventId");
            assertFalse(fullText.contains("$"), "No debe anteponer símbolo monetario '$'");
            assertFalse(fullText.contains("CLP"), "No debe inventar moneda CLP");
            assertFalse(fullText.contains("USD"), "No debe inventar moneda USD");

            // --- PÁGINA 2: ANEXO TÉCNICO ---
            assertTrue(page2.contains("ANEXO TÉCNICO"));
            assertTrue(page2.contains("EMX-ACTA-PRD-101"));
            assertTrue(page2.contains("FOLIO N° 000101"));
            assertTrue(page2.contains("ZooKeeper"));
            assertTrue(page2.contains("quote.gen"));
            assertTrue(page2.contains("274e40d9-cb8e-4205-93a7-c62605d20005"));
            assertTrue(page2.contains("678c5962-6568-4db9-9e1a-4a8fb7a60011"));
            assertTrue(page2.contains("b3c914d3-dd99-4c6a-9429-50ee613d0011"));
            assertTrue(page2.contains("b060074a-db80-40fc-ba21-a0dabd220011"));
            assertTrue(page2.contains("33d5f659-8c07-44ec-b413-456abc5f0019"));
            assertTrue(page2.contains("SHA-256 de datos canónicos (generador)"));
            assertTrue(page2.contains("SHA-256 de bytes finales del PDF (ClosurePdf)"));
            assertTrue(Pattern.compile("[0-9a-f]{64}").matcher(page2).find(), "Debe incluir huella SHA-256 canónica de 64 caracteres");
            assertTrue(page2.contains("(enmascarado)"), "Debe enmascarar identidades técnicas personales en el anexo");
            assertFalse(page2.contains("a1b2c3d4-e5f6-4708-8901-000000000099"), "No debe exponer el OID completo sin enmascarar");
            assertTrue(page2.contains("Página 2 de 2"));

            // --- AUSENCIA ESTRICTA DE AFIRMACIONES FALSAS (SECCIÓN 9) ---
            assertNoForbiddenClaims(fullText);

            renderAllPagesToPng(pdDoc, visualDir, "fixture-1-normal-completa");
        }
    }

    @Test
    @DisplayName("Identidades: ENTRA_GRAPH, EXTERNAL_CONTACT, UNRESOLVED, actor sin nombre, timeline histórico y cuadrilla ausente")
    void testIdentityAndCrewVariants() throws IOException {
        // 1. EXTERNAL_CONTACT — nunca etiquetar como Entra ID
        var base = createCompleteFictitiousFixture();
        var externalOrg = new OrganizerIdentityDTO(
                "external:ext-001", "ext***@ejemplo.invalid", "Productora Externa Ficticia", null,
                "Productora Externa Ficticia", "EXTERNAL_CONTACT");
        var noActorNameTrace = new TechnicalTraceabilityDTO(
                base.traceability().rabbitmqCommandEventId(),
                base.traceability().kafkaClosingEventId(),
                base.traceability().traceId(),
                base.traceability().correlationId(),
                "00000000-0000-0000-0000-000000000001:99998888-7777-4666-8555-444433332222",
                null
        );
        var histTimeline = List.of(
                new OperationalTimelineItemDTO(1, null, "SOLICITADO", Instant.parse("2026-11-20T14:00:00Z"),
                        "00000000-0000-0000-0000-000000000001:99998888-7777-4666-8555-444433332222", "evt-hist-1", null)
        );
        var extDoc = new ProductionClosureDocumentDTO(
                base.metadata(), base.production(), externalOrg, histTimeline, base.equipment(), base.services(),
                null, base.administrativeDeclaration(), noActorNameTrace, base.conformity(),
                CatalogAvailabilityState.AVAILABLE, null
        );

        byte[] extBytes = generator.generatePdfBytes(extDoc, false);
        try (PDDocument pd = Loader.loadPDF(extBytes)) {
            String p1 = extractPageText(pd, 1);
            String p2 = extractPageText(pd, 2);
            assertTrue(p1.contains("Productora Externa Ficticia (Contacto externo registrado)"));
            assertFalse(p1.contains("Microsoft Graph"), "Contacto externo no debe mostrarse como Microsoft Graph");
            assertTrue(p1.contains("Usuario autenticado (referencia técnica en Anexo)"));
            assertTrue(p1.contains("Sin cuadrilla asignada ni ticket de montaje registrado"));
            assertTrue(p2.contains("oid:999988...2222"), "Evento histórico sin nombre debe conservar referencia técnica enmascarada");
            assertNoForbiddenClaims(p1 + "\n" + p2);
        }

        // 2. UNRESOLVED sin nombre ni contacto -> "Nombre del organizador no disponible"
        var unresolvedOrg = new OrganizerIdentityDTO(
                "00000000-0000-0000-0000-000000000001:deadbeef-0000-4000-8000-000000000000",
                null, null, null, null, "UNRESOLVED");
        var unresolvedDoc = new ProductionClosureDocumentDTO(
                base.metadata(), base.production(), unresolvedOrg, List.of(), null, null,
                null, base.administrativeDeclaration(), noActorNameTrace, base.conformity(),
                CatalogAvailabilityState.EMPTY, new CrewAssignmentDTO("crew-solo-id", "Cuadrilla Sin Ticket Ficticia")
        );
        byte[] unresBytes = generator.generatePdfBytes(unresolvedDoc, false);
        try (PDDocument pd = Loader.loadPDF(unresBytes)) {
            String p1 = extractPageText(pd, 1);
            String p2 = extractPageText(pd, 2);
            assertTrue(p1.contains("Nombre del organizador no disponible"));
            assertTrue(p1.contains("Cuadrilla Sin Ticket Ficticia"));
            assertTrue(p1.contains("crew-solo-id"));
            assertTrue(p1.contains("Sin ticket de montaje registrado"));
            assertFalse(p1.contains("deadbeef"), "No debe exponer UUID del organizador en página ejecutiva");
            assertTrue(p2.contains("UNRESOLVED"));
            assertNoForbiddenClaims(p1 + "\n" + p2);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "EMPTY, Sin reservas de equipamiento informadas",
            "UNAVAILABLE, No fue posible verificar el inventario",
            "UNAUTHORIZED, Catálogo no autorizó la consulta",
            "NOT_PROVIDED, consulta de Catálogo no realizada"
    })
    @DisplayName("Catálogo: renderizado preciso de los estados EMPTY, UNAVAILABLE, UNAUTHORIZED y NOT_PROVIDED")
    void testCatalogNonAvailableStates(String stateName, String expectedText) throws IOException {
        var base = createCompleteFictitiousFixture();
        var state = CatalogAvailabilityState.valueOf(stateName);
        var dto = new ProductionClosureDocumentDTO(
                base.metadata(), base.production(), base.organizer(), base.timeline(),
                state == CatalogAvailabilityState.EMPTY ? List.of() : null,
                base.services(), base.crewTicket(), base.administrativeDeclaration(),
                base.traceability(), base.conformity(), state, base.crewAssignment()
        );
        byte[] bytes = generator.generatePdfBytes(dto, false);
        try (PDDocument pd = Loader.loadPDF(bytes)) {
            String p1 = extractPageText(pd, 1);
            assertTrue(p1.contains(expectedText), "Debe mostrar el estado exacto de catálogo: " + stateName);
            if (state == CatalogAvailabilityState.UNAVAILABLE || state == CatalogAvailabilityState.UNAUTHORIZED) {
                assertTrue(p1.contains("Esto no confirma ausencia de reservas."));
            }
            assertNoForbiddenClaims(p1 + "\n" + extractPageText(pd, 2));
        }
    }

    @Test
    @DisplayName("Fixture Visual 2: Producción con información incompleta y nulos tolerados")
    void testVisualFixture2_incompleteProduction() throws IOException {
        var minimalDoc = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO("EMX-ACTA-PRD-202", ProductionClosureDocumentBuilder.DOCUMENT_TITLE, "2.0", Instant.parse("2026-11-23T00:00:00Z"), "ms-eventomax-notify"),
                new ProductionIdentityDTO(202L, "Producción Incompleta Ficticia (Prueba)", "CERRADO", null, "Recinto Ficticio Mínimo", null, null),
                new OrganizerIdentityDTO("00000000-0000-0000-0000-000000000001:bbbbbbbb-0000-4000-8000-000000000202", null, null, null, null, "UNRESOLVED"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CatalogAvailabilityState.UNAVAILABLE,
                null
        );

        Path pdfFile = visualDir.resolve("fixture-2-incompleta.pdf");
        generator.generatePdfFile(minimalDoc, pdfFile, false);
        byte[] bytes = Files.readAllBytes(pdfFile);

        try (PDDocument pd = Loader.loadPDF(bytes)) {
            assertEquals(2, pd.getNumberOfPages(), "Documento incompleto debe generar P1 Ejecutiva + P2 Anexo Técnico sin páginas vacías");
            String full = extractAllText(pd);
            assertTrue(full.contains("Nombre del organizador no disponible"));
            assertTrue(full.contains("No fue posible verificar el inventario"));
            assertTrue(full.contains("Sin eventos de bitácora provistos"));
            assertNoForbiddenClaims(full);
            renderAllPagesToPng(pd, visualDir, "fixture-2-incompleta");
        }
    }

    @Test
    @DisplayName("Fixture Visual 3: Nombre extenso (150 chars) y ubicación extensa (255 chars)")
    void testVisualFixture3_longNameAndLocation() throws IOException {
        String longName = "Congreso Internacional Ficticio de Ingeniería Cloud Native, Arquitectura Distribuida, Observabilidad y Producción Escénica de Alto Rendimiento Edición 2026";
        String longLocation = "Complejo Ficticio de Convenciones y Espectáculos Parque Metropolitano Bicentenario — Pabellón Principal Norte, Acceso Peatonal Avenida Costanera 4500, Sector Acreditación Técnica, Estacionamiento Subterráneo Nivel -2, Comuna de Vitacura, Región Metropolitana";
        assertTrue(longName.length() >= 150);
        assertTrue(longLocation.length() >= 250);

        var base = createCompleteFictitiousFixture();
        var prod = new ProductionIdentityDTO(
                303L, longName, "CERRADO",
                base.production().scheduledAt(), longLocation,
                base.production().createdAt(), base.production().closedAt(),
                base.production().closedAtFormatted()
        );
        var dto = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO("EMX-ACTA-PRD-303", ProductionClosureDocumentBuilder.DOCUMENT_TITLE, "2.0", base.metadata().generatedAt(), "ms-eventomax-notify"),
                prod, base.organizer(), base.timeline(), base.equipment(), base.services(),
                base.crewTicket(), base.administrativeDeclaration(), base.traceability(), base.conformity(),
                CatalogAvailabilityState.AVAILABLE, base.crewAssignment()
        );

        Path pdfFile = visualDir.resolve("fixture-3-textos-extensos.pdf");
        generator.generatePdfFile(dto, pdfFile, false);

        try (PDDocument pd = Loader.loadPDF(Files.readAllBytes(pdfFile))) {
            assertTrue(pd.getNumberOfPages() >= 2);
            String full = extractAllText(pd);
            assertTrue(full.contains("Alto Rendimiento Edición 2026"));
            assertTrue(full.contains("Región Metropolitana"));
            assertNoForbiddenClaims(full);
            renderAllPagesToPng(pd, visualDir, "fixture-3-textos-extensos");
        }
    }

    @Test
    @DisplayName("Fixture Visual 4: Stress test con 12 equipos, 8 servicios y 35 eventos en bitácora (paginación multipágina)")
    void testVisualFixture4_stressPagination() throws IOException {
        var base = createCompleteFictitiousFixture();

        List<ReservedEquipmentRecordDTO> eqList = new ArrayList<>();
        String[] statuses = {"RESERVED", "RELEASED", "COMPENSATED"};
        for (int i = 1; i <= 12; i++) {
            eqList.add(new ReservedEquipmentRecordDTO(
                    (long) i,
                    "Equipo Técnico Ficticio de Prueba N° " + i + " — Módulo Escénico",
                    i + 1,
                    statuses[(i - 1) % statuses.length],
                    500L + i
            ));
        }

        List<ContractedServiceRecordDTO> srvList = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            srvList.add(new ContractedServiceRecordDTO(
                    (long) i,
                    "Servicio de Producción Ficticio N° " + i + " (Prueba de Paginación)",
                    new BigDecimal(125000 * i + ".00"),
                    LocalDateTime.parse("2026-11-20T15:00:00").plusMinutes(i * 5L)
            ));
        }

        List<OperationalTimelineItemDTO> tlList = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            String from = i == 1 ? null : "ESTADO_" + String.format("%02d", i - 1);
            String to = i == 35 ? "CERRADO" : "ESTADO_" + String.format("%02d", i);
            String actorName = (i % 4 == 0) ? null : "Operador Ficticio " + ((i % 3) + 1);
            tlList.add(new OperationalTimelineItemDTO(
                    i, from, to,
                    Instant.parse("2026-11-20T14:00:00Z").plusSeconds(i * 600L),
                    "00000000-0000-0000-0000-000000000001:aaaa0000-0000-4000-8000-00000000" + String.format("%04d", i),
                    String.format("00000000-0000-4000-8000-%012d", i),
                    actorName
            ));
        }

        var dto = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO("EMX-ACTA-PRD-404", ProductionClosureDocumentBuilder.DOCUMENT_TITLE, "2.0", base.metadata().generatedAt(), "ms-eventomax-notify"),
                new ProductionIdentityDTO(404L, "Mega Festival Ficticio Stress Test (Datos de Prueba)", "CERRADO",
                        base.production().scheduledAt(), base.production().location(),
                        base.production().createdAt(), base.production().closedAt(), base.production().closedAtFormatted()),
                base.organizer(), tlList, eqList, srvList,
                base.crewTicket(), base.administrativeDeclaration(), base.traceability(), base.conformity(),
                CatalogAvailabilityState.AVAILABLE, base.crewAssignment()
        );

        Path pdfFile = visualDir.resolve("fixture-4-stress-paginacion.pdf");
        generator.generatePdfFile(dto, pdfFile, false);

        try (PDDocument pd = Loader.loadPDF(Files.readAllBytes(pdfFile))) {
            int pages = pd.getNumberOfPages();
            assertTrue(pages >= 3, "El stress test (12 equipos, 8 servicios, 35 eventos) debe paginar dinámicamente a 3+ páginas, real: " + pages);
            String full = extractAllText(pd);
            assertTrue(full.contains("Página 1 de " + pages));
            assertTrue(full.contains("Página " + pages + " de " + pages));
            assertTrue(full.contains("RES-512"));
            assertTrue(full.contains("Servicio de Producción Ficticio N° 8"));
            assertTrue(full.contains("00000000-0000-4000-8000-000000000035"));
            assertNoForbiddenClaims(full);
            renderAllPagesToPng(pd, visualDir, "fixture-4-stress-paginacion");
        }
    }

    @Test
    @DisplayName("Fixture Visual 5: Producción histórica con payload clásico de 7 campos")
    void testVisualFixture5_legacy7FieldsPayload() throws IOException {
        var builder = new ProductionClosureDocumentBuilder(null, null, Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC));
        var legacyPayload = new QuoteCommandPayload(
                505L,
                "org-legacy-ficticio-505",
                "Evento Histórico Ficticio 7 Campos (Datos de Prueba)",
                "CERRADO",
                "2026-12-01T20:00:00",
                "Teatro Ficticio Central — Valparaíso",
                "2026-11-15T19:30:00"
        );
        var envelope = new MessageEnvelope<>(
                "GenerateClosingQuote",
                "legacy-cmd-505",
                "2026-11-15T19:30:05Z",
                "legacy-trace-505",
                "legacy-corr-505",
                legacyPayload
        );

        ProductionClosureDocumentDTO doc = builder.build(envelope);
        Path pdfFile = visualDir.resolve("fixture-5-historica-7-campos.pdf");
        generator.generatePdfFile(doc, pdfFile, false);

        try (PDDocument pd = Loader.loadPDF(Files.readAllBytes(pdfFile))) {
            assertEquals(2, pd.getNumberOfPages(), "Payload histórico de 7 campos debe generar 2 páginas limpias (P1 + P2 Anexo)");
            String p1 = extractPageText(pd, 1);
            String p2 = extractPageText(pd, 2);
            assertTrue(p1.contains("Evento Histórico Ficticio 7 Campos"));
            assertTrue(p1.contains("Nombre del organizador no disponible"));
            assertTrue(p1.contains("consulta de Catálogo no realizada"));
            assertTrue(p1.contains("Conformidad no gestionada en esta versión"));
            assertTrue(p2.contains("legacy-cmd-505"));
            assertTrue(p2.contains("org-le...-505 (enmascarado)"));
            assertFalse(p2.contains("org-legacy-ficticio-505"), "Identificador técnico extenso debe enmascararse en el anexo");
            assertNoForbiddenClaims(p1 + "\n" + p2);
            renderAllPagesToPng(pd, visualDir, "fixture-5-historica-7-campos");
        }
    }

    @Test
    @DisplayName("Modo vista previa muestra franja condicional y modo producción la omite")
    void testPreviewModeBannerToggle() throws IOException {
        var fixture = createCompleteFictitiousFixture();

        byte[] previewBytes = generator.generatePdfBytes(fixture, true);
        try (PDDocument pd = Loader.loadPDF(previewBytes)) {
            String full = extractAllText(pd);
            assertTrue(full.contains("VISTA PREVIA — DATOS DE PRUEBA — NO VÁLIDO PARA CONFORMIDAD"));
            assertNoForbiddenClaims(full);
        }

        byte[] prodBytes = generator.generatePdfBytes(fixture, false);
        try (PDDocument pd = Loader.loadPDF(prodBytes)) {
            String full = extractAllText(pd);
            assertFalse(full.contains("VISTA PREVIA"));
            assertFalse(full.contains("NO VÁLIDO PARA CONFORMIDAD"));
            assertTrue(full.contains("ACTA DE CIERRE OPERATIVO"));
            assertNoForbiddenClaims(full);
        }
    }

    @Test
    @DisplayName("Procedencia de correo: usa etiqueta neutral cuando el comando trae correo sin procedencia verificable")
    void testEmailProvenanceNeutralLabel_doesNotAttributeCommandEmailToOrganizer() throws IOException {
        var base = createCompleteFictitiousFixture();
        // Caso realista: organizador UNRESOLVED o ENTRA_GRAPH sin contacto propio, pero quote.gen incluye correo por defecto enmascarado
        var orgWithDefaultEmail = new OrganizerIdentityDTO(
                "00000000-0000-0000-0000-000000000001:cccc0000-0000-4000-8000-000000000777",
                "de*******@ejemplo.invalid",
                null,
                null,
                null,
                "UNRESOLVED"
        );
        var dto = new ProductionClosureDocumentDTO(
                base.metadata(), base.production(), orgWithDefaultEmail, base.timeline(),
                base.equipment(), base.services(), base.crewTicket(), base.administrativeDeclaration(),
                base.traceability(), base.conformity(), CatalogAvailabilityState.AVAILABLE, base.crewAssignment()
        );

        byte[] pdfBytes = generator.generatePdfBytes(dto, false);
        try (PDDocument pd = Loader.loadPDF(pdfBytes)) {
            assertEquals(2, pd.getNumberOfPages());
            String page1 = extractPageText(pd, 1).replaceAll("\\s+", " ");
            String full = extractAllText(pd).replaceAll("\\s+", " ");

            assertTrue(page1.contains("Correo informado en el comando (enmascarado)"),
                    "Debe usar denominación neutral para el correo del comando");
            assertTrue(page1.contains("de*******@ejemplo.invalid"),
                    "Debe conservar exactamente el valor enmascarado sin alterarlo");
            assertFalse(full.contains("Correo de contacto"),
                    "No debe atribuir erróneamente el correo al organizador como 'Correo de contacto'");
            assertFalse(full.contains("Canal de contacto"),
                    "No debe atribuir erróneamente el correo al organizador como 'Canal de contacto'");
            assertNoForbiddenClaims(full);
        }
    }

    private void assertNoForbiddenClaims(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_CLAIMS_UPPER) {
            assertFalse(upper.contains(forbidden),
                    "El documento no debe contener la afirmación prohibida: " + forbidden);
        }
    }

    private String extractPageText(PDDocument pdDoc, int oneBasedPage) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(oneBasedPage);
        stripper.setEndPage(oneBasedPage);
        return stripper.getText(pdDoc);
    }

    private String extractAllText(PDDocument pdDoc) throws IOException {
        return new PDFTextStripper().getText(pdDoc);
    }

    private void renderAllPagesToPng(PDDocument pdDoc, Path dir, String prefix) throws IOException {
        PDFRenderer renderer = new PDFRenderer(pdDoc);
        for (int i = 0; i < pdDoc.getNumberOfPages(); i++) {
            BufferedImage image = renderer.renderImageWithDPI(i, 160f, ImageType.RGB);
            File pngFile = dir.resolve(prefix + "-p" + (i + 1) + ".png").toFile();
            ImageIO.write(image, "PNG", pngFile);
            assertTrue(pngFile.exists());
        }
    }
}
