package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO.*;
import com.lowagie.text.pdf.PdfReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateRealClosureDocumentsTest {

    private final OpenPdfClosureDocumentGenerator generator = new OpenPdfClosureDocumentGenerator();

    private final Path quotesDir = Path.of("target", "test-output", "realistic-fixtures", "quotes").toAbsolutePath().normalize();
    private final Path nestedQuotesDir = quotesDir.resolve("quotes");
    private final Path artifactDir = Path.of("target", "test-output", "realistic-fixtures", "previews").toAbsolutePath().normalize();

    private static final AdministrativeDeclarationDTO COMMON_ADMIN_DECL = new AdministrativeDeclarationDTO(
            "La producción ha alcanzado su estado terminal CERRADO. Los recursos han sido consolidados administrativamente.",
            "El cierre administrativo y documental fue efectuado con anterioridad a la fecha programada del evento por decisión operacional y logística registrada en el sistema.",
            "El presente documento constituye un registro interno de carácter estrictamente operativo y de control de inventario. No constituye factura, boleta, comprobante de pago ni instrumento tributario conforme a la normativa legal vigente."
    );

    @Test
    @DisplayName("Generar Actas de Cierre PDF 100% Reales para Producciones 1, 2 y 4")
    void generateAllRealisticClosingDocuments() throws IOException {
        Files.createDirectories(quotesDir);
        Files.createDirectories(nestedQuotesDir);
        Files.createDirectories(artifactDir);

        // =====================================================================
        // PRODUCCIÓN 1: Festival Tecnológico del Biobío 2026
        // =====================================================================
        var doc1 = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO(
                        "EMX-ACTA-PRD-1",
                        "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN",
                        "2.0",
                        Instant.parse("2026-10-19T22:57:22Z"),
                        "ms-eventomax-notify"
                ),
                new ProductionIdentityDTO(
                        1L,
                        "Festival Tecnológico del Biobío 2026",
                        "CERRADO",
                        LocalDateTime.parse("2026-11-20T18:00:00"),
                        "Centro de Eventos SurActivo — Concepción",
                        LocalDateTime.parse("2026-10-19T12:38:29"),
                        LocalDateTime.parse("2026-10-19T19:57:22"),
                        "2026-10-19 22:57:22 UTC"
                ),
                new OrganizerIdentityDTO(
                        "org-innovacion-biobio",
                        "ig*********@duocuc.cl",
                        "Camila Valenzuela Ríos",
                        "+56987654321"
                ),
                List.of(
                        new OperationalTimelineItemDTO(1, null, "SOLICITADO", Instant.parse("2026-10-19T15:38:29.982053Z"), "org-innovacion-biobio", "d56e5b82-2cee-4d26-b767-4eadd3d04362"),
                        new OperationalTimelineItemDTO(2, "SOLICITADO", "CONFIRMADO", Instant.parse("2026-10-19T16:46:53.257976Z"), "admin.operaciones", "7ce03628-eaf3-4241-bffe-f37634ac12c2"),
                        new OperationalTimelineItemDTO(3, "CONFIRMADO", "EN_MONTAJE", Instant.parse("2026-10-19T22:05:26.097690Z"), "admin.operaciones", "f96cef4a-5091-4d06-8027-a8b1319c8804"),
                        new OperationalTimelineItemDTO(4, "EN_MONTAJE", "EN_EJECUCION", Instant.parse("2026-10-19T22:34:03.274482Z"), "admin.operaciones", "22cf4618-3728-435c-a67b-e55517d268f6"),
                        new OperationalTimelineItemDTO(5, "EN_EJECUCION", "CERRADO", Instant.parse("2026-10-19T22:57:22.366608Z"), "admin.operaciones", "274e40d9-cb8e-4205-93a7-c62605d298bd")
                ),
                List.of(
                        new ReservedEquipmentRecordDTO(1L, "Consola Digital DiGiCo Quantum 338", 1, "RESERVED"),
                        new ReservedEquipmentRecordDTO(2L, "Arreglo Lineal Acústico Meyer Sound LYON-M", 8, "RESERVED"),
                        new ReservedEquipmentRecordDTO(3L, "Subwoofer de Gran Potencia Meyer Sound 1100-LFC", 4, "RESERVED"),
                        new ReservedEquipmentRecordDTO(6L, "Panel LED Modular Unilumin Utile P3.91 (50x50cm)", 24, "RESERVED"),
                        new ReservedEquipmentRecordDTO(5L, "Generador Diésel Insonorizado Cummins 150 kVA", 1, "RESERVED")
                ),
                List.of(
                        new ContractedServiceRecordDTO(1L, "Refuerzo Sonoro FOH & Monitores para Concierto", new BigDecimal("1850000.00"), LocalDateTime.parse("2026-10-19T13:46:53")),
                        new ContractedServiceRecordDTO(4L, "Sistema de Pantalla LED Modular P3.91 Outdoor (6x3.5m)", new BigDecimal("1650000.00"), LocalDateTime.parse("2026-10-19T14:10:00")),
                        new ContractedServiceRecordDTO(5L, "Generación Eléctrica Continua 150 kVA y Cuadrilla SEC", new BigDecimal("850000.00"), LocalDateTime.parse("2026-10-19T14:15:00"))
                ),
                new CrewTicketRecordDTO(
                        11L,
                        "a1e041d8-7911-4b10-8b01-5278cba91001",
                        "CERRADO",
                        "Hoja de ruta para montaje de equipamiento de la producción 'Festival Tecnológico del Biobío 2026' en recinto: Centro de Eventos SurActivo — Concepción. Estado operacional: CERRADO. Cuadrilla asignada: Cuadrilla Técnica Biobío (Concepción Sur) (crew-biobio-sur). Protocolos SEC y EPP vigentes.",
                        LocalDateTime.parse("2026-10-19T19:05:26")
                ),
                COMMON_ADMIN_DECL,
                new TechnicalTraceabilityDTO(
                        "678c5962-6568-4db9-9e1a-4a8fb7a68614",
                        "274e40d9-cb8e-4205-93a7-c62605d298bd",
                        "b3c914d3-dd99-4c6a-9429-50ee613d12b5",
                        "b060074a-db80-40fc-ba21-a0dabd22e27a",
                        "admin.operaciones"
                ),
                new ConformityStatusDTO("AUTENTICADO VÍA ENTRA ID", "REGISTRADO Y NOTIFICADO", true),
                CatalogAvailabilityState.AVAILABLE
        );

        savePdfAndArtifact(doc1, 1L);

        // =====================================================================
        // PRODUCCIÓN 2: Festival Internacional de Jazz de Concepción
        // =====================================================================
        var doc2 = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO(
                        "EMX-ACTA-PRD-2",
                        "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN",
                        "2.0",
                        Instant.parse("2026-11-05T22:19:37Z"),
                        "ms-eventomax-notify"
                ),
                new ProductionIdentityDTO(
                        2L,
                        "Festival Internacional de Jazz de Concepción",
                        "CERRADO",
                        LocalDateTime.parse("2026-12-05T19:30:00"),
                        "Parque Bicentenario, Concepción",
                        LocalDateTime.parse("2026-10-25T09:00:00"),
                        LocalDateTime.parse("2026-11-05T22:19:37"),
                        "2026-11-05 22:19:37 UTC"
                ),
                new OrganizerIdentityDTO(
                        "org-cultura-concepcion",
                        "ig*********@duocuc.cl",
                        "Rodrigo Astudillo Peña",
                        "+56976543210"
                ),
                List.of(
                        new OperationalTimelineItemDTO(1, null, "SOLICITADO", Instant.parse("2026-10-25T12:00:00Z"), "org-cultura-concepcion", "f1234567-89ab-cdef-0123-456789abcdef"),
                        new OperationalTimelineItemDTO(2, "SOLICITADO", "CONFIRMADO", Instant.parse("2026-10-25T15:30:00Z"), "admin.operaciones", "a2345678-9abc-def0-1234-56789abcdef0"),
                        new OperationalTimelineItemDTO(3, "CONFIRMADO", "EN_MONTAJE", Instant.parse("2026-11-05T18:00:00Z"), "admin.operaciones", "b3456789-abcd-ef01-2345-6789abcdef01"),
                        new OperationalTimelineItemDTO(4, "EN_MONTAJE", "EN_EJECUCION", Instant.parse("2026-11-05T21:00:00Z"), "admin.operaciones", "c456789a-bcde-f012-3456-789abcdef012"),
                        new OperationalTimelineItemDTO(5, "EN_EJECUCION", "CERRADO", Instant.parse("2026-11-05T22:19:37Z"), "admin.operaciones", "d56789ab-cdef-0123-4567-89abcdef0123")
                ),
                List.of(
                        new ReservedEquipmentRecordDTO(1L, "Consola Digital DiGiCo Quantum 338", 1, "RESERVED"),
                        new ReservedEquipmentRecordDTO(2L, "Arreglo Lineal Acústico Meyer Sound LYON-M", 12, "RESERVED"),
                        new ReservedEquipmentRecordDTO(3L, "Subwoofer de Gran Potencia Meyer Sound 1100-LFC", 6, "RESERVED"),
                        new ReservedEquipmentRecordDTO(4L, "Cabezal Móvil Robótico Robe MegaPointe 470W", 8, "RESERVED")
                ),
                List.of(
                        new ContractedServiceRecordDTO(7L, "Sonido Line Array Meyer Sound LEO / LYON Principal", new BigDecimal("2100000.00"), LocalDateTime.parse("2026-10-25T10:30:00")),
                        new ContractedServiceRecordDTO(2L, "Sistema de Iluminación Robótica Escénica DMX", new BigDecimal("1250000.00"), LocalDateTime.parse("2026-10-25T10:35:00"))
                ),
                new CrewTicketRecordDTO(
                        12L,
                        "b2f152e9-8922-4c21-9c12-6389dcb02002",
                        "CERRADO",
                        "Hoja de ruta para montaje de equipamiento de la producción 'Festival Internacional de Jazz de Concepción' en recinto: Parque Bicentenario, Concepción. Estado operacional: CERRADO. Cuadrilla asignada: Cuadrilla Acústica & FOH Biobío (Concepción) (crew-jazz-biobio). Calibración electroacústica y certificación SEC.",
                        LocalDateTime.parse("2026-10-25T10:45:00")
                ),
                COMMON_ADMIN_DECL,
                new TechnicalTraceabilityDTO(
                        "fdc4c809-11a8-4d84-b43d-cbd084cf8275",
                        "d56789ab-cdef-0123-4567-89abcdef0123",
                        "e1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c",
                        "f2b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d",
                        "admin.operaciones"
                ),
                new ConformityStatusDTO("AUTENTICADO VÍA ENTRA ID", "REGISTRADO Y NOTIFICADO", true),
                CatalogAvailabilityState.AVAILABLE
        );

        savePdfAndArtifact(doc2, 2L);

        // =====================================================================
        // PRODUCCIÓN 4: Concierto Filarmónico de Fin de Año
        // =====================================================================
        var doc4 = new ProductionClosureDocumentDTO(
                new DocumentMetadataDTO(
                        "EMX-ACTA-PRD-4",
                        "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN",
                        "2.0",
                        Instant.parse("2026-11-05T22:22:08Z"),
                        "ms-eventomax-notify"
                ),
                new ProductionIdentityDTO(
                        4L,
                        "Concierto Filarmónico de Fin de Año",
                        "CERRADO",
                        LocalDateTime.parse("2026-12-28T20:30:00"),
                        "Quinta Vergara, Viña del Mar",
                        LocalDateTime.parse("2026-11-02T10:00:00"),
                        LocalDateTime.parse("2026-11-05T22:22:07"),
                        "2026-11-05 22:22:07 UTC"
                ),
                new OrganizerIdentityDTO(
                        "org-fundacion-filarmonica",
                        "ig*********@duocuc.cl",
                        "Elena Morales Chadwick",
                        "+56954321098"
                ),
                List.of(
                        new OperationalTimelineItemDTO(1, null, "SOLICITADO", Instant.parse("2026-11-02T10:00:00Z"), "org-fundacion-filarmonica", "e6789abc-def0-1234-5678-9abcdef01234"),
                        new OperationalTimelineItemDTO(2, "SOLICITADO", "CONFIRMADO", Instant.parse("2026-11-02T11:00:00Z"), "admin.operaciones", "f789abcd-ef01-2345-6789-abcdef012345"),
                        new OperationalTimelineItemDTO(3, "CONFIRMADO", "EN_MONTAJE", Instant.parse("2026-11-05T19:00:00Z"), "admin.operaciones", "089abcde-f012-3456-789a-bcdef0123456"),
                        new OperationalTimelineItemDTO(4, "EN_MONTAJE", "EN_EJECUCION", Instant.parse("2026-11-05T21:30:00Z"), "admin.operaciones", "19abcdef-0123-4567-89ab-cdef01234567"),
                        new OperationalTimelineItemDTO(5, "EN_EJECUCION", "CERRADO", Instant.parse("2026-11-05T22:22:07Z"), "admin.operaciones", "26f6302f-491e-480a-bff3-a62fe1431234")
                ),
                List.of(
                        new ReservedEquipmentRecordDTO(1L, "Consola Digital DiGiCo Quantum 338", 2, "RESERVED"),
                        new ReservedEquipmentRecordDTO(2L, "Arreglo Lineal Acústico Meyer Sound LYON-M", 16, "RESERVED"),
                        new ReservedEquipmentRecordDTO(3L, "Subwoofer de Gran Potencia Meyer Sound 1100-LFC", 8, "RESERVED"),
                        new ReservedEquipmentRecordDTO(7L, "Consola de Iluminación GrandMA3 Full-Size", 1, "RESERVED")
                ),
                List.of(
                        new ContractedServiceRecordDTO(7L, "Sonido Line Array Meyer Sound LEO / LYON Principal", new BigDecimal("2100000.00"), LocalDateTime.parse("2026-11-02T11:00:00")),
                        new ContractedServiceRecordDTO(2L, "Sistema de Iluminación Robótica Escénica DMX", new BigDecimal("1250000.00"), LocalDateTime.parse("2026-11-02T11:05:00")),
                        new ContractedServiceRecordDTO(6L, "Circuito Cerrado de Televisión (CCTV) y Streaming 4K", new BigDecimal("950000.00"), LocalDateTime.parse("2026-11-02T11:10:00"))
                ),
                new CrewTicketRecordDTO(
                        14L,
                        "d4b374ab-ab44-4e43-be34-8501fed24004",
                        "CERRADO",
                        "Hoja de ruta para montaje de equipamiento de la producción 'Concierto Filarmónico de Fin de Año' en recinto: Quinta Vergara, Viña del Mar. Estado operacional: CERRADO. Cuadrilla asignada: Cuadrilla Costa Gamma (Valparaíso & Viña) (crew-costa-gamma). Montaje de microfonía de alta sensibilidad y control acústico de escenario.",
                        LocalDateTime.parse("2026-11-02T11:30:00")
                ),
                COMMON_ADMIN_DECL,
                new TechnicalTraceabilityDTO(
                        "a81b15cb-f425-40f1-babc-66e5ddf9c902",
                        "26f6302f-491e-480a-bff3-a62fe1431234",
                        "b92c26dc-0536-41f2-cbcd-77f6ee0ad013",
                        "c03d37ed-1647-4203-dcee-8807ff1be124",
                        "admin.operaciones"
                ),
                new ConformityStatusDTO("AUTENTICADO VÍA ENTRA ID", "REGISTRADO Y NOTIFICADO", true),
                CatalogAvailabilityState.AVAILABLE
        );

        savePdfAndArtifact(doc4, 4L);
    }

    private void savePdfAndArtifact(ProductionClosureDocumentDTO doc, Long prodId) throws IOException {
        byte[] pdfBytes = generator.generatePdfBytes(doc, false);

        Path dest1 = quotesDir.resolve("quote-production-" + prodId + "-latest.pdf");
        Path dest2 = nestedQuotesDir.resolve("quote-production-" + prodId + "-latest.pdf");
        Files.write(dest1, pdfBytes);
        Files.write(dest2, pdfBytes);

        // Validar que se ajusta a 1 página A4
        PdfReader reader = new PdfReader(pdfBytes);
        assertEquals(1, reader.getNumberOfPages(), "El acta de cierre de producción #" + prodId + " debe ajustarse a 1 página A4");
        reader.close();

        // Renderizar PNG para verificación visual en artifacts
        try (PDDocument pdDoc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdDoc);
            BufferedImage image = renderer.renderImageWithDPI(0, 200f, ImageType.RGB);
            File pngArtifact = artifactDir.resolve("acta_cierre_prod_" + prodId + "_realistic.png").toFile();
            ImageIO.write(image, "PNG", pngArtifact);
            assertTrue(pngArtifact.exists());
        }
    }
}
