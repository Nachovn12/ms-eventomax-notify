package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;

import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generador empresarial de Actas de Cierre Operativo y Administrativo de Producción
 * implementado con OpenPDF 1.3.40.
 * <p>
 * Diseñado bajo estándares corporativos y editoriales EventoMax (Revisión REV02):
 * - Formato A4 profesional con distribución visual y semántica equilibrada en 2 páginas para el ciclo estándar.
 * - Soporte multirregistro escalable a N páginas para producciones con amplio historial.
 * - Branding corporativo oficial: Logotipo institucional destacado en Página 1 e Isotipo/Símbolo en cabecera de Página 2+.
 * - Tipografía Unicode TrueType incrustada (Roboto Regular y Bold) con soporte completo de tildes y guiones.
 * - Paginación dinámica "Página X de N" mediante template de dos fases en PdfPageEventHelper.
 * - Indicador de vista previa condicional ("VISTA PREVIA — DATOS DE PRUEBA — NO VÁLIDO PARA CONFORMIDAD") parametrizable por contexto.
 * - Rigor semántico y forense:
 *   * Sin sufijo monetario ficticio ("CLP") cuando no existe columna de moneda en base de datos.
 *   * Sin expresiones de certificación falsa ("Final Certificada", "Certificación Local Duoc UC").
 *   * Etiqueta técnica "Trace ID" neutral y fidedigna (sin asociar erróneamente formato UUID a W3C).
 *   * Fechas Instant formateadas en UTC explícito y LocalDateTime en formato estándar sin conversiones ficticias.
 *   * Firmas con espacios amplios de firma manual real, nombres y cargos vacíos y estado PENDIENTE DE CONFORMIDAD.
 */
@Component
public class OpenPdfClosureDocumentGenerator {

    private static final DateTimeFormatter UTC_DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Paleta Corporativa Oficial EventoMax
    private static final Color PRIMARY_NAVY = new Color(3, 28, 67);       // #031C43 (Dark Navy oficial)
    private static final Color PRIMARY_BLUE = new Color(2, 79, 252);      // #024FFC (Electric Blue oficial)
    private static final Color ACCENT_BLUE = new Color(37, 99, 235);      // #2563EB
    private static final Color BORDER_GRAY = new Color(203, 213, 225);    // #CBD5E1 (Slate 300)
    private static final Color BG_LIGHT_GRAY = new Color(248, 250, 252);  // #F8FAFC (Slate 50)
    private static final Color BG_TABLE_HEADER = new Color(241, 245, 249);// #F1F5F9 (Slate 100)
    private static final Color TEXT_DARK = new Color(15, 23, 42);         // #0F172A (Slate 900)
    private static final Color TEXT_MUTED = new Color(100, 116, 139);     // #64748B (Slate 500)
    private static final Color BADGE_GREEN_TEXT = new Color(6, 95, 70);   // #065F46
    private static final Color BADGE_GREEN_BG = new Color(209, 250, 229); // #D1FAE5
    private static final Color PREVIEW_BG = new Color(239, 246, 255);     // #EFF6FF (Blue 50)
    private static final Color PREVIEW_BORDER = new Color(191, 219, 254); // #BFDBFE (Blue 200)
    private static final Color PREVIEW_TEXT = new Color(30, 64, 175);     // #1E40AF (Blue 800)

    private final BaseFont regularBaseFont;
    private final BaseFont boldBaseFont;
    private final byte[] logoBytes;
    private final byte[] symbolBytes;

    public OpenPdfClosureDocumentGenerator() {
        this.regularBaseFont = loadTrueTypeFont("/fonts/Roboto-Regular.ttf");
        this.boldBaseFont = loadTrueTypeFont("/fonts/Roboto-Bold.ttf");
        this.logoBytes = loadResourceBytes("/branding/eventomax-logo-dark.png");
        this.symbolBytes = loadResourceBytes("/branding/eventomax-symbol-dark.png");
    }

    private BaseFont loadTrueTypeFont(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] fontBytes = is.readAllBytes();
                String fontName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                return BaseFont.createFont(fontName, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
            }
        } catch (Exception ignored) {
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo inicializar la fuente tipográfica base", e);
        }
    }

    private byte[] loadResourceBytes(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public byte[] generatePdfBytes(ProductionClosureDocumentDTO doc) {
        return generatePdfBytes(doc, false);
    }

    public byte[] generatePdfBytes(ProductionClosureDocumentDTO doc, boolean previewMode) {
        if (doc == null) {
            throw new IllegalArgumentException("El documento DTO no puede ser nulo");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        // Márgenes: Izquierda 36pt, Derecha 36pt, Superior 38pt, Inferior 40pt
        Document document = new Document(PageSize.A4, 36, 36, 38, 40);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);

            HeaderFooterPageEvent pageEvent = new HeaderFooterPageEvent(regularBaseFont, boldBaseFont, symbolBytes, doc);
            writer.setPageEvent(pageEvent);

            document.open();

            // ==========================================
            // PÁGINA 1: EJECUCIÓN OPERACIONAL
            // ==========================================

            // 0. Franja de Vista Previa (solo si previewMode = true)
            if (previewMode) {
                renderPreviewBanner(document);
            }

            // 1. Cabecera Corporativa con Logotipo Oficial
            renderHeaderBanner(document, doc);

            // 2. Sección 1: Identificación de la Producción
            renderSectionHeader(document, "1. IDENTIFICACIÓN DE LA PRODUCCIÓN");
            renderProductionIdentity(document, doc);

            // 3. Sección 2: Historial Operacional del Ciclo de Vida
            if (doc.timeline() != null && !doc.timeline().isEmpty()) {
                renderSectionHeader(document, "2. HISTORIAL OPERACIONAL DEL CICLO DE VIDA");
                renderTimeline(document, doc.timeline());
            }

            // 4. Sección 3: Recursos y Servicios Contratados
            renderSectionHeader(document, "3. ASIGNACIÓN LOGÍSTICA DE RECURSOS Y SERVICIOS");
            renderEquipmentAndServices(document, doc);

            // 5. Sección 4: Balance Operativo de Cuadrilla
            if (doc.crewTicket() != null) {
                renderSectionHeader(document, "4. BALANCE OPERATIVO DE MONTAJE Y CUADRILLA");
                renderCrewTicket(document, doc.crewTicket());
            }

            // Salto de página para separar la ejecución operativa del cierre administrativo
            document.newPage();

            // ==========================================
            // PÁGINA 2: CIERRE ADMINISTRATIVO Y TELEMETRÍA
            // ==========================================

            // 6. Sección 5: Declaración Administrativa de Cierre
            renderSectionHeader(document, "5. DECLARACIÓN ADMINISTRATIVA DE CIERRE");
            renderAdministrativeDeclaration(document, doc);

            // 7. Sección 6: Trazabilidad Técnica y Telemetría
            renderSectionHeader(document, "6. TRAZABILIDAD DISTRIBUIDA Y TELEMETRÍA DEL DOCUMENTO");
            renderTraceability(document, doc);

            // 8. Sección 7: Conformidad y Aprobaciones (firmas manuales)
            renderSectionHeader(document, "7. CONFORMIDAD Y APROBACIONES");
            renderConformity(document, doc);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Error al construir el documento PDF empresarial", e);
        }
    }

    public Path generatePdfFile(ProductionClosureDocumentDTO doc, Path destinationPath) throws IOException {
        return generatePdfFile(doc, destinationPath, false);
    }

    public Path generatePdfFile(ProductionClosureDocumentDTO doc, Path destinationPath, boolean previewMode) throws IOException {
        byte[] pdfBytes = generatePdfBytes(doc, previewMode);
        if (destinationPath.getParent() != null) {
            Files.createDirectories(destinationPath.getParent());
        }
        Files.write(destinationPath, pdfBytes);
        return destinationPath;
    }

    private void renderPreviewBanner(Document document) throws DocumentException {
        PdfPTable bannerTable = new PdfPTable(1);
        bannerTable.setWidthPercentage(100);
        bannerTable.setSpacingAfter(4f);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(PREVIEW_BG);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(PREVIEW_BORDER);
        cell.setPadding(2.5f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Font previewFont = new Font(boldBaseFont, 8.0f, Font.BOLD, PREVIEW_TEXT);
        Paragraph p = new Paragraph("VISTA PREVIA — DATOS DE PRUEBA — NO VÁLIDO PARA CONFORMIDAD", previewFont);
        p.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p);

        bannerTable.addCell(cell);
        document.add(bannerTable);
    }

    private void renderHeaderBanner(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(2);
        headerTable.setWidthPercentage(100);
        headerTable.setWidths(new float[]{62f, 38f});
        headerTable.setSpacingAfter(5f);

        // Celda Izquierda: Logotipo Oficial Aumentado y Título del Documento
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.setPadding(0);

        if (logoBytes != null) {
            try {
                Image logoImg = Image.getInstance(logoBytes);
                // Proporción original 1897 x 632 (~3.00:1). Escala aumentada a 155 x 51.7 pt para protagonismo
                logoImg.scaleToFit(155f, 51.7f);
                logoImg.setSpacingAfter(3f);
                leftCell.addElement(logoImg);
            } catch (Exception e) {
                renderFallbackBrandText(leftCell);
            }
        } else {
            renderFallbackBrandText(leftCell);
        }

        Font titleFont = new Font(boldBaseFont, 14.5f, Font.BOLD, PRIMARY_NAVY);
        String docTitle = (doc.metadata() != null && doc.metadata().documentTitle() != null)
                ? doc.metadata().documentTitle()
                : "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN";
        Paragraph title = new Paragraph(docTitle, titleFont);
        title.setSpacingAfter(2f);
        leftCell.addElement(title);

        Font subFont = new Font(regularBaseFont, 8.5f, Font.NORMAL, TEXT_MUTED);
        Paragraph sub = new Paragraph("Plataforma Cloud Native de Gestión y Producción de Eventos Masivos", subFont);
        leftCell.addElement(sub);

        // Celda Derecha: Tarjeta de Metadatos Documentales
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOX);
        rightCell.setBorderColor(BORDER_GRAY);
        rightCell.setBackgroundColor(BG_LIGHT_GRAY);
        rightCell.setPadding(5.5f);

        Font metaBoldFont = new Font(boldBaseFont, 8.5f, Font.BOLD, TEXT_DARK);
        Font metaFont = new Font(regularBaseFont, 8.5f, Font.NORMAL, TEXT_DARK);

        String code = (doc.metadata() != null && doc.metadata().documentCode() != null)
                ? doc.metadata().documentCode()
                : "EMX-ACTA-PRD-" + doc.production().productionId();
        String ver = (doc.metadata() != null && doc.metadata().version() != null)
                ? doc.metadata().version()
                : "1.0";
        String genTime = (doc.metadata() != null && doc.metadata().generatedAt() != null)
                ? UTC_DATE_TIME_FMT.format(doc.metadata().generatedAt()) + " UTC"
                : "N/A";

        Paragraph pCode = new Paragraph();
        pCode.add(new Chunk("Código: ", metaBoldFont));
        pCode.add(new Chunk(code, metaFont));
        rightCell.addElement(pCode);

        Paragraph pVer = new Paragraph();
        pVer.add(new Chunk("Versión: ", metaBoldFont));
        pVer.add(new Chunk(ver, metaFont));
        rightCell.addElement(pVer);

        Paragraph pGen = new Paragraph();
        pGen.add(new Chunk("Emisión: ", metaBoldFont));
        pGen.add(new Chunk(genTime, metaFont));
        rightCell.addElement(pGen);

        headerTable.addCell(leftCell);
        headerTable.addCell(rightCell);
        document.add(headerTable);

        // Línea divisoria decorativa con el azul primario institucional
        PdfPTable lineTable = new PdfPTable(1);
        lineTable.setWidthPercentage(100);
        lineTable.setSpacingAfter(5f);
        PdfPCell lineCell = new PdfPCell();
        lineCell.setBorder(Rectangle.BOTTOM);
        lineCell.setBorderColor(PRIMARY_BLUE);
        lineCell.setBorderWidth(1.5f);
        lineCell.setFixedHeight(2f);
        lineTable.addCell(lineCell);
        document.add(lineTable);
    }

    private void renderFallbackBrandText(PdfPCell cell) {
        Font brandFont = new Font(boldBaseFont, 16f, Font.BOLD, PRIMARY_NAVY);
        Paragraph brand = new Paragraph("EVENTOMAX", brandFont);
        brand.setSpacingAfter(3f);
        cell.addElement(brand);
    }

    private void renderSectionHeader(Document document, String title) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        table.setSpacingAfter(2.5f);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(BG_TABLE_HEADER);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(3.5f);

        Font font = new Font(boldBaseFont, 11f, Font.BOLD, PRIMARY_NAVY);
        Paragraph p = new Paragraph(title, font);
        cell.addElement(p);

        table.addCell(cell);
        document.add(table);
    }

    private void renderProductionIdentity(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{22f, 28f, 22f, 28f});
        table.setSpacingAfter(4f);

        var prod = doc.production();
        var org = doc.organizer();

        addLabelCell(table, "ID de Producción:");
        addValueCell(table, prod != null ? String.valueOf(prod.productionId()) : "N/A");

        addLabelCell(table, "Estado Operativo:");
        addStatusBadgeCell(table, prod != null ? prod.status() : "N/A");

        addLabelCell(table, "Nombre de Producción:");
        addValueCellColspan(table, prod != null ? safe(prod.productionName()) : "N/A", 3);

        addLabelCell(table, "Recinto / Ubicación:");
        addValueCellColspan(table, prod != null ? safe(prod.location()) : "N/A", 3);

        addLabelCell(table, "Fecha Programada:");
        addValueCell(table, (prod != null && prod.scheduledAt() != null) ? prod.scheduledAt().format(LOCAL_DATE_TIME_FMT) : "No especificada");

        addLabelCell(table, "Fecha de Cierre:");
        String closedAtDisplay = (prod != null && prod.closedAtFormatted() != null)
                ? prod.closedAtFormatted()
                : ((prod != null && prod.closedAt() != null) ? prod.closedAt().format(LOCAL_DATE_TIME_FMT) : "N/A");
        addValueCell(table, closedAtDisplay);

        addLabelCell(table, "Organizador ID:");
        addValueCell(table, org != null ? safe(org.organizerId()) : "N/A");

        addLabelCell(table, "Correo Contacto:");
        addValueCell(table, org != null ? safe(org.maskedEmail()) : "No registrado");

        addLabelCell(table, "Nombre Contacto:");
        addValueCell(table, (org != null && org.contactName() != null && !org.contactName().isBlank()) ? org.contactName() : "No registrado");

        addLabelCell(table, "Teléfono Contacto:");
        addValueCell(table, (org != null && org.phone() != null && !org.phone().isBlank()) ? org.phone() : "No registrado");

        document.add(table);
    }

    private void renderTimeline(Document document, List<ProductionClosureDocumentDTO.OperationalTimelineItemDTO> timeline) throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{7f, 30f, 25f, 38f});
        table.setHeaderRows(1);
        table.setSpacingAfter(4f);

        addTableHeaderCell(table, "Seq");
        addTableHeaderCell(table, "Transición");
        addTableHeaderCell(table, "Fecha / Hora (UTC)");
        addTableHeaderCell(table, "Actor Registrado");

        for (var item : timeline) {
            addTableCell(table, String.valueOf(item.sequenceIndex()), Element.ALIGN_CENTER);
            String transition = (item.fromStatus() != null ? item.fromStatus() : "-") + " -> " + safe(item.toStatus());
            addTableCell(table, transition, Element.ALIGN_LEFT);
            String dateStr = item.occurredAt() != null ? UTC_DATE_TIME_FMT.format(item.occurredAt()) + " UTC" : "N/A";
            addTableCell(table, dateStr, Element.ALIGN_CENTER);
            addTableCellSmall(table, formatActorOid(item.actorOid()), Element.ALIGN_LEFT);
        }

        document.add(table);
    }

    private static String formatActorOid(String actorOid) {
        if (actorOid == null) return "N/A";
        if (actorOid.contains(":")) {
            return actorOid.replace(":", ":\n");
        }
        return actorOid;
    }

    private void renderEquipmentAndServices(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        // Sub-tabla 1: Equipamiento
        Paragraph sub1 = new Paragraph("3.1. Equipamiento Asignado (Inventario Catalog)", new Font(boldBaseFont, 9.5f, Font.BOLD, PRIMARY_NAVY));
        sub1.setSpacingBefore(1.5f);
        sub1.setSpacingAfter(1.5f);
        document.add(sub1);

        PdfPTable eqTable = new PdfPTable(4);
        eqTable.setWidthPercentage(100);
        eqTable.setWidths(new float[]{15f, 50f, 15f, 20f});
        eqTable.setHeaderRows(1);
        eqTable.setSpacingAfter(3f);

        addTableHeaderCell(eqTable, "ID Equipo");
        addTableHeaderCell(eqTable, "Denominación del Equipamiento");
        addTableHeaderCell(eqTable, "Cantidad");
        addTableHeaderCell(eqTable, "Estado Asignación");

        var catState = doc.catalogAvailability() != null
                ? doc.catalogAvailability()
                : (doc.equipment() != null && !doc.equipment().isEmpty() ? ProductionClosureDocumentDTO.CatalogAvailabilityState.AVAILABLE :
                  (doc.equipment() != null ? ProductionClosureDocumentDTO.CatalogAvailabilityState.EMPTY : ProductionClosureDocumentDTO.CatalogAvailabilityState.NOT_PROVIDED));

        if (catState == ProductionClosureDocumentDTO.CatalogAvailabilityState.AVAILABLE && doc.equipment() != null && !doc.equipment().isEmpty()) {
            for (var eq : doc.equipment()) {
                String idText = eq.equipmentId() != null && eq.equipmentId() > 0 ? String.valueOf(eq.equipmentId()) : "—";
                addTableCell(eqTable, idText, Element.ALIGN_CENTER);
                addTableCell(eqTable, safe(eq.equipmentName()), Element.ALIGN_LEFT);
                String qtyText = eq.reservedQuantity() != null && eq.reservedQuantity() > 0 ? String.valueOf(eq.reservedQuantity()) : "—";
                addTableCell(eqTable, qtyText, Element.ALIGN_CENTER);
                addTableCell(eqTable, safe(eq.reservationStatus()), Element.ALIGN_CENTER);
            }
        } else if (catState == ProductionClosureDocumentDTO.CatalogAvailabilityState.EMPTY) {
            PdfPCell empty = new PdfPCell(new Phrase("Sin registros de equipamiento asociados a la producción", new Font(regularBaseFont, 8.5f, Font.ITALIC, TEXT_MUTED)));
            empty.setColspan(4);
            empty.setPadding(4f);
            eqTable.addCell(empty);
        } else if (catState == ProductionClosureDocumentDTO.CatalogAvailabilityState.UNAVAILABLE) {
            PdfPCell unavail = new PdfPCell(new Phrase("Servicio de inventario y catálogo temporalmente inaccesible (consulta no disponible al momento del cierre)", new Font(regularBaseFont, 8.5f, Font.ITALIC, TEXT_MUTED)));
            unavail.setColspan(4);
            unavail.setPadding(4f);
            eqTable.addCell(unavail);
        } else {
            PdfPCell notProv = new PdfPCell(new Phrase("Información de equipamiento no provista en el comando", new Font(regularBaseFont, 8.5f, Font.ITALIC, TEXT_MUTED)));
            notProv.setColspan(4);
            notProv.setPadding(4f);
            eqTable.addCell(notProv);
        }
        document.add(eqTable);

        // Sub-tabla 2: Servicios
        Paragraph sub2 = new Paragraph("3.2. Servicios Formalmente Contratados (Productions)", new Font(boldBaseFont, 9.5f, Font.BOLD, PRIMARY_NAVY));
        sub2.setSpacingBefore(1.5f);
        sub2.setSpacingAfter(1.5f);
        document.add(sub2);

        PdfPTable srvTable = new PdfPTable(3);
        srvTable.setWidthPercentage(100);
        srvTable.setWidths(new float[]{15f, 55f, 30f});
        srvTable.setHeaderRows(1);
        srvTable.setSpacingAfter(4f);

        addTableHeaderCell(srvTable, "ID Servicio");
        addTableHeaderCell(srvTable, "Denominación del Servicio");
        addTableHeaderCell(srvTable, "Tarifa Base Registrada");

        if (doc.services() != null && !doc.services().isEmpty()) {
            for (var srv : doc.services()) {
                String idText = srv.serviceId() != null && srv.serviceId() > 0 ? String.valueOf(srv.serviceId()) : "—";
                addTableCell(srvTable, idText, Element.ALIGN_CENTER);
                addTableCell(srvTable, safe(srv.serviceName()), Element.ALIGN_LEFT);
                // Sin inventar sufijo monetario: la BD almacena 175.00 sin columna de moneda
                String priceStr = srv.unitPrice() != null
                        ? srv.unitPrice().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                        : "No registrada";
                addTableCell(srvTable, priceStr, Element.ALIGN_RIGHT);
            }
        } else if (doc.services() == null) {
            PdfPCell notProv = new PdfPCell(new Phrase("Información de servicios no provista o no disponible en el comando", new Font(regularBaseFont, 8.5f, Font.ITALIC, TEXT_MUTED)));
            notProv.setColspan(3);
            notProv.setPadding(4f);
            srvTable.addCell(notProv);
        } else {
            PdfPCell empty = new PdfPCell(new Phrase("Sin servicios complementarios registrados", new Font(regularBaseFont, 8.5f, Font.ITALIC, TEXT_MUTED)));
            empty.setColspan(3);
            empty.setPadding(4f);
            srvTable.addCell(empty);
        }
        document.add(srvTable);
    }

    private void renderCrewTicket(Document document, ProductionClosureDocumentDTO.CrewTicketRecordDTO ticket) throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{18f, 32f, 18f, 32f});
        table.setKeepTogether(true);
        table.setSpacingAfter(4f);

        addLabelCell(table, "Ticket ID:");
        addValueCell(table, ticket.ticketId() != null ? String.valueOf(ticket.ticketId()) : "N/A");

        addLabelCell(table, "Estado de Cuadrilla:");
        addValueCell(table, safe(ticket.ticketStatus()));

        addLabelCell(table, "Event ID Cuadrilla:");
        addValueCellColspan(table, safe(ticket.ticketEventId()), 3);

        addLabelCell(table, "Instrucciones:");
        addValueCellColspan(table, (ticket.instructions() != null && !ticket.instructions().isBlank()) ? ticket.instructions() : "Sin instrucciones operativas específicas registradas.", 3);

        document.add(table);
    }

    private void renderAdministrativeDeclaration(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        table.setSpacingAfter(8f);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setBackgroundColor(BG_LIGHT_GRAY);
        cell.setPadding(8f);

        var decl = doc.administrativeDeclaration();

        Font boldFont = new Font(boldBaseFont, 9f, Font.BOLD, TEXT_DARK);
        Font normFont = new Font(regularBaseFont, 9f, Font.NORMAL, TEXT_DARK);

        Paragraph p1 = new Paragraph();
        p1.add(new Chunk("Constancia de Transición Terminal: ", boldFont));
        p1.add(new Chunk((decl != null && decl.closureReason() != null)
                ? decl.closureReason()
                : "La producción ha alcanzado su estado terminal CERRADO en el sistema. Los registros operacionales quedan consolidados administrativamente.", normFont));
        p1.setSpacingAfter(6f);
        cell.addElement(p1);

        Paragraph p2 = new Paragraph();
        p2.add(new Chunk("Observación Cronológica: ", boldFont));
        p2.add(new Chunk((decl != null && decl.chronologicalObservation() != null)
                ? decl.chronologicalObservation()
                : "Cierre administrativo registrado en plataforma con anterioridad a la fecha originalmente programada. Este registro no acredita por sí mismo la ejecución material anticipada ni la entrega de instalaciones.", normFont));
        p2.setSpacingAfter(6f);
        cell.addElement(p2);

        Paragraph p3 = new Paragraph();
        p3.add(new Chunk("Aviso Legal y Limitación de Alcance: ", boldFont));
        p3.add(new Chunk((decl != null && decl.legalDisclaimer() != null)
                ? decl.legalDisclaimer()
                : "Documento emitido electrónicamente como constancia de registro interno. No constituye liquidación financiera, documento tributario ni acta de conformidad comercial de servicios.", normFont));
        cell.addElement(p3);

        table.addCell(cell);
        document.add(table);
    }

    private void renderTraceability(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{28f, 72f});
        table.setSpacingBefore(4f);
        table.setSpacingAfter(8f);

        var trace = doc.traceability();

        addLabelCell(table, "RabbitMQ Command Event ID:");
        addValueCell(table, trace != null ? safe(trace.rabbitmqCommandEventId()) : "N/A");

        addLabelCell(table, "Kafka Closing Event ID:");
        addValueCell(table, trace != null ? safe(trace.kafkaClosingEventId()) : "N/A");

        addLabelCell(table, "Trace ID:");
        addValueCell(table, trace != null ? safe(trace.traceId()) : "N/A");

        addLabelCell(table, "Correlation ID:");
        addValueCell(table, trace != null ? safe(trace.correlationId()) : "N/A");

        addLabelCell(table, "Operador de Cierre (OID):");
        addValueCell(table, trace != null ? safe(trace.closingActorOid()) : "N/A");

        document.add(table);
    }

    private void renderConformity(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50f, 50f});
        table.setKeepTogether(true);
        table.setSpacingBefore(4f);
        table.setSpacingAfter(6f);

        Font boxHeaderFont = new Font(boldBaseFont, 9.5f, Font.BOLD, PRIMARY_NAVY);
        Font labelFont = new Font(boldBaseFont, 8.5f, Font.BOLD, TEXT_DARK);
        Font regularFieldFont = new Font(regularBaseFont, 8.5f, Font.NORMAL, TEXT_DARK);
        Font statusFont = new Font(regularBaseFont, 8f, Font.ITALIC, TEXT_MUTED);

        // Recuadro 1: RESPONSABLE OPERATIVO
        PdfPCell cell1 = new PdfPCell();
        cell1.setBorder(Rectangle.BOX);
        cell1.setBorderColor(BORDER_GRAY);
        cell1.setBackgroundColor(BG_LIGHT_GRAY);
        cell1.setPadding(8f);
        cell1.setMinimumHeight(130f);

        Paragraph hdr1 = new Paragraph("RESPONSABLE OPERATIVO", boxHeaderFont);
        hdr1.setSpacingAfter(36f);
        cell1.addElement(hdr1);

        Paragraph sigLine1 = new Paragraph("____________________________________________", new Font(regularBaseFont, 8.5f, Font.NORMAL, TEXT_MUTED));
        sigLine1.setSpacingAfter(4f);
        cell1.addElement(sigLine1);

        Paragraph fld1 = new Paragraph();
        fld1.add(new Chunk("Nombre: ", labelFont));
        fld1.add(new Chunk("__________________________________", regularFieldFont));
        fld1.setSpacingAfter(2.5f);
        cell1.addElement(fld1);

        Paragraph fldCargo1 = new Paragraph();
        fldCargo1.add(new Chunk("Cargo:  ", labelFont));
        fldCargo1.add(new Chunk("__________________________________", regularFieldFont));
        fldCargo1.setSpacingAfter(2.5f);
        cell1.addElement(fldCargo1);

        Paragraph fldFecha1 = new Paragraph();
        fldFecha1.add(new Chunk("Fecha:  ", labelFont));
        fldFecha1.add(new Chunk("____ / ____ / 2026", regularFieldFont));
        fldFecha1.setSpacingAfter(4f);
        cell1.addElement(fldFecha1);

        String prodStatus = (doc.conformity() != null && doc.conformity().producerApprovalStatus() != null)
                ? doc.conformity().producerApprovalStatus()
                : "PENDIENTE DE CONFORMIDAD";
        Paragraph st1 = new Paragraph("Estado: " + prodStatus, statusFont);
        cell1.addElement(st1);

        // Recuadro 2: REPRESENTANTE DEL ORGANIZADOR
        PdfPCell cell2 = new PdfPCell();
        cell2.setBorder(Rectangle.BOX);
        cell2.setBorderColor(BORDER_GRAY);
        cell2.setBackgroundColor(BG_LIGHT_GRAY);
        cell2.setPadding(8f);
        cell2.setMinimumHeight(130f);

        String orgId = doc.organizer() != null ? doc.organizer().organizerId() : "Organizador";
        Paragraph hdr2 = new Paragraph("REPRESENTANTE DEL ORGANIZADOR", boxHeaderFont);
        hdr2.setSpacingAfter(24f);
        cell2.addElement(hdr2);

        Paragraph orgSub = new Paragraph("Organización: " + orgId, new Font(regularBaseFont, 8f, Font.NORMAL, TEXT_MUTED));
        orgSub.setSpacingAfter(4f);
        cell2.addElement(orgSub);

        Paragraph sigLine2 = new Paragraph("____________________________________________", new Font(regularBaseFont, 8.5f, Font.NORMAL, TEXT_MUTED));
        sigLine2.setSpacingAfter(4f);
        cell2.addElement(sigLine2);

        Paragraph fld2 = new Paragraph();
        fld2.add(new Chunk("Nombre: ", labelFont));
        fld2.add(new Chunk("__________________________________", regularFieldFont));
        fld2.setSpacingAfter(2.5f);
        cell2.addElement(fld2);

        Paragraph fldCargo2 = new Paragraph();
        fldCargo2.add(new Chunk("Cargo:  ", labelFont));
        fldCargo2.add(new Chunk("__________________________________", regularFieldFont));
        fldCargo2.setSpacingAfter(2.5f);
        cell2.addElement(fldCargo2);

        Paragraph fldFecha2 = new Paragraph();
        fldFecha2.add(new Chunk("Fecha:  ", labelFont));
        fldFecha2.add(new Chunk("____ / ____ / 2026", regularFieldFont));
        fldFecha2.setSpacingAfter(4f);
        cell2.addElement(fldFecha2);

        String orgStatus = (doc.conformity() != null && doc.conformity().organizerConformityStatus() != null)
                ? doc.conformity().organizerConformityStatus()
                : "PENDIENTE DE CONFORMIDAD";
        Paragraph st2 = new Paragraph("Estado: " + orgStatus, statusFont);
        cell2.addElement(st2);

        table.addCell(cell1);
        table.addCell(cell2);
        document.add(table);
    }

    // ==========================================
    // MÉTODOS AUXILIARES DE CELDAS TABULARES
    // ==========================================

    private void addLabelCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(boldBaseFont, 9f, Font.BOLD, TEXT_DARK)));
        cell.setBackgroundColor(BG_LIGHT_GRAY);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(3.5f);
        table.addCell(cell);
    }

    private void addValueCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(regularBaseFont, 9f, Font.NORMAL, TEXT_DARK)));
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(3.5f);
        table.addCell(cell);
    }

    private void addValueCellColspan(PdfPTable table, String text, int colspan) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(regularBaseFont, 9f, Font.NORMAL, TEXT_DARK)));
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setColspan(colspan);
        cell.setPadding(3.5f);
        table.addCell(cell);
    }

    private void addStatusBadgeCell(PdfPTable table, String status) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(3f);

        Paragraph badge = new Paragraph(" " + status + " ", new Font(boldBaseFont, 8.5f, Font.BOLD, BADGE_GREEN_TEXT));
        badge.getFont().setColor(BADGE_GREEN_TEXT);
        cell.setBackgroundColor(BADGE_GREEN_BG);
        cell.addElement(badge);

        table.addCell(cell);
    }

    private void addTableHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(boldBaseFont, 8.5f, Font.BOLD, TEXT_DARK)));
        cell.setBackgroundColor(BG_TABLE_HEADER);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(3.5f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private void addTableCell(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(regularBaseFont, 8.5f, Font.NORMAL, TEXT_DARK)));
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(3.5f);
        cell.setHorizontalAlignment(align);
        table.addCell(cell);
    }

    private void addTableCellSmall(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(regularBaseFont, 7.5f, Font.NORMAL, TEXT_DARK)));
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(3.5f);
        cell.setHorizontalAlignment(align);
        table.addCell(cell);
    }

    private static String safe(String s) {
        return s == null ? "N/A" : s;
    }

    /**
     * Helper de eventos para paginación dinámica "Página X de Y", cabecera continua (Pág 2+)
     * y pie de página institucional.
     */
    private static class HeaderFooterPageEvent extends PdfPageEventHelper {
        private final BaseFont fontRegular;
        private final BaseFont fontBold;
        private final byte[] symbolBytes;
        private final ProductionClosureDocumentDTO doc;
        private PdfTemplate totalPagesTemplate;

        HeaderFooterPageEvent(BaseFont fontRegular, BaseFont fontBold, byte[] symbolBytes, ProductionClosureDocumentDTO doc) {
            this.fontRegular = fontRegular;
            this.fontBold = fontBold;
            this.symbolBytes = symbolBytes;
            this.doc = doc;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            this.totalPagesTemplate = writer.getDirectContent().createTemplate(30f, 16f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float pageRight = document.right();
            float pageLeft = document.left();
            float pageTop = document.top();
            float pageBottom = document.bottom() - 20f;
            int pageNumber = writer.getPageNumber();

            // 1. Cabecera compacta institucional para páginas secundarias (Página 2+)
            if (pageNumber > 1) {
                float headerY = pageTop + 14f;

                // Isotipo / Símbolo institucional
                if (symbolBytes != null) {
                    try {
                        Image symbolImg = Image.getInstance(symbolBytes);
                        symbolImg.scaleToFit(14f, 14f);
                        symbolImg.setAbsolutePosition(pageLeft, headerY - 2f);
                        cb.addImage(symbolImg);
                    } catch (Exception ignored) {
                    }
                }

                // Título de cabecera secundaria
                cb.beginText();
                cb.setFontAndSize(fontBold, 8.5f);
                cb.setColorFill(PRIMARY_NAVY);
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "EVENTOMAX — ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO", pageLeft + 18f, headerY, 0);
                cb.endText();

                // Código del documento en cabecera derecha
                String docCode = (doc.metadata() != null && doc.metadata().documentCode() != null)
                        ? doc.metadata().documentCode()
                        : "EMX-ACTA-PRD-" + doc.production().productionId();
                String ver = (doc.metadata() != null && doc.metadata().version() != null)
                        ? "v" + doc.metadata().version()
                        : "v1.0";

                cb.beginText();
                cb.setFontAndSize(fontRegular, 8f);
                cb.setColorFill(TEXT_MUTED);
                cb.showTextAligned(PdfContentByte.ALIGN_RIGHT, docCode + " | " + ver, pageRight, headerY, 0);
                cb.endText();

                // Línea divisoria bajo cabecera secundaria
                cb.setColorStroke(BORDER_GRAY);
                cb.setLineWidth(0.5f);
                cb.moveTo(pageLeft, headerY - 6f);
                cb.lineTo(pageRight, headerY - 6f);
                cb.stroke();
            }

            // 2. Pie de página institucional (en todas las páginas)
            // Línea divisoria de pie
            cb.setColorStroke(BORDER_GRAY);
            cb.setLineWidth(0.5f);
            cb.moveTo(pageLeft, pageBottom + 12f);
            cb.lineTo(pageRight, pageBottom + 12f);
            cb.stroke();

            // Texto izquierda: aviso legal y limitación de alcance
            cb.beginText();
            cb.setFontAndSize(fontRegular, 7.5f);
            cb.setColorFill(TEXT_MUTED);
            cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "EventoMax Platform — Documento de Cierre Operativo (Sin efectos tributarios ni firma electrónica avanzada)", pageLeft, pageBottom, 0);
            cb.endText();

            // Texto derecha: Paginación "Página X de "
            String pageText = "Página " + pageNumber + " de ";
            cb.beginText();
            cb.setFontAndSize(fontRegular, 8f);
            cb.setColorFill(TEXT_DARK);
            cb.showTextAligned(PdfContentByte.ALIGN_RIGHT, pageText, pageRight - 15f, pageBottom, 0);
            cb.endText();

            // Template total páginas
            cb.addTemplate(totalPagesTemplate, pageRight - 14f, pageBottom);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalPagesTemplate.beginText();
            totalPagesTemplate.setFontAndSize(fontBold, 8f);
            totalPagesTemplate.setColorFill(TEXT_DARK);
            totalPagesTemplate.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPagesTemplate.endText();
        }
    }
}
