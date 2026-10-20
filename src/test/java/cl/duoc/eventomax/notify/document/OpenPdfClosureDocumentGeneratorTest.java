package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO.*;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenPdfClosureDocumentGeneratorTest {

    private OpenPdfClosureDocumentGenerator generator;
    private Path outputDir;

    @BeforeEach
    void setUp() throws IOException {
        generator = new OpenPdfClosureDocumentGenerator();
        outputDir = Path.of("target", "test-output");
        Files.createDirectories(outputDir);
    }

    /**
     * Fixture de regresión controlado basado estrictamente en las evidencias reales de Producción #1
     * obtenidas en la auditoría rectificada EP2-FIX-PDF-01-A2.
     */
    private ProductionClosureDocumentDTO createProduction1RealFixture() {
        var metadata = new DocumentMetadataDTO(
                "EMX-ACTA-PRD-1",
                "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN",
                "1.0",
                Instant.parse("2026-10-19T22:57:22.951Z"),
                "ms-eventomax-notify"
        );

        var production = new ProductionIdentityDTO(
                1L,
                "EP2-DEMO Festival Tecnológico 2026",
                "CERRADO",
                LocalDateTime.parse("2026-11-20T18:00:00"),
                "Centro de Eventos EP2 — Santiago",
                LocalDateTime.parse("2026-10-19T12:38:29.901749"),
                LocalDateTime.parse("2026-10-19T19:57:22.361382")
        );

        // En BD: contact_name = null, phone = null, email = masked
        var organizer = new OrganizerIdentityDTO(
                "org-ep2-demo-001",
                "ig*********@gmail.com",
                null,
                null
        );

        var timeline = List.of(
                new OperationalTimelineItemDTO(1, null, "SOLICITADO", Instant.parse("2026-10-19T15:38:29.982053Z"), "f191bdc8-7926-4722-9ec0-cba663701940:c3057bf6-9e39-4fa4-b042-d3932d49ebc0", "d56e5b82-2cee-4d26-b767-4eadd3d04362"),
                new OperationalTimelineItemDTO(2, "SOLICITADO", "CONFIRMADO", Instant.parse("2026-10-19T16:46:53.257976Z"), "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", "7ce03628-eaf3-4241-bffe-f37634ac12c2"),
                new OperationalTimelineItemDTO(3, "CONFIRMADO", "EN_MONTAJE", Instant.parse("2026-10-19T22:05:26.097690Z"), "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", "f96cef4a-5091-4d06-8027-a8b1319c8804"),
                new OperationalTimelineItemDTO(4, "EN_MONTAJE", "EN_EJECUCION", Instant.parse("2026-10-19T22:34:03.274482Z"), "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", "22cf4618-3728-435c-a67b-e55517d268f6"),
                new OperationalTimelineItemDTO(5, "EN_EJECUCION", "CERRADO", Instant.parse("2026-10-19T22:57:22.366608Z"), "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", "274e40d9-cb8e-4205-93a7-c62605d298bd")
        );

        var equipment = List.of(
                new ReservedEquipmentRecordDTO(1L, "EP2-DEMO Generador Eléctrico 50kVA", 2, "RESERVED"),
                new ReservedEquipmentRecordDTO(2L, "EP2-DEMO Consola Digital 32 Canales", 3, "RESERVED")
        );

        // En BD: solo 1 servicio registrado con unit_price 175.00
        var services = List.of(
                new ContractedServiceRecordDTO(1L, "EP2-DEMO Iluminación Robótica", new BigDecimal("175.00"), LocalDateTime.parse("2026-10-19T13:46:53.249535"))
        );

        var crewTicket = new CrewTicketRecordDTO(
                1L,
                "33d5f659-8c07-44ec-b413-456abc5f0aa1",
                "EN_MONTAJE",
                "Hoja de ruta para montaje de equipamiento de la producción 'EP2-DEMO Festival Tecnológico 2026' en recinto: Centro de Eventos EP2 — Santiago. Estado operacional: EN_MONTAJE. Verificar dotación técnica y conexiones de energía/sonido.",
                LocalDateTime.parse("2026-10-19T19:05:26.854885")
        );

        var adminDecl = new AdministrativeDeclarationDTO(
                "La producción ha alcanzado su estado terminal CERRADO. Los recursos han sido consolidados administrativamente.",
                "Cierre administrativo registrado en plataforma con anterioridad a la fecha originalmente programada (20/11/2026). No acredita ejecución material anticipada.",
                "Documento emitido electrónicamente como registro interno de cierre. No constituye firma electrónica avanzada ni liquidación tributaria."
        );

        var trace = new TechnicalTraceabilityDTO(
                "678c5962-6568-4db9-9e1a-4a8fb7a68614",
                "274e40d9-cb8e-4205-93a7-c62605d298bd",
                "b3c914d3-dd99-4c6a-9429-50ee613d12b5",
                "b060074a-db80-40fc-ba21-a0dabd22e27a",
                "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds"
        );

        var conformity = new ConformityStatusDTO(
                "PENDIENTE DE CONFORMIDAD",
                "PENDIENTE DE CONFORMIDAD",
                false
        );

        return new ProductionClosureDocumentDTO(
                metadata, production, organizer, timeline, equipment, services,
                crewTicket, adminDecl, trace, conformity
        );
    }

    @Test
    @DisplayName("Generar acta de Producción #1 real en A4 y verificar texto y estructura")
    void testGeneratePdfProduction1Real() throws IOException {
        ProductionClosureDocumentDTO fixture = createProduction1RealFixture();
        Path previewFile = outputDir.resolve("acta-cierre-empresarial-preview.pdf");

        generator.generatePdfFile(fixture, previewFile, true);

        assertTrue(Files.exists(previewFile), "El archivo preview debe existir en target/test-output/");
        long fileSize = Files.size(previewFile);
        assertTrue(fileSize > 2000, "El tamaño del PDF debe ser significativo (>2KB), tamaño: " + fileSize);

        // Inspección de estructura con OpenPDF PdfReader
        byte[] pdfBytes = Files.readAllBytes(previewFile);
        PdfReader reader = new PdfReader(pdfBytes);

        // 1. Formato A4 (595x842 pt con margen de tolerancia)
        Rectangle pageSize = reader.getPageSize(1);
        assertEquals(PageSize.A4.getWidth(), pageSize.getWidth(), 1.0f, "El ancho debe ser A4 (595pt)");
        assertEquals(PageSize.A4.getHeight(), pageSize.getHeight(), 1.0f, "El alto debe ser A4 (842pt)");
        int totalPages = reader.getNumberOfPages();
        assertEquals(2, totalPages, "El acta empresarial estructurada de 7 secciones debe abarcar exactamente 2 páginas");
        reader.close();

        // 2. Extracción de texto con Apache PDFBox (interpreta CMaps ToUnicode y TrueType subsettings)
        try (PDDocument pdDoc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(pdDoc);

            // 3. Verificación rigurosa de caracteres Unicode (tildes, ñ, guion largo —)
            assertTrue(fullText.contains("Tecnológico"), "Debe preservar tildes en 'Tecnológico'");
            assertTrue(fullText.contains("Iluminación Robótica"), "Debe preservar tildes en 'Iluminación Robótica'");
            assertTrue(fullText.contains("Producción"), "Debe preservar 'Producción'");
            assertTrue(fullText.contains("Centro de Eventos EP2 — Santiago"), "Debe contener ubicación real y guion largo");

            // 4. Verificación de datos exactos de Producción #1
            assertTrue(fullText.contains("EP2-DEMO Festival Tecnológico 2026"));
            assertTrue(fullText.contains("org-ep2-demo-001"));
            assertTrue(fullText.contains("ig*********@gmail.com"));
            assertTrue(fullText.contains("CERRADO"));
            assertTrue(fullText.contains("EP2-DEMO Generador Eléctrico 50kVA"));
            assertTrue(fullText.contains("EP2-DEMO Consola Digital 32 Canales"));

            // 5. Verificación de ausencia estricta de moneda no registrada (CLP, USD, $)
            assertTrue(fullText.contains("175.00"), "Debe mostrar el valor numérico 175.00 de la tarifa");
            assertTrue(fullText.contains("Tarifa Base Registrada"), "Debe contener la etiqueta de tarifa base registrada");
            assertFalse(fullText.contains("CLP"), "No debe contener 'CLP' al no existir columna monetaria en base de datos");
            assertFalse(fullText.contains("USD"), "No debe contener 'USD'");
            assertFalse(fullText.contains("$"), "No debe anteponer ni contener símbolo monetario '$'");

            // 6. Verificación de Trazabilidad y UUIDs reales sin asociaciones W3C falsas
            assertTrue(fullText.contains("678c5962-6568-4db9-9e1a-4a8fb7a68614"), "Debe contener RabbitMQ command eventId");
            assertTrue(fullText.contains("274e40d9-cb8e-4205-93a7-c62605d298bd"), "Debe contener Kafka closing eventId");
            assertTrue(fullText.contains("b3c914d3-dd99-4c6a-9429-50ee613d12b5"), "Debe contener Trace ID");
            assertTrue(fullText.contains("Trace ID:"), "Debe etiquetar como 'Trace ID:'");
            assertFalse(fullText.contains("W3C Context"), "No debe etiquetar como W3C Context un UUID");
            assertTrue(fullText.contains("sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds"), "Debe contener el actor OID real");

            // 7. Verificación de formato limpio de historial y fechas UTC
            assertTrue(fullText.contains("SOLICITADO -> CONFIRMADO"), "Debe mostrar transición limpia de estados");
            assertTrue(fullText.contains("EN_EJECUCION -> CERRADO"), "Debe mostrar transición final a CERRADO");
            assertTrue(fullText.contains("UTC"), "Debe indicar zona UTC explícita en timestamps de eventos");

            // 8. Verificación de elementos de branding empresarial y vista previa condicionada
            assertTrue(fullText.contains("VISTA PREVIA — DATOS DE PRUEBA — NO VÁLIDO PARA CONFORMIDAD"),
                    "Debe contener franja de vista previa con leyenda completa en previewMode=true");
            assertFalse(fullText.contains("Final Certificada"), "No debe ostentar certificación final falsa");
            assertFalse(fullText.contains("Local Certificado (Auditoría E2E)"), "No debe declarar certificación local falsa");
            assertTrue(fullText.contains("Plataforma Cloud Native de Gestión y Producción de Eventos Masivos"), "Debe contener bajada de título");

            // 9. Verificación de recuadros de firma sin cargos ni identidades ficticias
            assertTrue(fullText.contains("RESPONSABLE OPERATIVO"), "Debe contener recuadro 'RESPONSABLE OPERATIVO'");
            assertTrue(fullText.contains("REPRESENTANTE DEL ORGANIZADOR"), "Debe contener recuadro 'REPRESENTANTE DEL ORGANIZADOR'");
            assertFalse(fullText.contains("Productor General / Jefe Técnico"), "No debe contener cargo no verificado");
            assertTrue(fullText.contains("PENDIENTE DE CONFORMIDAD"), "Debe contener estado 'PENDIENTE DE CONFORMIDAD'");

            // 10. Ausencia estricta de datos inventados
            assertFalse(fullText.contains("Catering"), "No debe contener Catering");
            assertFalse(fullText.contains("Seguridad y Control"), "No debe contener Seguridad");
            assertFalse(fullText.contains("430.000"), "No debe contener 430.000");
            assertFalse(fullText.contains("Ignacio Organizador"), "No debe contener nombre inventado de contacto");
            assertFalse(fullText.contains("8765 4321"), "No debe contener teléfono inventado");
            assertFalse(fullText.contains("Firmado digitalmente"), "No debe simular firma digital existente");

            // 11. Renderizar imágenes PNG de alta resolución de las páginas para la inspección visual
            PDFRenderer renderer = new PDFRenderer(pdDoc);
            for (int i = 0; i < totalPages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 150);
                File pngFile = new File(outputDir.toFile(), "preview-pagina-" + (i + 1) + ".png");
                ImageIO.write(image, "PNG", pngFile);
                assertTrue(pngFile.exists(), "La imagen renderizada de la página " + (i + 1) + " debe existir");
            }
        }
    }

    @Test
    @DisplayName("Tolerancia a campos opcionales nulos sin lanzar excepción")
    void testNullOptionalFieldsTolerance() {
        var minimalDoc = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO("CODE-TEST", "TITULO TEST", "1.0", Instant.now(), "notify"),
                new ProductionIdentityDTO(999L, "Producción Mínima", "CERRADO", null, "Recinto Mínimo", null, null),
                new OrganizerIdentityDTO("org-minimal", null, null, null),
                null, // timeline null
                null, // equipment null
                null, // services null
                null, // crewTicket null
                null, // adminDecl null
                null, // trace null
                null  // conformity null
        );

        byte[] bytes = assertDoesNotThrow(() -> generator.generatePdfBytes(minimalDoc));
        assertNotNull(bytes);
        assertTrue(bytes.length > 1000);

        PdfReader reader = assertDoesNotThrow(() -> new PdfReader(bytes));
        assertTrue(reader.getNumberOfPages() >= 1, "Debe tener al menos 1 página");
        reader.close();
    }

    @Test
    @DisplayName("Soporte de múltiples páginas con paginación real Página X de N")
    void testMultiPageDocumentPagination() throws IOException {
        var baseFixture = createProduction1RealFixture();

        // Creamos una lista extensa de timeline para forzar salto de página a página 3
        List<OperationalTimelineItemDTO> longTimeline = new ArrayList<>(baseFixture.timeline());
        for (int i = 6; i <= 35; i++) {
            longTimeline.add(new OperationalTimelineItemDTO(
                    i,
                    "ESTADO_" + (i - 1),
                    "ESTADO_" + i,
                    Instant.parse("2026-10-19T22:00:00Z"),
                    "actor-test-sequence-" + i,
                    "event-uuid-test-" + i
            ));
        }

        var multiPageDoc = new ProductionClosureDocumentDTO(
                baseFixture.metadata(),
                baseFixture.production(),
                baseFixture.organizer(),
                longTimeline,
                baseFixture.equipment(),
                baseFixture.services(),
                baseFixture.crewTicket(),
                baseFixture.administrativeDeclaration(),
                baseFixture.traceability(),
                baseFixture.conformity()
        );

        Path multiPageFile = outputDir.resolve("acta-cierre-multipage-test.pdf");
        generator.generatePdfFile(multiPageDoc, multiPageFile);

        PdfReader reader = new PdfReader(Files.readAllBytes(multiPageFile));
        int pages = reader.getNumberOfPages();
        assertTrue(pages >= 3, "El documento con 35 transiciones debe abarcar al menos 3 páginas, páginas reales: " + pages);
        reader.close();
    }

    @Test
    @DisplayName("Modo de producción sin franja de vista previa cuando previewMode es false")
    void testProductionWithoutPreviewBanner() throws IOException {
        var fixture = createProduction1RealFixture();
        byte[] productionPdfBytes = generator.generatePdfBytes(fixture, false);

        try (PDDocument pdDoc = Loader.loadPDF(productionPdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(pdDoc);

            assertFalse(fullText.contains("VISTA PREVIA"),
                    "En modo producción real (previewMode=false) no debe imprimirse ninguna franja de vista previa");
            assertFalse(fullText.contains("NO VÁLIDO PARA CONFORMIDAD"),
                    "En modo producción real no debe imprimirse la cláusula de vista previa");
            assertTrue(fullText.contains("ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO"),
                    "Debe conservar el título institucional del documento");
        }
    }
}

