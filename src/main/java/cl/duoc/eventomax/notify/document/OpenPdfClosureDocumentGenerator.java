package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO.*;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generador del Acta de Cierre Operativo con Anexo Técnico (Diseño C) implementado con OpenPDF 1.3.40.
 * <p>
 * Estructura desacoplada en dos secciones:
 * - Parte 1 (Página 1+): Acta Ejecutiva clara y autosuficiente para organizador y productora,
 *   sin identificadores técnicos crudos (UUID/OID), sin moneda inventada y sin afirmaciones falsas
 *   de firmas, certificaciones legales ni ejecución física.
 * - Parte 2 (Página nueva): Anexo Técnico de auditoría y trazabilidad distribuida (bitácora Kafka,
 *   correlación RabbitMQ/Kafka, identidades técnicas enmascaradas e integridad documental diferenciada).
 */
@Component
public class OpenPdfClosureDocumentGenerator {

    private static final DateTimeFormatter UTC_DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // Paleta Corporativa EventoMax (Diseño C — sobria y legible)
    private static final Color PRIMARY_NAVY = new Color(3, 28, 67);        // #031C43
    private static final Color PRIMARY_BLUE = new Color(2, 79, 252);       // #024FFC
    private static final Color SLATE_900 = new Color(15, 23, 42);          // #0F172A
    private static final Color SLATE_700 = new Color(51, 65, 85);          // #334155
    private static final Color SLATE_500 = new Color(100, 116, 139);       // #64748B
    private static final Color SLATE_300 = new Color(203, 213, 225);       // #CBD5E1
    private static final Color SLATE_200 = new Color(226, 232, 240);       // #E2E8F0
    private static final Color SLATE_100 = new Color(241, 245, 249);       // #F1F5F9
    private static final Color SLATE_50 = new Color(248, 250, 252);        // #F8FAFC
    private static final Color EMERALD_700 = new Color(4, 120, 87);        // #047857
    private static final Color AMBER_800 = new Color(146, 64, 14);         // #92400E
    private static final Color AMBER_50 = new Color(255, 251, 235);        // #FFFBEB
    private static final Color AMBER_200 = new Color(253, 230, 138);       // #FDE68A
    private static final Color PREVIEW_BG = new Color(239, 246, 255);      // #EFF6FF
    private static final Color PREVIEW_BORDER = new Color(191, 219, 254);  // #BFDBFE
    private static final Color PREVIEW_TEXT = new Color(30, 64, 175);      // #1E40AF

    private final BaseFont regularBaseFont;
    private final BaseFont boldBaseFont;
    private final BaseFont courierRegularFont;
    private final BaseFont courierBoldFont;
    private final BaseFont zapfDingbatsFont;
    private final byte[] logoBytes;
    private final byte[] symbolBytes;

    public OpenPdfClosureDocumentGenerator() {
        this.regularBaseFont = loadTrueTypeFont("/fonts/Roboto-Regular.ttf", BaseFont.HELVETICA);
        this.boldBaseFont = loadTrueTypeFont("/fonts/Roboto-Bold.ttf", BaseFont.HELVETICA_BOLD);
        this.courierRegularFont = loadType1Font(BaseFont.COURIER);
        this.courierBoldFont = loadType1Font(BaseFont.COURIER_BOLD);
        this.zapfDingbatsFont = loadType1Font(BaseFont.ZAPFDINGBATS);
        this.logoBytes = loadResourceBytes("/branding/eventomax-logo-dark.png");
        this.symbolBytes = loadResourceBytes("/branding/eventomax-symbol-dark.png");
    }

    private BaseFont loadTrueTypeFont(String resourcePath, String fallbackName) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] fontBytes = is.readAllBytes();
                String fontName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                return BaseFont.createFont(fontName, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
            }
        } catch (Exception ignored) {
        }
        return loadType1Font(fallbackName);
    }

    private BaseFont loadType1Font(String fontName) {
        try {
            return BaseFont.createFont(fontName, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo inicializar la fuente tipográfica base: " + fontName, e);
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

        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        Document document = new Document(PageSize.A4, 38f, 38f, 36f, 40f);
        String canonicalSha256 = computeCanonicalSha256(doc);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
            writer.setPageEvent(new HeaderFooterPageEvent(
                    regularBaseFont, boldBaseFont, courierRegularFont, symbolBytes, doc, previewMode));

            document.open();

            // =================================================================
            // PARTE 1: ACTA EJECUTIVA (PÁGINA 1+)
            // =================================================================
            if (previewMode) {
                renderPreviewBanner(document, "VISTA PREVIA — DATOS DE PRUEBA — NO VÁLIDO PARA CONFORMIDAD");
            }

            renderExecutiveHeader(document, doc);
            renderIdentificationSection(document, doc);
            renderClosureSummarySection(document, doc);
            renderServicesSection(document, doc);
            renderEquipmentSection(document, doc);
            renderCrewCoordinationSection(document, doc);
            renderResponsiblesAndConformitySection(document, doc);
            renderScopeNoticeSection(document);

            // =================================================================
            // PARTE 2: ANEXO TÉCNICO Y DE AUDITORÍA (SIEMPRE EN PÁGINA NUEVA)
            // =================================================================
            document.newPage();

            if (previewMode) {
                renderPreviewBanner(document, "VISTA PREVIA — DATOS DE PRUEBA — ANEXO TÉCNICO");
            }

            renderAnnexHeaderBanner(document, doc);
            renderAnnexTimelineSection(document, doc);
            renderAnnexMessagingSection(document, doc);
            renderAnnexTechnicalIdentitiesSection(document, doc);
            renderAnnexIntegritySection(document, doc, canonicalSha256);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Error al construir el Acta de Cierre Operativo PDF", e);
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

    // =========================================================================
    // PARTE 1: ACTA EJECUTIVA (DISEÑO C)
    // =========================================================================

    private void renderPreviewBanner(Document document, String text) throws DocumentException {
        PdfPTable banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(6f);

        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(boldBaseFont, 6.8f, Font.BOLD, PREVIEW_TEXT)));
        cell.setBackgroundColor(PREVIEW_BG);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(PREVIEW_BORDER);
        cell.setBorderWidth(0.5f);
        cell.setPadding(3f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        banner.addCell(cell);
        document.add(banner);
    }

    private void renderExecutiveHeader(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        Long prodId = doc.production() != null && doc.production().productionId() != null
                ? doc.production().productionId() : 0L;
        String folio = formatFolio(prodId);
        String docCode = resolveDocumentCode(doc, prodId);
        String version = resolveVersion(doc);
        String status = doc.production() != null && !isBlank(doc.production().status())
                ? doc.production().status() : "CERRADO";
        String emittedUtc = formatInstantUtc(doc.metadata() != null ? doc.metadata().generatedAt() : null);

        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{60f, 40f});
        header.setSpacingAfter(9f);
        header.setKeepTogether(true);

        // Columna izquierda: barra azul corporativa + logo oficial + título y subtítulo
        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.LEFT);
        left.setBorderColor(PRIMARY_BLUE);
        left.setBorderWidth(3f);
        left.setPaddingLeft(10f);
        left.setPaddingTop(2f);
        left.setPaddingBottom(2f);
        left.setPaddingRight(8f);
        left.setVerticalAlignment(Element.ALIGN_MIDDLE);

        if (logoBytes != null) {
            try {
                Image logo = Image.getInstance(logoBytes);
                logo.scaleToFit(116f, 34f);
                logo.setSpacingAfter(5f);
                left.addElement(logo);
            } catch (Exception ignored) {
            }
        }

        Paragraph title = new Paragraph(ProductionClosureDocumentBuilder.DOCUMENT_TITLE,
                new Font(boldBaseFont, 15.0f, Font.BOLD, PRIMARY_NAVY));
        title.setLeading(17.5f);
        left.addElement(title);

        Paragraph subtitle = new Paragraph("Registro de cierre de producción",
                new Font(regularBaseFont, 8.6f, Font.NORMAL, SLATE_500));
        subtitle.setSpacingBefore(1.5f);
        left.addElement(subtitle);

        // Columna derecha: recuadro sobrio de folio y metadatos
        PdfPCell right = new PdfPCell();
        right.setBackgroundColor(SLATE_50);
        right.setBorder(Rectangle.BOX);
        right.setBorderColor(SLATE_200);
        right.setBorderWidth(0.75f);
        right.setPadding(7f);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph pFolio = new Paragraph(folio, new Font(courierBoldFont, 11.0f, Font.BOLD, PRIMARY_NAVY));
        pFolio.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(pFolio);

        Paragraph pCode = new Paragraph(docCode + " · Producción #" + prodId + " · v" + version,
                new Font(courierRegularFont, 6.9f, Font.NORMAL, SLATE_700));
        pCode.setAlignment(Element.ALIGN_RIGHT);
        pCode.setSpacingBefore(2f);
        right.addElement(pCode);

        Paragraph pStatus = new Paragraph("Estado: " + status,
                new Font(boldBaseFont, 7.4f, Font.BOLD, EMERALD_700));
        pStatus.setAlignment(Element.ALIGN_RIGHT);
        pStatus.setSpacingBefore(3f);
        right.addElement(pStatus);

        Paragraph pEmit = new Paragraph("Emisión: " + emittedUtc,
                new Font(regularBaseFont, 6.8f, Font.NORMAL, SLATE_500));
        pEmit.setAlignment(Element.ALIGN_RIGHT);
        pEmit.setSpacingBefore(1.5f);
        right.addElement(pEmit);

        header.addCell(left);
        header.addCell(right);
        document.add(header);
    }

    private void renderIdentificationSection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        addCleanHeading(document, "IDENTIFICACIÓN DE LA PRODUCCIÓN");

        ProductionIdentityDTO prod = doc.production();
        OrganizerIdentityDTO org = doc.organizer();
        TechnicalTraceabilityDTO trace = doc.traceability();

        Long prodId = prod != null && prod.productionId() != null ? prod.productionId() : 0L;
        String prodName = prod != null && !isBlank(prod.productionName()) ? prod.productionName() : "N/A";
        String location = prod != null && !isBlank(prod.location()) ? prod.location() : "N/A";
        String status = prod != null && !isBlank(prod.status()) ? prod.status() : "CERRADO";
        String scheduledDisplay = formatScheduledAt(prod != null ? prod.scheduledAt() : null);
        String createdDisplay = formatLocalDateTime(prod != null ? prod.createdAt() : null, "No informada");
        String closedDisplay = formatClosedAtDisplay(prod);
        String organizerDisplay = resolveOrganizerDisplay(org);
        String maskedEmail = (org != null && !isBlank(org.maskedEmail())) ? org.maskedEmail() : "No informado";
        String closingActorDisplay = resolveClosingActorExecutiveDisplay(trace);
        String resolutionSourceDisplay = translateResolutionSource(org != null ? org.resolutionSource() : null);

        PdfPTable infoGrid = new PdfPTable(4);
        infoGrid.setWidthPercentage(100);
        infoGrid.setWidths(new float[]{19f, 34f, 19f, 28f});
        infoGrid.setSpacingAfter(7f);
        infoGrid.setSplitRows(false);
        infoGrid.setSplitLate(true);

        // Si el nombre o ubicación son muy largos, usar fila completa (colspan=3) para evitar compresión
        if (prodName.length() > 55) {
            addCleanFullRowKV(infoGrid, "Producción", prodName, true);
            addCleanKV(infoGrid, "N° Producción", "#" + prodId, true);
            addCleanKV(infoGrid, "Estado registrado", status, true);
        } else {
            addCleanKV(infoGrid, "Producción", prodName, true);
            addCleanKV(infoGrid, "N° Producción", "#" + prodId + " · Estado: " + status, true);
        }

        if (location.length() > 55) {
            addCleanFullRowKV(infoGrid, "Recinto / Ubicación", location, false);
            addCleanKV(infoGrid, "Fecha programada", scheduledDisplay, false);
            addCleanKV(infoGrid, "Cierre registrado", closedDisplay, true);
        } else {
            addCleanKV(infoGrid, "Recinto / Ubicación", location, false);
            addCleanKV(infoGrid, "Fecha programada", scheduledDisplay, false);
        }

        addCleanKV(infoGrid, "Organizador asociado", organizerDisplay, true);
        addCleanKV(infoGrid, "Correo de contacto", maskedEmail, false);

        if (location.length() <= 55) {
            addCleanKV(infoGrid, "Creación registrada", createdDisplay, false);
            addCleanKV(infoGrid, "Cierre registrado", closedDisplay, true);
        } else {
            addCleanKV(infoGrid, "Creación registrada", createdDisplay, false);
            addCleanKV(infoGrid, "Origen identidad org.", resolutionSourceDisplay, false);
        }

        addCleanKV(infoGrid, "Cerrado por", closingActorDisplay, false);
        if (location.length() <= 55) {
            addCleanKV(infoGrid, "Origen identidad org.", resolutionSourceDisplay, false);
        } else {
            addCleanKV(infoGrid, "Emisión del acta", formatInstantUtc(doc.metadata() != null ? doc.metadata().generatedAt() : null), false);
        }

        document.add(infoGrid);
    }

    private void renderClosureSummarySection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        String closedDisplay = formatClosedAtDisplay(doc.production());
        String summaryText = "Resumen de cierre administrativo: La producción alcanzó el estado CERRADO"
                + (!"No informado".equals(closedDisplay) ? " el " + closedDisplay : "")
                + ". Este registro refleja el estado administrativo almacenado en EventoMax y no acredita por sí solo la ejecución física del evento.";

        addCallout(document, summaryText, SLATE_50, SLATE_300, SLATE_700, 7.6f, 8f);
    }

    private void renderServicesSection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        addCleanHeading(document, "SERVICIOS CONTRATADOS");

        List<ContractedServiceRecordDTO> services = doc.services();
        if (services == null) {
            addCallout(document,
                    "Información de servicios contratados no provista en el comando recibido.",
                    SLATE_50, SLATE_200, SLATE_700, 7.5f, 8f);
            return;
        }
        if (services.isEmpty()) {
            addCallout(document,
                    "Sin servicios contratados registrados para esta producción.",
                    SLATE_50, SLATE_200, SLATE_700, 7.5f, 8f);
            return;
        }

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{12f, 50f, 19f, 19f});
        table.setHeaderRows(1);
        table.setSplitRows(false);
        table.setSplitLate(true);
        table.setSpacingAfter(2.5f);

        addCleanTh(table, "Código", Element.ALIGN_LEFT);
        addCleanTh(table, "Servicio", Element.ALIGN_LEFT);
        addCleanTh(table, "Contratación", Element.ALIGN_CENTER);
        addCleanTh(table, "Tarifa base reg.", Element.ALIGN_RIGHT);

        BigDecimal total = BigDecimal.ZERO;
        boolean allValidRates = true;

        for (ContractedServiceRecordDTO srv : services) {
            String code = srv.serviceId() != null ? String.format("SRV-%03d", srv.serviceId()) : "SRV-N/A";
            String name = !isBlank(srv.serviceName()) ? srv.serviceName() : "Servicio sin denominación";
            String contractedAt = srv.contractedAt() != null ? SHORT_LOCAL_FMT.format(srv.contractedAt()) : "—";
            String priceText;
            if (srv.unitPrice() != null) {
                priceText = formatNumericRate(srv.unitPrice());
                total = total.add(srv.unitPrice());
            } else {
                priceText = "No informada";
                allValidRates = false;
            }

            addCleanTd(table, code, Element.ALIGN_LEFT, true, 7.6f);
            addCleanTd(table, name, Element.ALIGN_LEFT, false, 7.9f);
            addCleanTd(table, contractedAt, Element.ALIGN_CENTER, true, 7.4f);
            addCleanTd(table, priceText, Element.ALIGN_RIGHT, srv.unitPrice() != null, 7.9f);
        }

        if (allValidRates) {
            addCleanTotalRow(table,
                    "Total tarifas base registradas (sin impuestos ni moneda contractual)",
                    formatNumericRate(total),
                    3);
        }

        document.add(table);

        String note = allValidRates
                ? "Valores numéricos base almacenados sin moneda contractual explícita, sin impuestos ni descuentos. No constituye factura ni precio final."
                : "Total omitido porque uno o más servicios no registran tarifa base. Valores sin moneda contractual ni impuestos.";
        addFootnoteNote(document, note, 8f);
    }

    private void renderEquipmentSection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        addCleanHeading(document, "INVENTARIO Y EQUIPAMIENTO RESERVADO");

        List<ReservedEquipmentRecordDTO> equipment = doc.equipment();
        CatalogAvailabilityState catState = doc.catalogAvailability() != null
                ? doc.catalogAvailability()
                : (equipment != null && !equipment.isEmpty()
                        ? CatalogAvailabilityState.AVAILABLE
                        : (equipment != null ? CatalogAvailabilityState.EMPTY : CatalogAvailabilityState.NOT_PROVIDED));

        if (catState == CatalogAvailabilityState.AVAILABLE && equipment != null && !equipment.isEmpty()) {
            PdfPTable table = new PdfPTable(5);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{12f, 40f, 8f, 27f, 13f});
            table.setHeaderRows(1);
            table.setSplitRows(false);
            table.setSplitLate(true);
            table.setSpacingAfter(2.5f);

            addCleanTh(table, "ID Equipo", Element.ALIGN_LEFT);
            addCleanTh(table, "Equipamiento", Element.ALIGN_LEFT);
            addCleanTh(table, "Cant.", Element.ALIGN_CENTER);
            addCleanTh(table, "Estado de reserva", Element.ALIGN_LEFT);
            addCleanTh(table, "ID Reserva", Element.ALIGN_RIGHT);

            boolean hasActiveReservation = false;
            for (ReservedEquipmentRecordDTO eq : equipment) {
                String eqCode = eq.equipmentId() != null ? String.format("EQ-%03d", eq.equipmentId()) : "EQ-N/A";
                String eqName = !isBlank(eq.equipmentName()) ? eq.equipmentName() : "Equipamiento sin nombre";
                String qty = eq.reservedQuantity() != null ? String.valueOf(eq.reservedQuantity()) : "—";
                String statusEs = translateReservationStatus(eq.reservationStatus());
                String resCode = eq.reservationId() != null ? String.format("RES-%03d", eq.reservationId()) : "—";
                if ("RESERVED".equalsIgnoreCase(eq.reservationStatus())) {
                    hasActiveReservation = true;
                }

                addCleanTd(table, eqCode, Element.ALIGN_LEFT, true, 7.6f);
                addCleanTd(table, eqName, Element.ALIGN_LEFT, false, 7.9f);
                addCleanTd(table, qty, Element.ALIGN_CENTER, true, 7.9f);
                addCleanTd(table, statusEs, Element.ALIGN_LEFT, false, 7.6f);
                addCleanTd(table, resCode, Element.ALIGN_RIGHT, true, 7.5f);
            }

            document.add(table);
            String note = hasActiveReservation
                    ? "Las reservas activas permanecen vigentes en Catálogo; este documento no acredita devolución física de equipamiento."
                    : "Estados reflejados según el registro informado por Catálogo al emitir el acta (ver códigos internos en Anexo Técnico).";
            addFootnoteNote(document, note, 8f);
            return;
        }

        switch (catState) {
            case EMPTY -> addCallout(document,
                    "Sin reservas de equipamiento informadas para esta producción (consulta confirmada con Catálogo).",
                    SLATE_50, SLATE_200, SLATE_700, 7.5f, 8f);
            case UNAVAILABLE -> addCallout(document,
                    "No fue posible verificar el inventario en el servicio de Catálogo al emitir el acta. Esto no confirma ausencia de reservas.",
                    AMBER_50, AMBER_200, AMBER_800, 7.5f, 8f);
            case UNAUTHORIZED -> addCallout(document,
                    "Catálogo no autorizó la consulta de inventario al emitir el acta. Esto no confirma ausencia de reservas.",
                    AMBER_50, AMBER_200, AMBER_800, 7.5f, 8f);
            case NOT_PROVIDED, AVAILABLE -> addCallout(document,
                    "Información de equipamiento no incluida (consulta de Catálogo no realizada).",
                    SLATE_50, SLATE_200, SLATE_700, 7.5f, 8f);
        }
    }

    private void renderCrewCoordinationSection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        addCleanHeading(document, "COORDINACIÓN DE CUADRILLA");

        CrewAssignmentDTO crew = doc.crewAssignment();
        CrewTicketRecordDTO ticket = doc.crewTicket();

        boolean hasCrew = crew != null && (!isBlank(crew.crewName()) || !isBlank(crew.crewId()));
        boolean hasTicket = ticket != null;

        if (!hasCrew && !hasTicket) {
            addCallout(document,
                    "Sin cuadrilla asignada ni ticket de montaje registrado para esta producción.",
                    SLATE_50, SLATE_200, SLATE_700, 7.5f, 8f);
            return;
        }

        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        box.setSpacingAfter(2.5f);
        box.setKeepTogether(true);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(SLATE_50);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(SLATE_200);
        cell.setBorderWidth(0.6f);
        cell.setPadding(6f);

        Paragraph meta = new Paragraph();
        meta.setLeading(10.5f);
        if (hasCrew) {
            meta.add(new Chunk("Cuadrilla asignada: ", new Font(boldBaseFont, 7.7f, Font.BOLD, PRIMARY_NAVY)));
            if (!isBlank(crew.crewName())) {
                meta.add(new Chunk(crew.crewName(), new Font(regularBaseFont, 7.7f, Font.NORMAL, SLATE_900)));
                if (!isBlank(crew.crewId())) {
                    meta.add(new Chunk(" (" + crew.crewId() + ")", new Font(courierRegularFont, 7.3f, Font.NORMAL, SLATE_700)));
                }
            } else {
                meta.add(new Chunk(crew.crewId(), new Font(courierRegularFont, 7.5f, Font.NORMAL, SLATE_900)));
            }
        } else {
            meta.add(new Chunk("Cuadrilla: ", new Font(boldBaseFont, 7.7f, Font.BOLD, PRIMARY_NAVY)));
            meta.add(new Chunk("No informada de forma estructurada en el comando", new Font(regularBaseFont, 7.6f, Font.NORMAL, SLATE_700)));
        }

        if (hasTicket) {
            meta.add(new Chunk("   ·   ", new Font(regularBaseFont, 7.6f, Font.NORMAL, SLATE_500)));
            String ticketLabel = ticket.ticketId() != null ? "Ticket #" + ticket.ticketId() : "Ticket registrado";
            meta.add(new Chunk(ticketLabel, new Font(courierBoldFont, 7.5f, Font.BOLD, PRIMARY_NAVY)));
            if (ticket.createdAt() != null) {
                meta.add(new Chunk("   ·   Emitido: " + LOCAL_DATE_TIME_FMT.format(ticket.createdAt()),
                        new Font(regularBaseFont, 7.5f, Font.NORMAL, SLATE_700)));
            }
            if (!isBlank(ticket.ticketStatus())) {
                meta.add(new Chunk("   ·   Estado: " + translateTicketStatus(ticket.ticketStatus()),
                        new Font(boldBaseFont, 7.5f, Font.BOLD, SLATE_700)));
            }
        } else {
            meta.add(new Chunk("   ·   Sin ticket de montaje registrado",
                    new Font(regularBaseFont, 7.5f, Font.NORMAL, SLATE_500)));
        }
        cell.addElement(meta);

        if (hasTicket && !isBlank(ticket.instructions())) {
            Paragraph instr = new Paragraph("Instrucciones registradas: " + ticket.instructions(),
                    new Font(regularBaseFont, 7.5f, Font.NORMAL, SLATE_700));
            instr.setLeading(10.2f);
            instr.setSpacingBefore(3f);
            cell.addElement(instr);
        }

        box.addCell(cell);
        document.add(box);
        addFootnoteNote(document,
                "El registro de cuadrilla o ticket refleja exclusivamente lo almacenado en plataforma; no acredita aceptación ni ejecución física en terreno.",
                8f);
    }

    private void renderResponsiblesAndConformitySection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        addCleanHeading(document, "REGISTRO DE RESPONSABLES Y CONFORMIDAD");

        String closingActor = resolveClosingActorExecutiveDisplay(doc.traceability());
        String closedDisplay = formatClosedAtDisplay(doc.production());
        String organizerDisplay = resolveOrganizerDisplay(doc.organizer());
        String maskedEmail = (doc.organizer() != null && !isBlank(doc.organizer().maskedEmail()))
                ? doc.organizer().maskedEmail() : null;
        String conformityStatus = ProductionClosureDocumentBuilder.ORGANIZER_CONFORMITY_STATUS;

        PdfPTable grid = new PdfPTable(3);
        grid.setWidthPercentage(100);
        grid.setWidths(new float[]{49f, 2f, 49f});
        grid.setKeepTogether(true);
        grid.setSpacingAfter(6f);

        PdfPCell c1 = new PdfPCell();
        c1.setBorder(Rectangle.BOX);
        c1.setBorderColor(SLATE_300);
        c1.setBorderWidth(0.6f);
        c1.setPadding(7f);

        Paragraph p1Title = new Paragraph("RESPONSABLE DE CIERRE REGISTRADO",
                new Font(boldBaseFont, 7.7f, Font.BOLD, PRIMARY_NAVY));
        p1Title.setSpacingAfter(3f);
        c1.addElement(p1Title);

        Paragraph p1Body = new Paragraph(
                "Usuario registrado: " + closingActor + "\n"
                + "Fecha de cierre: " + closedDisplay + "\n"
                + "Autenticación: Microsoft Entra ID (sin firma digital PDF)",
                new Font(regularBaseFont, 7.6f, Font.NORMAL, SLATE_700));
        p1Body.setLeading(10.8f);
        c1.addElement(p1Body);

        PdfPCell spacer = new PdfPCell();
        spacer.setBorder(Rectangle.NO_BORDER);

        PdfPCell c2 = new PdfPCell();
        c2.setBackgroundColor(AMBER_50);
        c2.setBorder(Rectangle.BOX);
        c2.setBorderColor(AMBER_200);
        c2.setBorderWidth(0.75f);
        c2.setPadding(7f);

        Paragraph p2Title = new Paragraph("ORGANIZADOR ASOCIADO Y CONFORMIDAD",
                new Font(boldBaseFont, 7.7f, Font.BOLD, PRIMARY_NAVY));
        p2Title.setSpacingAfter(3f);
        c2.addElement(p2Title);

        StringBuilder orgLines = new StringBuilder("Organizador: ").append(organizerDisplay);
        if (maskedEmail != null) {
            orgLines.append("\nCanal de contacto: ").append(maskedEmail);
        }
        Paragraph p2Body = new Paragraph(orgLines.toString(),
                new Font(regularBaseFont, 7.6f, Font.NORMAL, SLATE_700));
        p2Body.setLeading(10.8f);
        c2.addElement(p2Body);

        Paragraph p2Status = new Paragraph(conformityStatus,
                new Font(boldBaseFont, 7.7f, Font.BOLD, AMBER_800));
        p2Status.setSpacingBefore(3.5f);
        c2.addElement(p2Status);

        grid.addCell(c1);
        grid.addCell(spacer);
        grid.addCell(c2);
        document.add(grid);
    }

    private void renderScopeNoticeSection(Document document) throws DocumentException {
        PdfPTable notice = new PdfPTable(1);
        notice.setWidthPercentage(100);
        notice.setSpacingBefore(4f);
        notice.setKeepTogether(true);

        PdfPCell cell = new PdfPCell(new Phrase(
                "AVISO DE ALCANCE: " + ProductionClosureDocumentBuilder.SCOPE_NOTICE,
                new Font(regularBaseFont, 7.2f, Font.NORMAL, SLATE_500)));
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColor(SLATE_300);
        cell.setBorderWidth(0.6f);
        cell.setPaddingTop(6f);
        cell.setPaddingLeft(0f);
        cell.setPaddingRight(0f);
        cell.setPaddingBottom(0f);
        notice.addCell(cell);
        document.add(notice);
    }

    // =========================================================================
    // PARTE 2: ANEXO TÉCNICO Y DE AUDITORÍA (PÁGINA 2+)
    // =========================================================================

    private void renderAnnexHeaderBanner(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        Long prodId = doc.production() != null && doc.production().productionId() != null
                ? doc.production().productionId() : 0L;
        String folio = formatFolio(prodId);
        String docCode = resolveDocumentCode(doc, prodId);
        String emittedUtc = formatInstantUtc(doc.metadata() != null ? doc.metadata().generatedAt() : null);

        PdfPTable banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(10f);
        banner.setKeepTogether(true);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(PRIMARY_NAVY);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(8.5f);
        cell.setPaddingBottom(8.5f);
        cell.setPaddingLeft(12f);
        cell.setPaddingRight(12f);

        Paragraph title = new Paragraph("ANEXO TÉCNICO · BITÁCORA DE AUDITORÍA Y TRAZABILIDAD DISTRIBUIDA",
                new Font(boldBaseFont, 9.6f, Font.BOLD, Color.WHITE));
        Paragraph sub = new Paragraph(
                "Documento asociado: " + docCode + " (" + folio + ")  ·  Producción #" + prodId + "  ·  Emisión: " + emittedUtc,
                new Font(regularBaseFont, 7.5f, Font.NORMAL, SLATE_200));
        sub.setSpacingBefore(2f);

        cell.addElement(title);
        cell.addElement(sub);
        banner.addCell(cell);
        document.add(banner);
    }

    private void renderAnnexTimelineSection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        addCleanHeading(document, "A. BITÁCORA DE ESTADOS REGISTRADA (APACHE KAFKA · 3 BROKERS / ZOOKEEPER)");

        List<OperationalTimelineItemDTO> timeline = doc.timeline();
        if (timeline == null || timeline.isEmpty()) {
            addCallout(document,
                    "Sin eventos de bitácora provistos en el comando recibido. No se completan ni fabrican transiciones ausentes.",
                    SLATE_50, SLATE_200, SLATE_700, 7.5f, 10f);
            return;
        }

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{5.5f, 26.5f, 20.0f, 20.5f, 27.5f});
        table.setHeaderRows(1);
        table.setSplitRows(false);
        table.setSplitLate(true);
        table.setSpacingAfter(3f);

        addAnnexTh(table, "Sec.", Element.ALIGN_CENTER);
        addAnnexTh(table, "Transición", Element.ALIGN_LEFT);
        addAnnexTh(table, "Fecha y hora (UTC)", Element.ALIGN_CENTER);
        addAnnexTh(table, "Actor registrado", Element.ALIGN_LEFT);
        addAnnexTh(table, "Event ID", Element.ALIGN_CENTER);

        for (int i = 0; i < timeline.size(); i++) {
            OperationalTimelineItemDTO item = timeline.get(i);
            Color bg = (i % 2 == 1) ? SLATE_50 : Color.WHITE;

            String seq = String.format("%02d", item.sequenceIndex());
            String from = !isBlank(item.fromStatus()) ? item.fromStatus() : "INICIO";
            String to = !isBlank(item.toStatus()) ? item.toStatus() : "N/A";
            String ts = item.occurredAt() != null ? UTC_DATE_TIME_FMT.format(item.occurredAt()) + " UTC" : "No informado";
            String actor = resolveTimelineActorDisplay(item);
            boolean actorMono = isBlank(item.actorDisplayName());
            String eventId = !isBlank(item.eventId()) ? item.eventId() : "No informado";

            addAnnexTd(table, seq, Element.ALIGN_CENTER, bg, true, 7.2f);
            addTransitionTd(table, from, to, bg, 7.3f);
            addAnnexTd(table, ts, Element.ALIGN_CENTER, bg, true, 6.8f);
            addAnnexTd(table, actor, Element.ALIGN_LEFT, bg, actorMono, actorMono ? 6.8f : 7.4f);
            addAnnexTd(table, eventId, Element.ALIGN_CENTER, bg, true, 6.2f);
        }

        document.add(table);
        addFootnoteNote(document,
                "Secuencia construida exclusivamente desde eventos persistidos (Outbox/Kafka). Los nombres legibles proceden del evento persistido; Notify no realiza una nueva consulta a Entra ID ni fabrica transiciones ausentes.",
                10f);
    }

    private void renderAnnexMessagingSection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        TechnicalTraceabilityDTO trace = doc.traceability();
        CrewTicketRecordDTO ticket = doc.crewTicket();

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{33f, 67f});
        table.setSplitRows(false);
        table.setSplitLate(true);
        table.setKeepTogether(true);
        table.setSpacingBefore(3f);
        table.setSpacingAfter(10f);

        addEmbeddedHeadingRow(table, "B. CORRELACIÓN DE MENSAJERÍA KAFKA Y RABBITMQ", 2);

        addTechKV(table, "Arquitectura de mensajería",
                "Apache Kafka (3 brokers · ZooKeeper) y RabbitMQ (exchange cmd.direct · routing key quote.gen)");
        addTechKV(table, "Evento de cierre Kafka", formatOptionalId(trace != null ? trace.kafkaClosingEventId() : null));
        addTechKV(table, "Comando RabbitMQ (quote.gen)", formatOptionalId(trace != null ? trace.rabbitmqCommandEventId() : null));
        addTechKV(table, "Routing key", "quote.gen");
        addTechKV(table, "Trace ID", formatOptionalId(trace != null ? trace.traceId() : null));
        addTechKV(table, "Correlation ID", formatOptionalId(trace != null ? trace.correlationId() : null));
        addTechKV(table, "Event ID ticket de cuadrilla",
                (ticket != null && !isBlankOrNa(ticket.ticketEventId())) ? ticket.ticketEventId() : "Sin ticket registrado");

        if (doc.equipment() != null && !doc.equipment().isEmpty()) {
            String catalogSummary = doc.equipment().stream()
                    .map(eq -> {
                        String ref = eq.reservationId() != null
                                ? String.format("RES-%03d / EQ-%03d", eq.reservationId(), eq.equipmentId() != null ? eq.equipmentId() : 0L)
                                : (eq.equipmentId() != null ? String.format("EQ-%03d", eq.equipmentId()) : "EQ-N/A");
                        String st = !isBlank(eq.reservationStatus()) ? eq.reservationStatus() : "SIN_ESTADO";
                        return ref + " (" + st + ")";
                    })
                    .collect(Collectors.joining(", "));
            addTechKV(table, "Reservas Catálogo (códigos internos)", catalogSummary);
        }

        document.add(table);
    }

    private void renderAnnexTechnicalIdentitiesSection(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        TechnicalTraceabilityDTO trace = doc.traceability();
        OrganizerIdentityDTO org = doc.organizer();

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{33f, 67f});
        table.setSplitRows(false);
        table.setSplitLate(true);
        table.setKeepTogether(true);
        table.setSpacingBefore(3f);
        table.setSpacingAfter(10f);

        addEmbeddedHeadingRow(table, "C. IDENTIDADES TÉCNICAS (ENMASCARADAS PARA AUDITORÍA)", 2);

        String rawActorOid = trace != null ? trace.closingActorOid() : null;
        String rawOrgId = org != null ? org.organizerId() : null;
        String sourceCode = (org != null && !isBlank(org.resolutionSource())) ? org.resolutionSource() : "No informado";

        addTechKV(table, "Identidad técnica actor de cierre", maskTechnicalIdentity(rawActorOid));
        addTechKV(table, "Identidad técnica organizador", maskTechnicalIdentity(rawOrgId));
        addTechKV(table, "Origen de resolución organizador", sourceCode);

        document.add(table);
    }

    private void renderAnnexIntegritySection(Document document, ProductionClosureDocumentDTO doc, String canonicalSha256)
            throws DocumentException {
        Long prodId = doc.production() != null && doc.production().productionId() != null
                ? doc.production().productionId() : 0L;
        String docCode = resolveDocumentCode(doc, prodId);
        String version = resolveVersion(doc);
        String emitter = (doc.metadata() != null && !isBlank(doc.metadata().emittingService()))
                ? doc.metadata().emittingService() : "ms-eventomax-notify";

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{33f, 67f});
        table.setSplitRows(false);
        table.setSplitLate(true);
        table.setKeepTogether(true);
        table.setSpacingBefore(3f);
        table.setSpacingAfter(8f);

        addEmbeddedHeadingRow(table, "D. INTEGRIDAD DOCUMENTAL Y REFERENCIA", 2);

        addTechKV(table, "Referencia documental", docCode + " · Versión " + version + " · Emisor: " + emitter);
        addTechKV(table, "SHA-256 de datos canónicos (generador)", canonicalSha256);
        addTechKV(table, "SHA-256 de bytes finales del PDF (ClosurePdf)",
                "Calculado y verificado externamente por ClosurePdf sobre los bytes finales del archivo PDF al persistir y recuperar el documento en el almacenamiento durable. Es distinto de la huella de datos canónicos anterior.");
        addTechKV(table, "Verificación externa",
                "Esta versión no incluye portal público de validación externa, código de barras ni firma digital.");

        document.add(table);

        addCallout(document,
                "NOTA TÉCNICA: Este anexo acompaña al Acta de Cierre Operativo para fines de auditoría distribuida en la plataforma EventoMax. La autenticación de usuarios en Microsoft Entra ID no equivale a firma digital PDF ni a aceptación formal del organizador.",
                SLATE_100, SLATE_300, SLATE_700, 7.3f, 0f);
    }

    // =========================================================================
    // RESOLUCIÓN DE IDENTIDADES, FORMATOS Y TRADUCCIONES
    // =========================================================================

    static String resolveOrganizerDisplay(OrganizerIdentityDTO org) {
        if (org == null) {
            return "Nombre del organizador no disponible";
        }
        String displayName = !isBlank(org.organizerDisplayName()) ? org.organizerDisplayName().trim() : null;
        String contactName = !isBlank(org.contactName()) ? org.contactName().trim() : null;
        String source = !isBlank(org.resolutionSource()) ? org.resolutionSource().trim().toUpperCase(Locale.ROOT) : null;

        if (displayName != null) {
            if ("ENTRA_GRAPH".equals(source)) {
                return displayName + " (Resuelto · Microsoft Graph)";
            }
            if ("EXTERNAL_CONTACT".equals(source)) {
                return displayName + " (Contacto externo registrado)";
            }
            return displayName;
        }
        if (contactName != null) {
            if ("EXTERNAL_CONTACT".equals(source)) {
                return contactName + " (Contacto externo registrado)";
            }
            return contactName + " (Contacto registrado)";
        }
        return "Nombre del organizador no disponible";
    }

    static String resolveClosingActorExecutiveDisplay(TechnicalTraceabilityDTO trace) {
        if (trace != null && !isBlank(trace.closingActorDisplayName())) {
            return trace.closingActorDisplayName().trim();
        }
        return "Usuario autenticado (referencia técnica en Anexo)";
    }

    static String resolveTimelineActorDisplay(OperationalTimelineItemDTO item) {
        if (item == null) {
            return "No informado";
        }
        if (!isBlank(item.actorDisplayName())) {
            return item.actorDisplayName().trim();
        }
        if (!isBlank(item.actorOid())) {
            return maskTechnicalIdentityCompact(item.actorOid().trim());
        }
        return "No informado";
    }

    static String translateResolutionSource(String source) {
        if (isBlank(source)) {
            return "No informada";
        }
        return switch (source.trim().toUpperCase(Locale.ROOT)) {
            case "ENTRA_GRAPH" -> "Microsoft Graph (Entra ID)";
            case "EXTERNAL_CONTACT" -> "Contacto externo registrado";
            case "UNRESOLVED" -> "Identidad no resuelta";
            default -> source.trim();
        };
    }

    static String translateReservationStatus(String status) {
        if (isBlank(status)) {
            return "Estado no informado";
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "RESERVED" -> "Reservado (activo en catálogo)";
            case "RELEASED" -> "Liberado";
            case "COMPENSATED" -> "Compensado / cancelado";
            case "CANCELLED", "CANCELED" -> "Cancelado";
            case "PENDING" -> "Pendiente";
            default -> status.trim();
        };
    }

    static String translateTicketStatus(String status) {
        if (isBlank(status)) {
            return "No informado";
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "EN_MONTAJE" -> "En montaje (registrado)";
            case "CERRADO" -> "Cerrado (registrado)";
            case "CONFIRMADO" -> "Confirmado (registrado)";
            case "EN_EJECUCION" -> "En ejecución (registrado)";
            default -> status.trim();
        };
    }

    static String maskTechnicalIdentity(String raw) {
        if (isBlank(raw) || "N/A".equalsIgnoreCase(raw.trim())) {
            return "No informada";
        }
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1) {
            String left = trimmed.substring(0, colon);
            String right = trimmed.substring(colon + 1);
            return "tid:" + maskToken(left) + " / oid:" + maskToken(right) + " (enmascarado)";
        }
        if (trimmed.contains("@")) {
            return maskEmail(trimmed);
        }
        if (UUID_PATTERN.matcher(trimmed).matches() || trimmed.length() > 16) {
            return maskToken(trimmed) + " (enmascarado)";
        }
        return trimmed;
    }

    static String maskTechnicalIdentityCompact(String raw) {
        if (isBlank(raw) || "N/A".equalsIgnoreCase(raw.trim())) {
            return "No informado";
        }
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1) {
            String right = trimmed.substring(colon + 1);
            return "oid:" + maskToken(right);
        }
        if (trimmed.contains("@")) {
            return maskEmail(trimmed);
        }
        if (UUID_PATTERN.matcher(trimmed).matches() || trimmed.length() > 16) {
            return maskToken(trimmed);
        }
        return trimmed;
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 10) {
            return token != null ? token : "";
        }
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) {
            return "***" + email.substring(at);
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private static String formatNumericRate(BigDecimal value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        DecimalFormat df = new DecimalFormat("#,##0.00", symbols);
        return df.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String formatScheduledAt(LocalDateTime scheduledAt) {
        if (scheduledAt == null) {
            return "No informada";
        }
        return SHORT_LOCAL_FMT.format(scheduledAt) + " (Hora local registrada)";
    }

    private static String formatLocalDateTime(LocalDateTime dt, String fallback) {
        return dt != null ? LOCAL_DATE_TIME_FMT.format(dt) : fallback;
    }

    private static String formatClosedAtDisplay(ProductionIdentityDTO prod) {
        if (prod == null) {
            return "No informado";
        }
        if (!isBlank(prod.closedAtFormatted())) {
            return prod.closedAtFormatted().trim();
        }
        if (prod.closedAt() != null) {
            return LOCAL_DATE_TIME_FMT.format(prod.closedAt());
        }
        return "No informado";
    }

    private static String formatInstantUtc(Instant instant) {
        return instant != null ? UTC_DATE_TIME_FMT.format(instant) + " UTC" : "No informada";
    }

    private static String formatFolio(Long prodId) {
        long safe = prodId != null && prodId > 0 ? prodId : 0L;
        return String.format("FOLIO N° %06d", safe);
    }

    private static String resolveDocumentCode(ProductionClosureDocumentDTO doc, Long prodId) {
        if (doc.metadata() != null && !isBlank(doc.metadata().documentCode())) {
            return doc.metadata().documentCode();
        }
        return "EMX-ACTA-PRD-" + prodId;
    }

    private static String resolveVersion(ProductionClosureDocumentDTO doc) {
        if (doc.metadata() != null && !isBlank(doc.metadata().version())) {
            return doc.metadata().version();
        }
        return ProductionClosureDocumentBuilder.DOCUMENT_VERSION;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isBlankOrNa(String value) {
        return isBlank(value) || "N/A".equalsIgnoreCase(value.trim());
    }

    private static String formatOptionalId(String value) {
        return isBlankOrNa(value) ? "No informado" : value.trim();
    }

    // =========================================================================
    // HUELLA SHA-256 DE DATOS CANÓNICOS (DISTINTA DEL HASH DE BYTES DE CLOSUREPDF)
    // =========================================================================

    static String computeCanonicalSha256(ProductionClosureDocumentDTO doc) {
        StringBuilder sb = new StringBuilder();
        if (doc.metadata() != null) {
            sb.append(doc.metadata().documentCode()).append('|')
              .append(doc.metadata().version()).append('|')
              .append(doc.metadata().generatedAt()).append('|');
        }
        if (doc.production() != null) {
            sb.append(doc.production().productionId()).append('|')
              .append(doc.production().productionName()).append('|')
              .append(doc.production().status()).append('|')
              .append(doc.production().scheduledAt()).append('|')
              .append(doc.production().location()).append('|')
              .append(doc.production().closedAtFormatted()).append('|');
        }
        if (doc.organizer() != null) {
            sb.append(doc.organizer().organizerId()).append('|')
              .append(doc.organizer().organizerDisplayName()).append('|')
              .append(doc.organizer().resolutionSource()).append('|')
              .append(doc.organizer().maskedEmail()).append('|');
        }
        if (doc.crewAssignment() != null) {
            sb.append(doc.crewAssignment().crewId()).append('|')
              .append(doc.crewAssignment().crewName()).append('|');
        }
        if (doc.traceability() != null) {
            sb.append(doc.traceability().rabbitmqCommandEventId()).append('|')
              .append(doc.traceability().kafkaClosingEventId()).append('|')
              .append(doc.traceability().traceId()).append('|')
              .append(doc.traceability().correlationId()).append('|')
              .append(doc.traceability().closingActorOid()).append('|')
              .append(doc.traceability().closingActorDisplayName()).append('|');
        }
        if (doc.services() != null) {
            for (var s : doc.services()) {
                sb.append("S:").append(s.serviceId()).append(':').append(s.unitPrice()).append('|');
            }
        }
        if (doc.equipment() != null) {
            for (var e : doc.equipment()) {
                sb.append("E:").append(e.equipmentId()).append(':').append(e.reservedQuantity())
                  .append(':').append(e.reservationStatus()).append(':').append(e.reservationId()).append('|');
            }
        }
        if (doc.timeline() != null) {
            for (var t : doc.timeline()) {
                sb.append("T:").append(t.sequenceIndex()).append(':').append(t.fromStatus()).append("->")
                  .append(t.toStatus()).append(':').append(t.eventId()).append(':').append(t.actorDisplayName()).append('|');
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la huella SHA-256 de datos canónicos", e);
        }
    }

    // =========================================================================
    // COMPONENTES REUTILIZABLES DE DISEÑO C
    // =========================================================================

    private void addCleanHeading(Document document, String title) throws DocumentException {
        PdfPTable headingTable = new PdfPTable(1);
        headingTable.setWidthPercentage(100);
        headingTable.setSpacingBefore(3f);
        headingTable.setSpacingAfter(0f);
        headingTable.setKeepTogether(true);

        PdfPCell cell = new PdfPCell(new Phrase(title, new Font(boldBaseFont, 8.2f, Font.BOLD, PRIMARY_NAVY)));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingLeft(0f);
        cell.setPaddingRight(0f);
        cell.setPaddingTop(1f);
        cell.setPaddingBottom(4.5f);
        headingTable.addCell(cell);
        document.add(headingTable);
    }

    private void addEmbeddedHeadingRow(PdfPTable table, String title, int colspan) {
        PdfPCell cell = new PdfPCell(new Phrase(title, new Font(boldBaseFont, 8.2f, Font.BOLD, PRIMARY_NAVY)));
        cell.setColspan(colspan);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingLeft(0f);
        cell.setPaddingRight(0f);
        cell.setPaddingTop(1f);
        cell.setPaddingBottom(4.5f);
        table.addCell(cell);
    }

    private void addCleanKV(PdfPTable table, String key, String value, boolean boldVal) {
        PdfPCell lc = new PdfPCell(new Phrase(key, new Font(regularBaseFont, 7.5f, Font.NORMAL, SLATE_500)));
        lc.setBorder(Rectangle.BOTTOM);
        lc.setBorderColor(SLATE_200);
        lc.setBorderWidth(0.5f);
        lc.setPadding(3.8f);
        lc.setPaddingLeft(1f);
        table.addCell(lc);

        Font vf = new Font(boldVal ? boldBaseFont : regularBaseFont, 7.9f, boldVal ? Font.BOLD : Font.NORMAL, SLATE_900);
        PdfPCell vc = new PdfPCell(new Phrase(value, vf));
        vc.setBorder(Rectangle.BOTTOM);
        vc.setBorderColor(SLATE_200);
        vc.setBorderWidth(0.5f);
        vc.setPadding(3.8f);
        table.addCell(vc);
    }

    private void addCleanFullRowKV(PdfPTable table, String key, String value, boolean boldVal) {
        PdfPCell lc = new PdfPCell(new Phrase(key, new Font(regularBaseFont, 7.5f, Font.NORMAL, SLATE_500)));
        lc.setBorder(Rectangle.BOTTOM);
        lc.setBorderColor(SLATE_200);
        lc.setBorderWidth(0.5f);
        lc.setPadding(3.8f);
        lc.setPaddingLeft(1f);
        table.addCell(lc);

        Font vf = new Font(boldVal ? boldBaseFont : regularBaseFont, 7.9f, boldVal ? Font.BOLD : Font.NORMAL, SLATE_900);
        PdfPCell vc = new PdfPCell(new Phrase(value, vf));
        vc.setColspan(3);
        vc.setBorder(Rectangle.BOTTOM);
        vc.setBorderColor(SLATE_200);
        vc.setBorderWidth(0.5f);
        vc.setPadding(3.8f);
        table.addCell(vc);
    }

    private void addCleanTh(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(boldBaseFont, 7.3f, Font.BOLD, SLATE_700)));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(PRIMARY_NAVY);
        cell.setBorderWidth(1.0f);
        cell.setHorizontalAlignment(align);
        cell.setPaddingTop(3.8f);
        cell.setPaddingBottom(4.2f);
        table.addCell(cell);
    }

    private void addCleanTd(PdfPTable table, String text, int align, boolean mono, float size) {
        Font font = mono
                ? new Font(courierRegularFont, size - 0.3f, Font.NORMAL, SLATE_900)
                : new Font(regularBaseFont, size, Font.NORMAL, SLATE_900);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(SLATE_200);
        cell.setBorderWidth(0.5f);
        cell.setHorizontalAlignment(align);
        cell.setPaddingTop(4.0f);
        cell.setPaddingBottom(4.0f);
        table.addCell(cell);
    }

    private void addCleanTotalRow(PdfPTable table, String label, String value, int colspan) {
        PdfPCell l = new PdfPCell(new Phrase(label, new Font(boldBaseFont, 7.6f, Font.BOLD, PRIMARY_NAVY)));
        l.setColspan(colspan);
        l.setHorizontalAlignment(Element.ALIGN_RIGHT);
        l.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        l.setBorderColor(SLATE_300);
        l.setBorderWidth(0.8f);
        l.setBackgroundColor(SLATE_50);
        l.setPadding(4.8f);
        table.addCell(l);

        PdfPCell v = new PdfPCell(new Phrase(value, new Font(courierBoldFont, 8.3f, Font.BOLD, PRIMARY_NAVY)));
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        v.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        v.setBorderColor(SLATE_300);
        v.setBorderWidth(0.8f);
        v.setBackgroundColor(SLATE_50);
        v.setPadding(4.8f);
        table.addCell(v);
    }

    private void addAnnexTh(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(boldBaseFont, 7.0f, Font.BOLD, PRIMARY_NAVY)));
        cell.setBackgroundColor(SLATE_200);
        cell.setBorderColor(SLATE_300);
        cell.setBorderWidth(0.5f);
        cell.setHorizontalAlignment(align);
        cell.setPadding(3.6f);
        table.addCell(cell);
    }

    private void addAnnexTd(PdfPTable table, String text, int align, Color bg, boolean mono, float size) {
        Font font = mono
                ? new Font(courierRegularFont, size, Font.NORMAL, SLATE_900)
                : new Font(regularBaseFont, size, Font.NORMAL, SLATE_900);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(SLATE_200);
        cell.setBorderWidth(0.45f);
        cell.setHorizontalAlignment(align);
        cell.setPadding(3.2f);
        table.addCell(cell);
    }

    private void addTransitionTd(PdfPTable table, String from, String to, Color bg, float size) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(from, new Font(regularBaseFont, size, Font.NORMAL, SLATE_700)));
        phrase.add(new Chunk(" " + (char) 222 + " ", new Font(zapfDingbatsFont, size - 0.3f, Font.NORMAL, PRIMARY_BLUE)));
        boolean isClosed = "CERRADO".equalsIgnoreCase(to);
        phrase.add(new Chunk(to, new Font(boldBaseFont, size, Font.BOLD, isClosed ? EMERALD_700 : SLATE_900)));

        PdfPCell cell = new PdfPCell(phrase);
        cell.setBackgroundColor(bg);
        cell.setBorderColor(SLATE_200);
        cell.setBorderWidth(0.45f);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPadding(3.2f);
        table.addCell(cell);
    }

    private void addTechKV(PdfPTable table, String key, String value) {
        PdfPCell lc = new PdfPCell(new Phrase(key, new Font(boldBaseFont, 7.3f, Font.BOLD, SLATE_700)));
        lc.setBackgroundColor(SLATE_50);
        lc.setBorderColor(SLATE_200);
        lc.setBorderWidth(0.5f);
        lc.setPadding(4.0f);
        table.addCell(lc);

        PdfPCell vc = new PdfPCell(new Phrase(value, new Font(courierRegularFont, 7.0f, Font.NORMAL, SLATE_900)));
        vc.setBorderColor(SLATE_200);
        vc.setBorderWidth(0.5f);
        vc.setPadding(4.0f);
        table.addCell(vc);
    }

    private void addCallout(Document document, String text, Color bg, Color border, Color fg,
                            float fontSize, float spaceAfter) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingAfter(spaceAfter);
        table.setKeepTogether(true);

        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(regularBaseFont, fontSize, Font.NORMAL, fg)));
        cell.setBackgroundColor(bg);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(border);
        cell.setBorderWidth(0.6f);
        cell.setPadding(5.5f);
        table.addCell(cell);
        document.add(table);
    }

    private void addFootnoteNote(Document document, String text, float spaceAfter) throws DocumentException {
        Paragraph p = new Paragraph(text, new Font(regularBaseFont, 6.8f, Font.ITALIC, SLATE_500));
        p.setLeading(9.0f);
        p.setSpacingAfter(spaceAfter);
        document.add(p);
    }

    // =========================================================================
    // ENCABEZADO DE CONTINUACIÓN Y PIE DE PÁGINA ("Página X de N")
    // =========================================================================

    private static class HeaderFooterPageEvent extends PdfPageEventHelper {
        private final BaseFont regularFont;
        private final BaseFont boldFont;
        private final BaseFont monoFont;
        private final byte[] symbolBytes;
        private final String folio;
        private final String docCode;
        private final String version;
        private final boolean previewMode;
        private PdfTemplate totalPages;

        HeaderFooterPageEvent(
                BaseFont regularFont,
                BaseFont boldFont,
                BaseFont monoFont,
                byte[] symbolBytes,
                ProductionClosureDocumentDTO doc,
                boolean previewMode
        ) {
            this.regularFont = regularFont;
            this.boldFont = boldFont;
            this.monoFont = monoFont;
            this.symbolBytes = symbolBytes;
            Long prodId = doc.production() != null && doc.production().productionId() != null
                    ? doc.production().productionId() : 0L;
            this.folio = formatFolio(prodId);
            this.docCode = resolveDocumentCode(doc, prodId);
            this.version = resolveVersion(doc);
            this.previewMode = previewMode;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            this.totalPages = writer.getDirectContent().createTemplate(24f, 12f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float left = document.left();
            float right = document.right();
            float top = document.top();
            float bottom = document.bottom() - 20f;
            int page = writer.getPageNumber();

            // Encabezado de continuación en página 2+
            if (page > 1) {
                float hy = top + 12f;
                if (symbolBytes != null) {
                    try {
                        Image sym = Image.getInstance(symbolBytes);
                        sym.scaleToFit(10f, 10f);
                        sym.setAbsolutePosition(left, hy - 1.5f);
                        cb.addImage(sym);
                    } catch (Exception ignored) {
                    }
                }
                cb.beginText();
                cb.setFontAndSize(boldFont, 7.2f);
                cb.setColorFill(PRIMARY_NAVY);
                cb.showTextAligned(Element.ALIGN_LEFT,
                        "EVENTOMAX · ACTA DE CIERRE OPERATIVO",
                        symbolBytes != null ? left + 14f : left, hy, 0);
                cb.setFontAndSize(monoFont, 6.8f);
                cb.setColorFill(SLATE_500);
                cb.showTextAligned(Element.ALIGN_RIGHT,
                        folio + " · " + docCode + " · v" + version, right, hy, 0);
                cb.endText();

                cb.setColorStroke(SLATE_300);
                cb.setLineWidth(0.45f);
                cb.moveTo(left, hy - 4.5f);
                cb.lineTo(right, hy - 4.5f);
                cb.stroke();
            }

            // Línea separadora de pie de página
            cb.setColorStroke(SLATE_300);
            cb.setLineWidth(0.5f);
            cb.moveTo(left, bottom + 10f);
            cb.lineTo(right, bottom + 10f);
            cb.stroke();

            String footerLeft = "EventoMax · " + docCode + " (" + folio + ") · Registro operativo"
                    + (previewMode ? " · VISTA PREVIA" : "");

            cb.beginText();
            cb.setFontAndSize(regularFont, 6.8f);
            cb.setColorFill(SLATE_500);
            cb.showTextAligned(Element.ALIGN_LEFT, footerLeft, left, bottom, 0);

            cb.setFontAndSize(regularFont, 7.1f);
            cb.setColorFill(SLATE_900);
            cb.showTextAligned(Element.ALIGN_RIGHT, "Página " + page + " de", right - 12f, bottom, 0);
            cb.endText();

            cb.addTemplate(totalPages, right - 9.5f, bottom);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalPages.beginText();
            totalPages.setFontAndSize(boldFont, 7.1f);
            totalPages.setColorFill(SLATE_900);
            totalPages.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPages.endText();
        }
    }
}
