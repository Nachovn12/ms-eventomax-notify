package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO.*;
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
import java.util.regex.Pattern;

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

    private ProductionClosureDocumentDTO createProduction1RealFixture() {
        var metadata = new DocumentMetadataDTO(
                "EMX-ACTA-PRD-1",
                "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN",
                "2.0",
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
                LocalDateTime.parse("2026-10-19T19:57:22.361382"),
                "2026-10-19 22:57:22 UTC"
        );

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
                "AUTENTICADO VÍA ENTRA ID",
                "REGISTRADO Y NOTIFICADO",
                false
        );

        return new ProductionClosureDocumentDTO(
                metadata, production, organizer, timeline, equipment, services,
                crewTicket, adminDecl, trace, conformity
        );
    }

    @Test
    @DisplayName("Generar acta ejecutiva consolidada en 1 página A4 con campos Caso 8, Firma Electrónica Dual y Timbre PDF417")
    void testGeneratePdfProduction1Real() throws IOException {
        ProductionClosureDocumentDTO fixture = createProduction1RealFixture();
        Path previewFile = outputDir.resolve("acta-cierre-empresarial-preview.pdf");

        generator.generatePdfFile(fixture, previewFile, true);

        assertTrue(Files.exists(previewFile), "El archivo preview debe existir en target/test-output/");
        long fileSize = Files.size(previewFile);
        assertTrue(fileSize > 5000, "El tamaño del PDF empresarial con fuentes y timbre vectorial debe superar 5KB: " + fileSize);

        byte[] pdfBytes = Files.readAllBytes(previewFile);
        PdfReader reader = new PdfReader(pdfBytes);

        Rectangle pageSize = reader.getPageSize(1);
        assertEquals(PageSize.A4.getWidth(), pageSize.getWidth(), 1.0f, "El ancho debe ser A4 (595pt)");
        assertEquals(PageSize.A4.getHeight(), pageSize.getHeight(), 1.0f, "El alto debe ser A4 (842pt)");
        int totalPages = reader.getNumberOfPages();
        assertEquals(1, totalPages, "El acta consolidada ejecutiva para una producción estándar debe ajustarse limpiamente en 1 página A4");
        reader.close();

        try (PDDocument pdDoc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(pdDoc);

            // 1. Caracteres Unicode reales (tildes, ñ, guion largo —)
            assertTrue(fullText.contains("Tecnológico"), "Debe preservar tildes en 'Tecnológico'");
            assertTrue(fullText.contains("Iluminación Robótica"), "Debe preservar tildes en 'Iluminación Robótica'");
            assertTrue(fullText.contains("Producción"), "Debe preservar 'Producción'");
            assertTrue(fullText.contains("Centro de Eventos EP2 — Santiago"), "Debe contener ubicación real y guion largo");

            // 2. Alcance estricto Caso 8: sin campos de relleno inexistentes
            assertFalse(fullText.contains("Nombre Contacto:"), "No debe imprimir campos fuera del alcance de Caso 8");
            assertFalse(fullText.contains("Teléfono Contacto:"), "No debe imprimir teléfono no contemplado en Caso 8");
            assertFalse(fullText.contains("No registrado"), "No debe imprimir filas de relleno 'No registrado'");

            // 3. Datos reales de Producción, Catálogo, Servicios y Cuadrilla
            assertTrue(fullText.contains("FOLIO N° 000001"), "Debe incluir recuadro normativo de Folio");
            assertTrue(fullText.contains("EMX-ACTA-PRD-1"));
            assertTrue(fullText.contains("EP2-DEMO Festival Tecnológico 2026"));
            assertTrue(fullText.contains("org-ep2-demo-001"));
            assertTrue(fullText.contains("ig*********@gmail.com"));
            assertTrue(fullText.contains("CERRADO"));
            assertTrue(fullText.contains("EP2-DEMO Generador Eléctrico 50kVA"));
            assertTrue(fullText.contains("EP2-DEMO Consola Digital 32 Canales"));
            assertTrue(fullText.contains("33d5f659-8c07-44ec-b413-456abc5f0aa1"));

            // 4. Ausencia estricta de moneda inventada (CLP, USD, $) y presencia de total numérico
            assertTrue(fullText.contains("175.00"), "Debe mostrar el valor numérico exacto 175.00");
            assertTrue(fullText.contains("TOTAL TARIFA BASE REGISTRADA"), "Debe incluir fila resumen de tarifa base");
            assertFalse(fullText.contains("CLP"), "No debe inventar moneda CLP");
            assertFalse(fullText.contains("USD"), "No debe inventar moneda USD");
            assertFalse(fullText.contains("$"), "No debe anteponer símbolo monetario '$'");

            // 5. Firma Electrónica Dual para ambos Stakeholders + Timbre PDF417 + SHA-256 + CSV + Ley 19.799
            assertTrue(fullText.contains("RESPONSABLE OPERATIVO"), "Debe contener certificado de Stakeholder 1 (Responsable Operativo)");
            assertTrue(fullText.contains("REPRESENTANTE DEL ORGANIZADOR"), "Debe contener certificado de Stakeholder 2 (Organizador Mandante)");
            assertTrue(fullText.contains("sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds"), "Debe acreditar el actor OID real de cierre");
            assertTrue(fullText.contains("TIMBRE ELECTRÓNICO"), "Debe incluir leyenda del Timbre Electrónico PDF417");
            assertTrue(fullText.contains("PDF417"), "Debe especificar estándar PDF417");
            assertTrue(fullText.contains("Ley N° 19.799"), "Debe referenciar marco normativo Ley N° 19.799 sin efectos tributarios");
            assertTrue(Pattern.compile("EMX-000001-[0-9A-F]{4}-[0-9A-F]{4}").matcher(fullText).find(),
                    "Debe generar Código Seguro de Verificación (CSV) determinístico");
            assertTrue(Pattern.compile("[0-9a-f]{64}").matcher(fullText).find(),
                    "Debe incluir huella digital SHA-256 hexadecimal completa de 64 caracteres");

            // 6. Trazabilidad técnica completa íntegra
            assertTrue(fullText.contains("678c5962-6568-4db9-9e1a-4a8fb7a68614"), "Debe contener RabbitMQ command eventId");
            assertTrue(fullText.contains("274e40d9-cb8e-4205-93a7-c62605d298bd"), "Debe contener Kafka closing eventId");
            assertTrue(fullText.contains("b3c914d3-dd99-4c6a-9429-50ee613d12b5"), "Debe contener Trace ID completo");
            assertTrue(fullText.contains("b060074a-db80-40fc-ba21-a0dabd22e27a"), "Debe contener Correlation ID completo");

            // 7. Franja condicional de vista previa cuando previewMode=true
            assertTrue(fullText.contains("VISTA PREVIA — DATOS DE PRUEBA — NO VÁLIDO PARA CONFORMIDAD"));

            // 8. Renderizado visual de control a PNG
            PDFRenderer renderer = new PDFRenderer(pdDoc);
            for (int i = 0; i < totalPages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 200f, ImageType.RGB);
                File pngFile = new File(outputDir.toFile(), "preview-pagina-" + (i + 1) + ".png");
                ImageIO.write(image, "PNG", pngFile);
                assertTrue(pngFile.exists());
            }
        }
    }

    @Test
    @DisplayName("Tolerancia a campos opcionales nulos sin lanzar excepción y en 1 página")
    void testNullOptionalFieldsTolerance() {
        var minimalDoc = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO("CODE-TEST", "TITULO TEST", "2.0", Instant.now(), "notify"),
                new ProductionIdentityDTO(999L, "Producción Mínima", "CERRADO", null, "Recinto Mínimo", null, null),
                new OrganizerIdentityDTO("org-minimal", null, null, null),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        byte[] bytes = assertDoesNotThrow(() -> generator.generatePdfBytes(minimalDoc));
        assertNotNull(bytes);
        assertTrue(bytes.length > 2000);

        PdfReader reader = assertDoesNotThrow(() -> new PdfReader(bytes));
        assertEquals(1, reader.getNumberOfPages(), "Documento mínimo debe ocupar exactamente 1 página");
        reader.close();
    }

    @Test
    @DisplayName("Soporte de múltiples páginas con paginación real Página X de N cuando hay decenas de eventos")
    void testMultiPageDocumentPagination() throws IOException {
        var baseFixture = createProduction1RealFixture();

        List<OperationalTimelineItemDTO> longTimeline = new ArrayList<>(baseFixture.timeline());
        for (int i = 6; i <= 45; i++) {
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
        assertTrue(pages >= 2, "El documento con 45 transiciones debe paginar dinámicamente a 2+ páginas, real: " + pages);
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

            assertFalse(fullText.contains("VISTA PREVIA"));
            assertFalse(fullText.contains("NO VÁLIDO PARA CONFORMIDAD"));
            assertTrue(fullText.contains("ACTA DE CIERRE OPERATIVO"));
        }
    }
}
