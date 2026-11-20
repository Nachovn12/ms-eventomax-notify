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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * Generador empresarial de Actas de Cierre Operativo y Administrativo de Producción
 * implementado con OpenPDF 1.3.40 bajo estándar corporativo chileno (estilo Sovos Acepta / Buk / DTE Empresarial).
 * <p>
 * Características principales:
 * - Consolidación ejecutiva en 1 página A4 para el ciclo operativo estándar (paginación automática a N páginas en historiales extensos).
 * - Alcance estricto de datos reales de dominio (Caso 8 - EventoMax), eliminando filas ficticias o de relleno ("No registrado").
 * - Certificados paralelos de Firma Electrónica Simple autenticada vía OIDC Microsoft Entra ID para ambos Stakeholders
 *   (Responsable Operativo + Representante del Organizador Mandante) con micro-líneas de seguridad y timbre circular vectorial.
 * - Timbre Electrónico vectorial real PDF417 + Código Seguro de Verificación (CSV) + huella criptográfica SHA-256 canónica + Ley N° 19.799.
 */
@Component
public class OpenPdfClosureDocumentGenerator {

    private static final DateTimeFormatter UTC_DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Paleta Corporativa Impresión Empresarial EventoMax
    private static final Color PRIMARY_NAVY = new Color(3, 28, 67);        // #031C43
    private static final Color PRIMARY_BLUE = new Color(2, 79, 252);       // #024FFC
    private static final Color SLATE_900 = new Color(15, 23, 42);          // #0F172A
    private static final Color SLATE_700 = new Color(51, 65, 85);          // #334155
    private static final Color SLATE_500 = new Color(100, 116, 139);       // #64748B
    private static final Color SLATE_300 = new Color(203, 213, 225);       // #CBD5E1
    private static final Color SLATE_200 = new Color(226, 232, 240);       // #E2E8F0
    private static final Color SLATE_100 = new Color(241, 245, 249);       // #F1F5F9
    private static final Color SLATE_50 = new Color(248, 250, 252);        // #F8FAFC
    private static final Color GUILLOCHE_LINE = new Color(238, 242, 246);  // Micro-línea seguridad
    private static final Color EMERALD_700 = new Color(4, 120, 87);        // #047857
    private static final Color EMERALD_50 = new Color(236, 253, 245);      // #ECFDF5
    private static final Color EMERALD_200 = new Color(167, 243, 208);     // #A7F3D0
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
        Document document = new Document(PageSize.A4, 30f, 30f, 24f, 30f);

        String sha256 = computeCanonicalSha256(doc);
        Long prodId = doc.production() != null ? doc.production().productionId() : 0L;
        String csv = buildCsvCode(prodId, sha256);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
            writer.setPageEvent(new HeaderFooterPageEvent(regularBaseFont, boldBaseFont, courierRegularFont, symbolBytes, doc, csv));

            document.open();

            if (previewMode) {
                renderPreviewBanner(document);
            }

            // Cabecera institucional con Recuadro Normativo de Folio
            renderHeaderBanner(document, doc);

            // I. Identificación del Evento y Entidad Mandante (Alcance estricto Caso 8)
            renderSectionHeader(document, "I. IDENTIFICACIÓN DEL EVENTO Y ENTIDAD MANDANTE", "SISTEMA PRODUCCIONES");
            renderProductionIdentity(document, doc);

            // II. Consolidación de Inventario Reservado, Servicios Contratados y Montaje
            renderSectionHeader(document, "II. CONSOLIDACIÓN DE INVENTARIO, SERVICIOS Y MONTAJE", "CATÁLOGO · SERVICIOS · CUADRILLA");
            renderEquipmentServicesAndCrew(document, doc);

            // III. Bitácora Auditada del Ciclo de Vida Operacional (Kafka)
            if (doc.timeline() != null && !doc.timeline().isEmpty()) {
                renderSectionHeader(document, "III. BITÁCORA AUDITADA DEL CICLO DE VIDA OPERACIONAL", "TRAZABILIDAD APACHE KAFKA");
                renderTimeline(document, doc.timeline());
            }

            // IV. Certificación y Firma Electrónica Dual + Timbre PDF417 + Sello SHA-256
            renderSectionHeader(document, "IV. CERTIFICACIÓN Y FIRMA ELECTRÓNICA DE CONFORMIDAD", "MICROSOFT ENTRA ID OIDC · LEY 19.799");
            renderDualElectronicSignatures(document, doc, sha256);
            renderCryptographicSealAndPdf417(document, doc, sha256, csv);

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

    // =========================================================================
    // SECCIONES EDITORIALES EMPRESARIALES
    // =========================================================================

    private void renderPreviewBanner(Document document) throws DocumentException {
        PdfPTable bannerTable = new PdfPTable(1);
        bannerTable.setWidthPercentage(100);
        bannerTable.setSpacingAfter(3.5f);

        PdfPCell cell = new PdfPCell(new Phrase(
                "VISTA PREVIA — DATOS DE PRUEBA — NO VÁLIDO PARA CONFORMIDAD",
                new Font(boldBaseFont, 6.8f, Font.BOLD, PREVIEW_TEXT)));
        cell.setBackgroundColor(PREVIEW_BG);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(PREVIEW_BORDER);
        cell.setBorderWidth(0.5f);
        cell.setPadding(2.2f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        bannerTable.addCell(cell);
        document.add(bannerTable);
    }

    private void renderHeaderBanner(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(2);
        headerTable.setWidthPercentage(100);
        headerTable.setWidths(new float[]{62f, 38f});
        headerTable.setSpacingAfter(4.5f);

        // Columna Izquierda: Logotipo Oficial + Jerarquía Institucional
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.setPadding(0f);
        leftCell.setPaddingRight(8f);
        leftCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        if (logoBytes != null) {
            try {
                Image logoImg = Image.getInstance(logoBytes);
                logoImg.scaleToFit(126f, 42f);
                logoImg.setSpacingAfter(2.5f);
                leftCell.addElement(logoImg);
            } catch (Exception e) {
                renderFallbackBrandText(leftCell);
            }
        } else {
            renderFallbackBrandText(leftCell);
        }

        Paragraph masthead = new Paragraph("DIRECCIÓN DE OPERACIONES Y PRODUCCIÓN TÉCNICA",
                new Font(boldBaseFont, 6.4f, Font.BOLD, PRIMARY_BLUE));
        masthead.setLeading(8f);
        leftCell.addElement(masthead);

        String docTitle = (doc.metadata() != null && doc.metadata().documentTitle() != null)
                ? doc.metadata().documentTitle()
                : "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN";
        Paragraph title = new Paragraph(docTitle, new Font(boldBaseFont, 10.8f, Font.BOLD, PRIMARY_NAVY));
        title.setLeading(12.2f);
        title.setSpacingBefore(1f);
        title.setSpacingAfter(1.5f);
        leftCell.addElement(title);

        Paragraph sub = new Paragraph("Plataforma Integral de Gestión y Producción de Eventos Masivos · Registro Auditado",
                new Font(regularBaseFont, 7.1f, Font.NORMAL, SLATE_500));
        sub.setLeading(8.5f);
        leftCell.addElement(sub);

        // Columna Derecha: Recuadro Normativo Corporativo de Folio (3 niveles)
        Long prodId = (doc.production() != null && doc.production().productionId() != null)
                ? doc.production().productionId() : 0L;
        String code = (doc.metadata() != null && doc.metadata().documentCode() != null)
                ? doc.metadata().documentCode()
                : "EMX-ACTA-PRD-" + prodId;
        String ver = (doc.metadata() != null && doc.metadata().version() != null)
                ? doc.metadata().version()
                : "2.0";
        String genTime = (doc.metadata() != null && doc.metadata().generatedAt() != null)
                ? UTC_DATE_TIME_FMT.format(doc.metadata().generatedAt()) + " UTC"
                : "N/A";

        PdfPCell folioWrapper = new PdfPCell();
        folioWrapper.setBorder(Rectangle.BOX);
        folioWrapper.setBorderColor(PRIMARY_NAVY);
        folioWrapper.setBorderWidth(1.1f);
        folioWrapper.setPadding(0f);

        PdfPTable folioInner = new PdfPTable(1);
        folioInner.setWidthPercentage(100);

        // Franja superior azul marino
        PdfPCell topCap = new PdfPCell(new Phrase("EVENTOMAX OPERACIONES · CERTIFICADO OFICIAL",
                new Font(boldBaseFont, 6.5f, Font.BOLD, Color.WHITE)));
        topCap.setBackgroundColor(PRIMARY_NAVY);
        topCap.setBorder(Rectangle.NO_BORDER);
        topCap.setHorizontalAlignment(Element.ALIGN_CENTER);
        topCap.setPaddingTop(3.0f);
        topCap.setPaddingBottom(3.4f);
        folioInner.addCell(topCap);

        // Cuerpo central blanco con Folio destacado
        PdfPCell centerFolio = new PdfPCell();
        centerFolio.setBackgroundColor(Color.WHITE);
        centerFolio.setBorder(Rectangle.NO_BORDER);
        centerFolio.setPaddingTop(4f);
        centerFolio.setPaddingBottom(4.5f);

        Paragraph certType = new Paragraph("ACTA DE CIERRE OPERATIVO",
                new Font(boldBaseFont, 7.4f, Font.BOLD, PRIMARY_BLUE));
        certType.setAlignment(Element.ALIGN_CENTER);
        certType.setLeading(8.5f);
        centerFolio.addElement(certType);

        Paragraph folioNumber = new Paragraph(String.format("FOLIO N° %06d", prodId),
                new Font(courierBoldFont, 10.6f, Font.BOLD, PRIMARY_NAVY));
        folioNumber.setAlignment(Element.ALIGN_CENTER);
        folioNumber.setLeading(12.5f);
        folioNumber.setSpacingBefore(1.5f);
        centerFolio.addElement(folioNumber);
        folioInner.addCell(centerFolio);

        // Franja inferior gris pizarra con código y emisión UTC
        PdfPCell botMeta = new PdfPCell();
        botMeta.setBackgroundColor(SLATE_50);
        botMeta.setBorder(Rectangle.TOP);
        botMeta.setBorderColor(SLATE_300);
        botMeta.setBorderWidth(0.45f);
        botMeta.setPaddingTop(2.8f);
        botMeta.setPaddingBottom(3.2f);

        Paragraph pCode = new Paragraph("CÓD: " + code + "  ·  REV " + ver,
                new Font(courierRegularFont, 6.6f, Font.NORMAL, SLATE_700));
        pCode.setAlignment(Element.ALIGN_CENTER);
        pCode.setLeading(7.8f);
        botMeta.addElement(pCode);

        Paragraph pEmit = new Paragraph("EMISIÓN: " + genTime,
                new Font(regularBaseFont, 6.4f, Font.NORMAL, SLATE_500));
        pEmit.setAlignment(Element.ALIGN_CENTER);
        pEmit.setLeading(7.8f);
        botMeta.addElement(pEmit);
        folioInner.addCell(botMeta);

        folioWrapper.addElement(folioInner);

        headerTable.addCell(leftCell);
        headerTable.addCell(folioWrapper);
        document.add(headerTable);

        // Regla bicolor ejecutiva (#024FFC + #031C43)
        PdfPTable ruleTable = new PdfPTable(2);
        ruleTable.setWidthPercentage(100);
        ruleTable.setWidths(new float[]{28f, 72f});
        ruleTable.setSpacingAfter(4.5f);

        PdfPCell blueBar = new PdfPCell();
        blueBar.setBorder(Rectangle.BOTTOM);
        blueBar.setBorderColor(PRIMARY_BLUE);
        blueBar.setBorderWidth(1.6f);
        blueBar.setFixedHeight(1.6f);

        PdfPCell navyBar = new PdfPCell();
        navyBar.setBorder(Rectangle.BOTTOM);
        navyBar.setBorderColor(PRIMARY_NAVY);
        navyBar.setBorderWidth(1.6f);
        navyBar.setFixedHeight(1.6f);

        ruleTable.addCell(blueBar);
        ruleTable.addCell(navyBar);
        document.add(ruleTable);
    }

    private void renderFallbackBrandText(PdfPCell cell) {
        Paragraph brand = new Paragraph("EVENTOMAX", new Font(boldBaseFont, 14f, Font.BOLD, PRIMARY_NAVY));
        brand.setSpacingAfter(2f);
        cell.addElement(brand);
    }

    /**
     * Cabecera de sección ejecutiva con acento lateral eléctrico de 3pt y etiqueta de origen a la derecha.
     */
    private void renderSectionHeader(Document document, String title, String domainTag) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{72f, 28f});
        table.setSpacingBefore(2.0f);
        table.setSpacingAfter(2.2f);

        PdfPCell left = new PdfPCell(new Phrase(title, new Font(boldBaseFont, 7.6f, Font.BOLD, PRIMARY_NAVY)));
        left.setBackgroundColor(SLATE_100);
        left.setBorder(Rectangle.TOP | Rectangle.BOTTOM | Rectangle.LEFT);
        left.setBorderColorTop(SLATE_300);
        left.setBorderColorBottom(SLATE_300);
        left.setBorderColorLeft(PRIMARY_BLUE);
        left.setBorderWidthTop(0.45f);
        left.setBorderWidthBottom(0.45f);
        left.setBorderWidthLeft(2.8f);
        left.setPaddingTop(2.8f);
        left.setPaddingBottom(3.2f);
        left.setPaddingLeft(6f);

        PdfPCell right = new PdfPCell(new Phrase(domainTag, new Font(boldBaseFont, 6.0f, Font.BOLD, SLATE_500)));
        right.setBackgroundColor(SLATE_100);
        right.setBorder(Rectangle.TOP | Rectangle.BOTTOM | Rectangle.RIGHT);
        right.setBorderColor(SLATE_300);
        right.setBorderWidth(0.45f);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);
        right.setPaddingTop(2.8f);
        right.setPaddingBottom(3.2f);
        right.setPaddingRight(6f);

        table.addCell(left);
        table.addCell(right);
        document.add(table);
    }

    private void renderProductionIdentity(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{19f, 36f, 18f, 27f});
        table.setSpacingAfter(4.0f);

        var prod = doc.production();
        var org = doc.organizer();

        addKeyValueCells(table,
                "EVENTO / PRODUCCIÓN",
                prod != null ? safe(prod.productionName()) : "N/A",
                true, false);
        addStatusBadgeCells(table, "ESTADO FINAL", prod != null ? safe(prod.status()) : "CERRADO");

        addKeyValueCells(table,
                "RECINTO / UBICACIÓN",
                prod != null ? safe(prod.location()) : "N/A",
                false, false);
        addKeyValueCells(table,
                "ID PRODUCCIÓN",
                prod != null && prod.productionId() != null ? "#" + prod.productionId() : "N/A",
                true, true);

        String scheduledStr = (prod != null && prod.scheduledAt() != null)
                ? prod.scheduledAt().format(LOCAL_DATE_TIME_FMT)
                : "Por confirmar";
        String closedStr = (prod != null && prod.closedAtFormatted() != null)
                ? prod.closedAtFormatted()
                : ((prod != null && prod.closedAt() != null) ? prod.closedAt().format(LOCAL_DATE_TIME_FMT) : "N/A");

        addKeyValueCells(table, "FECHA PROGRAMADA", scheduledStr, false, true);
        addKeyValueCells(table, "CIERRE EFECTIVO", closedStr, false, true);

        String orgId = org != null ? safe(org.organizerId()) : "N/A";
        String emailDisplay = (org != null && org.maskedEmail() != null && !org.maskedEmail().isBlank())
                ? org.maskedEmail()
                : "Sin correo asociado";

        addKeyValueCells(table, "ORGANIZADOR MANDANTE", orgId, false, false);
        addKeyValueCells(table, "CORREO NOTIFICADO", emailDisplay, false, false);

        document.add(table);
    }

    private void renderEquipmentServicesAndCrew(Document document, ProductionClosureDocumentDTO doc) throws DocumentException {
        // 1. Tabla de Equipamiento Reservado (Catálogo)
        PdfPTable eqTable = new PdfPTable(4);
        eqTable.setWidthPercentage(100);
        eqTable.setWidths(new float[]{13f, 56f, 12f, 19f});
        eqTable.setHeaderRows(1);
        eqTable.setSpacingAfter(3.0f);

        addCompactTableHeader(eqTable, "CÓD. EQUIPO", Element.ALIGN_CENTER);
        addCompactTableHeader(eqTable, "EQUIPAMIENTO TÉCNICO RESERVADO (CATÁLOGO)", Element.ALIGN_LEFT);
        addCompactTableHeader(eqTable, "CANT.", Element.ALIGN_CENTER);
        addCompactTableHeader(eqTable, "ESTADO RESERVA", Element.ALIGN_CENTER);

        var catState = doc.catalogAvailability() != null
                ? doc.catalogAvailability()
                : (doc.equipment() != null && !doc.equipment().isEmpty()
                        ? ProductionClosureDocumentDTO.CatalogAvailabilityState.AVAILABLE
                        : (doc.equipment() != null
                                ? ProductionClosureDocumentDTO.CatalogAvailabilityState.EMPTY
                                : ProductionClosureDocumentDTO.CatalogAvailabilityState.NOT_PROVIDED));

        if (catState == ProductionClosureDocumentDTO.CatalogAvailabilityState.AVAILABLE
                && doc.equipment() != null && !doc.equipment().isEmpty()) {
            int idx = 0;
            for (var eq : doc.equipment()) {
                Color bg = (idx++ % 2 == 1) ? SLATE_50 : Color.WHITE;
                String codeTxt = (eq.equipmentId() != null && eq.equipmentId() > 0)
                        ? String.format("EQ-%03d", eq.equipmentId()) : "—";
                String qtyTxt = (eq.reservedQuantity() != null && eq.reservedQuantity() > 0)
                        ? String.valueOf(eq.reservedQuantity()) : "—";
                addCompactDataCell(eqTable, codeTxt, Element.ALIGN_CENTER, bg, true);
                addCompactDataCell(eqTable, safe(eq.equipmentName()), Element.ALIGN_LEFT, bg, false);
                addCompactDataCell(eqTable, qtyTxt, Element.ALIGN_CENTER, bg, true);
                addCompactDataCell(eqTable, safe(eq.reservationStatus()), Element.ALIGN_CENTER, bg, false);
            }
        } else {
            String msg = switch (catState) {
                case EMPTY -> "Sin reservas de equipamiento informadas para esta producción.";
                case UNAVAILABLE -> "No fue posible verificar el inventario en Catálogo al emitir el acta. Esto no confirma ausencia de reservas.";
                case UNAUTHORIZED -> "Catálogo no autorizó la consulta de inventario al emitir el acta. Esto no confirma ausencia de reservas.";
                default -> "Equipamiento no incluido en el comando y consulta de Catálogo no realizada. No se puede confirmar el inventario.";
            };
            addEmptySpanRow(eqTable, msg, 4);
        }
        document.add(eqTable);

        // 2. Tabla de Servicios Contratados (Producciones) + Fila Total Tarifa Base Registrada
        PdfPTable srvTable = new PdfPTable(4);
        srvTable.setWidthPercentage(100);
        srvTable.setWidths(new float[]{13f, 50f, 18f, 19f});
        srvTable.setHeaderRows(1);
        srvTable.setSpacingAfter(3.0f);

        addCompactTableHeader(srvTable, "CÓD. SERV.", Element.ALIGN_CENTER);
        addCompactTableHeader(srvTable, "SERVICIOS ESPECIALIZADOS CONTRATADOS", Element.ALIGN_LEFT);
        addCompactTableHeader(srvTable, "REGISTRO", Element.ALIGN_CENTER);
        addCompactTableHeader(srvTable, "TARIFA BASE REG.", Element.ALIGN_RIGHT);

        if (doc.services() != null && !doc.services().isEmpty()) {
            BigDecimal totalBase = BigDecimal.ZERO;
            boolean hasNumericRate = false;
            int idx = 0;
            for (var srv : doc.services()) {
                Color bg = (idx++ % 2 == 1) ? SLATE_50 : Color.WHITE;
                String srvCode = (srv.serviceId() != null && srv.serviceId() > 0)
                        ? String.format("SRV-%03d", srv.serviceId()) : "—";
                String regAt = srv.contractedAt() != null ? srv.contractedAt().format(SHORT_LOCAL_FMT) : "—";
                String priceStr;
                if (srv.unitPrice() != null) {
                    BigDecimal scaled = srv.unitPrice().setScale(2, RoundingMode.HALF_UP);
                    totalBase = totalBase.add(scaled);
                    hasNumericRate = true;
                    priceStr = scaled.toPlainString();
                } else {
                    priceStr = "—";
                }
                addCompactDataCell(srvTable, srvCode, Element.ALIGN_CENTER, bg, true);
                addCompactDataCell(srvTable, safe(srv.serviceName()), Element.ALIGN_LEFT, bg, false);
                addCompactDataCell(srvTable, regAt, Element.ALIGN_CENTER, bg, true);
                addCompactDataCell(srvTable, priceStr, Element.ALIGN_RIGHT, bg, true);
            }

            PdfPCell totalLbl = new PdfPCell(new Phrase(
                    "TOTAL TARIFA BASE REGISTRADA (" + doc.services().size() + " SERVICIOS)",
                    new Font(boldBaseFont, 6.9f, Font.BOLD, PRIMARY_NAVY)));
            totalLbl.setColspan(3);
            totalLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalLbl.setBackgroundColor(SLATE_100);
            totalLbl.setBorderColor(SLATE_300);
            totalLbl.setBorderWidth(0.45f);
            totalLbl.setPadding(2.8f);
            srvTable.addCell(totalLbl);

            String totalTxt = hasNumericRate ? totalBase.setScale(2, RoundingMode.HALF_UP).toPlainString() : "—";
            PdfPCell totalVal = new PdfPCell(new Phrase(totalTxt,
                    new Font(courierBoldFont, 7.6f, Font.BOLD, PRIMARY_NAVY)));
            totalVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalVal.setBackgroundColor(SLATE_100);
            totalVal.setBorderColor(SLATE_300);
            totalVal.setBorderWidth(0.45f);
            totalVal.setPadding(2.8f);
            srvTable.addCell(totalVal);
        } else {
            addEmptySpanRow(srvTable, "Sin servicios complementarios registrados en la producción.", 4);
        }
        document.add(srvTable);

        // 3. Bloque compacto de Orden de Trabajo y Cuadrilla (RabbitMQ q.cmd.crew)
        if (doc.crewTicket() != null) {
            var ticket = doc.crewTicket();
            PdfPTable crewTable = new PdfPTable(4);
            crewTable.setWidthPercentage(100);
            crewTable.setWidths(new float[]{19f, 23f, 18f, 40f});
            crewTable.setKeepTogether(true);
            crewTable.setSpacingAfter(4.0f);

            String tIdStatus = "Ticket #" + (ticket.ticketId() != null ? ticket.ticketId() : "N/A")
                    + " · " + safe(ticket.ticketStatus());
            addKeyValueCells(crewTable, "ORDEN DE CUADRILLA", tIdStatus, true, false);
            addKeyValueCells(crewTable, "EVENT ID CUADRILLA", safe(ticket.ticketEventId()), false, true);

            PdfPCell instrLbl = new PdfPCell(new Phrase("HOJA DE RUTA EJECUTADA",
                    new Font(boldBaseFont, 6.6f, Font.BOLD, SLATE_700)));
            instrLbl.setBackgroundColor(SLATE_50);
            instrLbl.setBorderColor(SLATE_300);
            instrLbl.setBorderWidth(0.45f);
            instrLbl.setPadding(2.8f);
            crewTable.addCell(instrLbl);

            String instrVal = (ticket.instructions() != null && !ticket.instructions().isBlank())
                    ? ticket.instructions()
                    : "Ejecución operativa completada sin observaciones adicionales.";
            PdfPCell instrCell = new PdfPCell(new Phrase(instrVal,
                    new Font(regularBaseFont, 7.0f, Font.NORMAL, SLATE_900)));
            instrCell.setColspan(3);
            instrCell.setBorderColor(SLATE_300);
            instrCell.setBorderWidth(0.45f);
            instrCell.setPadding(2.8f);
            crewTable.addCell(instrCell);

            document.add(crewTable);
        }
    }

    private void renderTimeline(Document document, List<ProductionClosureDocumentDTO.OperationalTimelineItemDTO> timeline)
            throws DocumentException {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{5.5f, 26.5f, 20.5f, 30.5f, 17f});
        table.setHeaderRows(1);
        table.setSpacingAfter(4.0f);

        addCompactTableHeader(table, "#", Element.ALIGN_CENTER);
        addCompactTableHeader(table, "TRANSICIÓN DE ESTADO", Element.ALIGN_LEFT);
        addCompactTableHeader(table, "TIMESTAMP (UTC)", Element.ALIGN_CENTER);
        addCompactTableHeader(table, "ACTOR AUTENTICADO (ENTRA ID)", Element.ALIGN_LEFT);
        addCompactTableHeader(table, "EVENT ID", Element.ALIGN_CENTER);

        int idx = 0;
        for (var item : timeline) {
            Color bg = (idx++ % 2 == 1) ? SLATE_50 : Color.WHITE;
            boolean isTerminal = "CERRADO".equalsIgnoreCase(item.toStatus());

            addCompactDataCell(table, String.format("%02d", item.sequenceIndex()), Element.ALIGN_CENTER, bg, true);
            addPhraseDataCell(table, buildTransitionPhrase(item.fromStatus(), item.toStatus(), isTerminal), Element.ALIGN_LEFT, bg);

            String dateStr = item.occurredAt() != null ? UTC_DATE_TIME_FMT.format(item.occurredAt()) + " UTC" : "N/A";
            addCompactDataCell(table, dateStr, Element.ALIGN_CENTER, bg, true);
            addCompactDataCell(table, formatCompactActor(item.actorOid()), Element.ALIGN_LEFT, bg, true);
            addCompactDataCell(table, shortenToken(item.eventId(), 8, 4), Element.ALIGN_CENTER, bg, true);
        }

        document.add(table);
    }

    private void renderDualElectronicSignatures(Document document, ProductionClosureDocumentDTO doc, String sha256)
            throws DocumentException {
        PdfPTable dualTable = new PdfPTable(3);
        dualTable.setWidthPercentage(100);
        dualTable.setWidths(new float[]{49f, 2f, 49f});
        dualTable.setKeepTogether(true);
        dualTable.setSpacingAfter(4.0f);

        String closingActor = (doc.traceability() != null && doc.traceability().closingActorOid() != null)
                ? doc.traceability().closingActorOid() : "N/A";
        String closedUtc = (doc.production() != null && doc.production().closedAtFormatted() != null)
                ? doc.production().closedAtFormatted()
                : ((doc.metadata() != null && doc.metadata().generatedAt() != null)
                        ? UTC_DATE_TIME_FMT.format(doc.metadata().generatedAt()) + " UTC" : "N/A");

        String orgId = (doc.organizer() != null && doc.organizer().organizerId() != null)
                ? doc.organizer().organizerId() : "N/A";
        String orgEmail = (doc.organizer() != null && doc.organizer().maskedEmail() != null)
                ? doc.organizer().maskedEmail() : "Notificación electrónica registrada";
        String initialActor = (doc.timeline() != null && !doc.timeline().isEmpty() && doc.timeline().getFirst().actorOid() != null)
                ? formatCompactActor(doc.timeline().getFirst().actorOid())
                : orgId;
        String emitUtc = (doc.metadata() != null && doc.metadata().generatedAt() != null)
                ? UTC_DATE_TIME_FMT.format(doc.metadata().generatedAt()) + " UTC" : closedUtc;

        String sigDigest1 = sha256.substring(0, 16).toUpperCase();
        String sigDigest2 = sha256.substring(16, 32).toUpperCase();

        dualTable.addCell(buildStakeholderCertificateCell(
                "FIRMANTE 1 · RESPONSABLE OPERATIVO",
                "FIRMA ELECTRÓNICA SIMPLE · ENTRA ID OIDC",
                "Operador de Cierre EventoMax",
                "Dirección de Operaciones y Producción Técnica",
                "Actor OID: " + closingActor,
                "Cierre UTC: " + closedUtc,
                sigDigest1,
                "EVENTOMAX",
                "VERIFICADO",
                "CIERRE TÉCNICO"
        ));

        PdfPCell spacer = new PdfPCell();
        spacer.setBorder(Rectangle.NO_BORDER);
        dualTable.addCell(spacer);

        dualTable.addCell(buildStakeholderCertificateCell(
                "FIRMANTE 2 · REPRESENTANTE DEL ORGANIZADOR",
                "REGISTRO Y RECEPCIÓN ELECTRÓNICA · ENTRA ID",
                "Organización: " + orgId,
                "Canal Certificado: " + orgEmail,
                "Registro Inicial: " + initialActor,
                "Emisión UTC: " + emitUtc,
                sigDigest2,
                "MANDANTE",
                "REGISTRADO",
                "RECEPCIÓN ACTA"
        ));

        document.add(dualTable);
    }

    private PdfPCell buildStakeholderCertificateCell(
            String topTitle,
            String verifiedTag,
            String primaryEntity,
            String secondaryLine,
            String actorIdentityLine,
            String timestampLine,
            String sigDigest,
            String sealLine1,
            String sealLine2,
            String sealLine3) throws DocumentException {

        PdfPCell outer = new PdfPCell();
        outer.setBorder(Rectangle.BOX);
        outer.setBorderColor(SLATE_300);
        outer.setBorderWidth(0.5f);
        outer.setBackgroundColor(Color.WHITE);
        outer.setPadding(0f);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Barra superior del certificado con check vectorial esmeralda
        PdfPCell headBar = new PdfPCell(new Phrase(topTitle, new Font(boldBaseFont, 6.6f, Font.BOLD, PRIMARY_NAVY)));
        headBar.setBackgroundColor(SLATE_100);
        headBar.setBorder(Rectangle.BOTTOM | Rectangle.LEFT);
        headBar.setBorderColorBottom(SLATE_300);
        headBar.setBorderColorLeft(PRIMARY_NAVY);
        headBar.setBorderWidthBottom(0.45f);
        headBar.setBorderWidthLeft(2.2f);
        headBar.setPaddingTop(3.2f);
        headBar.setPaddingBottom(3.6f);
        headBar.setPaddingLeft(5.5f);
        headBar.setPaddingRight(18f);
        headBar.setCellEvent((cell, rect, canvases) -> {
            PdfContentByte cb = canvases[PdfPTable.TEXTCANVAS];
            cb.saveState();
            float cx = rect.getRight() - 9.0f;
            float cy = (rect.getTop() + rect.getBottom()) / 2f;
            cb.setColorFill(EMERALD_700);
            cb.circle(cx, cy, 3.8f);
            cb.fill();
            cb.setColorStroke(Color.WHITE);
            cb.setLineWidth(0.95f);
            cb.moveTo(cx - 1.8f, cy - 0.2f);
            cb.lineTo(cx - 0.5f, cy - 1.5f);
            cb.lineTo(cx + 1.9f, cy + 1.3f);
            cb.stroke();
            cb.restoreState();
        });
        card.addCell(headBar);

        // Cuerpo en 2 columnas (76% datos | 24% timbre circular vectorial) con micro-líneas de seguridad
        PdfPTable bodyGrid = new PdfPTable(2);
        bodyGrid.setWidthPercentage(100);
        bodyGrid.setWidths(new float[]{76f, 24f});

        PdfPCell infoCol = new PdfPCell();
        infoCol.setBorder(Rectangle.NO_BORDER);
        infoCol.setPaddingLeft(6f);
        infoCol.setPaddingRight(2f);
        infoCol.setPaddingTop(4.2f);
        infoCol.setPaddingBottom(4.8f);
        infoCol.setCellEvent((cell, rect, canvases) -> {
            PdfContentByte bg = canvases[PdfPTable.BACKGROUNDCANVAS];
            bg.saveState();
            bg.setColorStroke(GUILLOCHE_LINE);
            bg.setLineWidth(0.25f);
            for (float y = rect.getBottom() + 5f; y < rect.getTop() - 4f; y += 5.5f) {
                bg.moveTo(rect.getLeft() + 3f, y);
                bg.lineTo(rect.getRight() - 2f, y);
            }
            bg.stroke();
            bg.restoreState();
        });

        Paragraph pVerified = new Paragraph(verifiedTag, new Font(boldBaseFont, 5.9f, Font.BOLD, EMERALD_700));
        pVerified.setSpacingAfter(1.5f);
        infoCol.addElement(pVerified);

        Paragraph pPrimary = new Paragraph(primaryEntity, new Font(boldBaseFont, 7.8f, Font.BOLD, SLATE_900));
        pPrimary.setLeading(9.0f);
        infoCol.addElement(pPrimary);

        Paragraph pSecondary = new Paragraph(secondaryLine, new Font(regularBaseFont, 6.5f, Font.NORMAL, SLATE_700));
        pSecondary.setLeading(8.0f);
        pSecondary.setSpacingAfter(2.2f);
        infoCol.addElement(pSecondary);

        Paragraph pActor = new Paragraph(actorIdentityLine, new Font(courierRegularFont, 5.6f, Font.NORMAL, SLATE_700));
        pActor.setLeading(7.2f);
        infoCol.addElement(pActor);

        Paragraph pTime = new Paragraph(timestampLine, new Font(regularBaseFont, 6.2f, Font.NORMAL, SLATE_700));
        pTime.setLeading(7.8f);
        infoCol.addElement(pTime);

        Paragraph pDigest = new Paragraph("Sello Criptográfico: " + sigDigest,
                new Font(courierBoldFont, 5.9f, Font.BOLD, PRIMARY_NAVY));
        pDigest.setLeading(7.8f);
        infoCol.addElement(pDigest);

        PdfPCell sealCol = new PdfPCell();
        sealCol.setBorder(Rectangle.NO_BORDER);
        sealCol.setMinimumHeight(62f);
        sealCol.setCellEvent((cell, rect, canvases) -> {
            PdfContentByte cb = canvases[PdfPTable.TEXTCANVAS];
            cb.saveState();
            PdfGState gs = new PdfGState();
            gs.setStrokeOpacity(0.26f);
            gs.setFillOpacity(0.26f);
            cb.setGState(gs);

            float sx = (rect.getLeft() + rect.getRight()) / 2f - 2f;
            float sy = (rect.getTop() + rect.getBottom()) / 2f;
            cb.setColorStroke(PRIMARY_NAVY);
            cb.setLineWidth(1.05f);
            cb.circle(sx, sy, 21f);
            cb.stroke();
            cb.setLineWidth(0.45f);
            cb.circle(sx, sy, 17.5f);
            cb.stroke();

            cb.beginText();
            cb.setColorFill(PRIMARY_NAVY);
            cb.setFontAndSize(boldBaseFont, 4.9f);
            cb.showTextAligned(Element.ALIGN_CENTER, sealLine1, sx, sy + 6.4f, 7f);
            cb.setFontAndSize(boldBaseFont, 5.5f);
            cb.showTextAligned(Element.ALIGN_CENTER, sealLine2, sx, sy - 0.4f, 7f);
            cb.setFontAndSize(courierBoldFont, 4.4f);
            cb.showTextAligned(Element.ALIGN_CENTER, sealLine3, sx, sy - 7.0f, 7f);
            cb.endText();
            cb.restoreState();
        });

        bodyGrid.addCell(infoCol);
        bodyGrid.addCell(sealCol);

        PdfPCell bodyWrap = new PdfPCell(bodyGrid);
        bodyWrap.setBorder(Rectangle.NO_BORDER);
        bodyWrap.setPadding(0f);
        card.addCell(bodyWrap);

        outer.addElement(card);
        return outer;
    }

    private void renderCryptographicSealAndPdf417(
            Document document,
            ProductionClosureDocumentDTO doc,
            String sha256,
            String csv) throws DocumentException {

        PdfPTable verifyBox = new PdfPTable(2);
        verifyBox.setWidthPercentage(100);
        verifyBox.setWidths(new float[]{64f, 36f});
        verifyBox.setKeepTogether(true);

        var trace = doc.traceability();
        String rabbitEvt = trace != null ? safe(trace.rabbitmqCommandEventId()) : "N/A";
        String kafkaEvt = trace != null ? safe(trace.kafkaClosingEventId()) : "N/A";
        String traceId = trace != null ? safe(trace.traceId()) : "N/A";
        String corrId = trace != null ? safe(trace.correlationId()) : "N/A";

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.BOX);
        left.setBorderColor(SLATE_300);
        left.setBorderWidth(0.5f);
        left.setBackgroundColor(SLATE_50);
        left.setPadding(5.0f);

        Paragraph title = new Paragraph("SELLO DE INTEGRIDAD CRIPTOGRÁFICA Y TRAZABILIDAD DISTRIBUIDA",
                new Font(boldBaseFont, 7.1f, Font.BOLD, PRIMARY_NAVY));
        title.setSpacingAfter(2.0f);
        left.addElement(title);

        addMonoMetadataLine(left, "CSV Verificación: ", csv + "   |   Algoritmo: SHA-256");
        addMonoMetadataLine(left, "SHA-256 Digest:   ", sha256);
        addMonoMetadataLine(left, "Event ID (RMQ/KFK): ", rabbitEvt + " / " + kafkaEvt);
        addMonoMetadataLine(left, "Trace ID / Corr ID: ", traceId + " / " + corrId);

        Paragraph legal = new Paragraph(
                "Constancia electrónica suscrita mediante Firma Electrónica Simple autenticada vía OIDC Microsoft Entra ID conforme a la "
                + "Ley N° 19.799 sobre Documentos Electrónicos y Firma Electrónica. Acredita el cierre operativo e inventario interno en "
                + "EventoMax Platform; sin efectos tributarios ni facturación electrónica ante el SII.",
                new Font(regularBaseFont, 6.0f, Font.NORMAL, SLATE_500));
        legal.setLeading(7.5f);
        legal.setSpacingBefore(2.2f);
        left.addElement(legal);

        // Columna Derecha: Timbre Electrónico Vectorial PDF417 Real
        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.BOX);
        right.setBorderColor(SLATE_300);
        right.setBorderWidth(0.5f);
        right.setBackgroundColor(Color.WHITE);
        right.setPadding(4.5f);
        right.setHorizontalAlignment(Element.ALIGN_CENTER);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Long prodId = (doc.production() != null && doc.production().productionId() != null)
                ? doc.production().productionId() : 0L;
        String docCode = (doc.metadata() != null && doc.metadata().documentCode() != null)
                ? doc.metadata().documentCode() : "EMX-ACTA-PRD-" + prodId;
        String orgId = (doc.organizer() != null && doc.organizer().organizerId() != null)
                ? doc.organizer().organizerId() : "N/A";
        String tsIso = (doc.metadata() != null && doc.metadata().generatedAt() != null)
                ? ISO_UTC_FMT.format(doc.metadata().generatedAt()) : ISO_UTC_FMT.format(Instant.now());

        BarcodePDF417 pdf417 = new BarcodePDF417();
        String tedXml = "<TED><DOC>" + docCode + "</DOC><CSV>" + csv + "</CSV><PROD>" + prodId
                + "</PROD><ORG>" + orgId + "</ORG><TS>" + tsIso
                + "</TS><SHA256>" + sha256.substring(0, 32) + "</SHA256></TED>";
        pdf417.setText(tedXml);
        pdf417.setErrorLevel(3);
        Image barcodeImg = pdf417.getImage();
        barcodeImg.scaleAbsolute(168f, 40f);
        barcodeImg.setAlignment(Element.ALIGN_CENTER);
        right.addElement(barcodeImg);

        Paragraph cap1 = new Paragraph("TIMBRE ELECTRÓNICO EVENTOMAX · PDF417",
                new Font(boldBaseFont, 6.1f, Font.BOLD, PRIMARY_NAVY));
        cap1.setAlignment(Element.ALIGN_CENTER);
        cap1.setSpacingBefore(2.0f);
        right.addElement(cap1);

        Paragraph cap2 = new Paragraph("Verificación íntegra mediante CSV " + csv,
                new Font(regularBaseFont, 5.8f, Font.NORMAL, SLATE_500));
        cap2.setAlignment(Element.ALIGN_CENTER);
        right.addElement(cap2);

        verifyBox.addCell(left);
        verifyBox.addCell(right);
        document.add(verifyBox);
    }

    // =========================================================================
    // AUXILIARES DE CELDAS, FORMATO Y CRIPTOGRAFÍA
    // =========================================================================

    private void addKeyValueCells(PdfPTable table, String label, String value, boolean boldValue, boolean monoValue) {
        PdfPCell lc = new PdfPCell(new Phrase(label, new Font(boldBaseFont, 6.6f, Font.BOLD, SLATE_700)));
        lc.setBackgroundColor(SLATE_50);
        lc.setBorderColor(SLATE_300);
        lc.setBorderWidth(0.45f);
        lc.setPadding(2.9f);
        table.addCell(lc);

        Font vf;
        if (monoValue) {
            vf = new Font(boldValue ? courierBoldFont : courierRegularFont, 7.1f, boldValue ? Font.BOLD : Font.NORMAL, SLATE_900);
        } else {
            vf = new Font(boldValue ? boldBaseFont : regularBaseFont, 7.4f, boldValue ? Font.BOLD : Font.NORMAL, SLATE_900);
        }
        PdfPCell vc = new PdfPCell(new Phrase(value != null ? value : "N/A", vf));
        vc.setBorderColor(SLATE_300);
        vc.setBorderWidth(0.45f);
        vc.setPadding(2.9f);
        table.addCell(vc);
    }

    private void addStatusBadgeCells(PdfPTable table, String label, String status) {
        PdfPCell lc = new PdfPCell(new Phrase(label, new Font(boldBaseFont, 6.6f, Font.BOLD, SLATE_700)));
        lc.setBackgroundColor(SLATE_50);
        lc.setBorderColor(SLATE_300);
        lc.setBorderWidth(0.45f);
        lc.setPadding(2.9f);
        table.addCell(lc);

        PdfPCell vc = new PdfPCell(new Phrase(status + " · AUDITADO", new Font(boldBaseFont, 7.2f, Font.BOLD, EMERALD_700)));
        vc.setBackgroundColor(EMERALD_50);
        vc.setBorderColor(EMERALD_200);
        vc.setBorderWidth(0.45f);
        vc.setPadding(2.9f);
        table.addCell(vc);
    }

    private void addCompactTableHeader(PdfPTable table, String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, new Font(boldBaseFont, 6.5f, Font.BOLD, PRIMARY_NAVY)));
        c.setBackgroundColor(SLATE_200);
        c.setBorderColor(SLATE_300);
        c.setBorderWidth(0.45f);
        c.setHorizontalAlignment(align);
        c.setPadding(2.8f);
        table.addCell(c);
    }

    private void addCompactDataCell(PdfPTable table, String text, int align, Color bg, boolean mono) {
        Font f = mono
                ? new Font(courierRegularFont, 6.8f, Font.NORMAL, SLATE_900)
                : new Font(regularBaseFont, 7.2f, Font.NORMAL, SLATE_900);
        PdfPCell c = new PdfPCell(new Phrase(text != null ? text : "—", f));
        c.setBackgroundColor(bg);
        c.setBorderColor(SLATE_300);
        c.setBorderWidth(0.45f);
        c.setHorizontalAlignment(align);
        c.setPadding(2.6f);
        table.addCell(c);
    }

    private void addPhraseDataCell(PdfPTable table, Phrase phrase, int align, Color bg) {
        PdfPCell c = new PdfPCell(phrase);
        c.setBackgroundColor(bg);
        c.setBorderColor(SLATE_300);
        c.setBorderWidth(0.45f);
        c.setHorizontalAlignment(align);
        c.setPadding(2.6f);
        table.addCell(c);
    }

    private void addEmptySpanRow(PdfPTable table, String message, int colspan) {
        PdfPCell c = new PdfPCell(new Phrase(message, new Font(regularBaseFont, 7.0f, Font.ITALIC, SLATE_500)));
        c.setColspan(colspan);
        c.setBackgroundColor(Color.WHITE);
        c.setBorderColor(SLATE_300);
        c.setBorderWidth(0.45f);
        c.setPadding(3.2f);
        table.addCell(c);
    }

    private void addMonoMetadataLine(PdfPCell container, String key, String val) {
        Paragraph p = new Paragraph();
        p.setLeading(8.2f);
        p.add(new Chunk(key, new Font(boldBaseFont, 6.1f, Font.BOLD, SLATE_700)));
        p.add(new Chunk(val, new Font(courierRegularFont, 5.9f, Font.NORMAL, SLATE_900)));
        container.addElement(p);
    }

    private Phrase buildTransitionPhrase(String fromStatus, String toStatus, boolean emphasizeTarget) {
        Phrase p = new Phrase();
        String origin = (fromStatus != null && !fromStatus.isBlank() && !"-".equals(fromStatus.trim()))
                ? fromStatus : "REGISTRO";
        p.add(new Chunk(origin, new Font(regularBaseFont, 7.0f, Font.NORMAL, SLATE_700)));
        p.add(new Chunk("  " + (char) 222 + "  ", new Font(zapfDingbatsFont, 6.8f, Font.NORMAL, PRIMARY_BLUE)));
        Font targetFont = emphasizeTarget
                ? new Font(boldBaseFont, 7.1f, Font.BOLD, EMERALD_700)
                : new Font(boldBaseFont, 7.0f, Font.BOLD, SLATE_900);
        p.add(new Chunk(safe(toStatus), targetFont));
        return p;
    }

    private static String formatCompactActor(String actor) {
        if (actor == null || actor.isBlank()) return "N/A";
        String cleaned = actor.startsWith("entra-oid:") ? actor.substring("entra-oid:".length()) : actor;
        if (cleaned.contains(":")) {
            String[] parts = cleaned.split(":", 2);
            String oidPart = parts[1];
            return "Entra OID " + shortenToken(oidPart, 8, 6);
        }
        if (cleaned.length() > 26) {
            return "OID " + shortenToken(cleaned, 10, 6);
        }
        return cleaned;
    }

    private static String shortenToken(String val, int head, int tail) {
        if (val == null) return "N/A";
        if (val.length() <= head + tail + 3) return val;
        return val.substring(0, head) + "..." + val.substring(val.length() - tail);
    }

    private static String computeCanonicalSha256(ProductionClosureDocumentDTO doc) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String prodId = (doc.production() != null && doc.production().productionId() != null)
                    ? String.valueOf(doc.production().productionId()) : "0";
            String prodName = doc.production() != null ? safe(doc.production().productionName()) : "N/A";
            String status = doc.production() != null ? safe(doc.production().status()) : "N/A";
            String orgId = doc.organizer() != null ? safe(doc.organizer().organizerId()) : "N/A";
            String traceId = doc.traceability() != null ? safe(doc.traceability().traceId()) : "N/A";
            String cmdEvt = doc.traceability() != null ? safe(doc.traceability().rabbitmqCommandEventId()) : "N/A";
            String actor = doc.traceability() != null ? safe(doc.traceability().closingActorOid()) : "N/A";
            int timelineCount = doc.timeline() != null ? doc.timeline().size() : 0;

            String canonical = String.join("|", prodId, prodName, status, orgId, traceId, cmdEvt, actor, String.valueOf(timelineCount));
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el hash SHA-256 del documento", e);
        }
    }

    private static String buildCsvCode(Long productionId, String sha256) {
        long id = productionId != null ? productionId : 0L;
        return String.format("EMX-%06d-%s-%s",
                id,
                sha256.substring(0, 4).toUpperCase(),
                sha256.substring(4, 8).toUpperCase());
    }

    private static String safe(String s) {
        return s == null ? "N/A" : s;
    }

    /**
     * Evento de página corporativo para cabecera continua en Página 2+ y pie institucional "Página X de N".
     */
    private static class HeaderFooterPageEvent extends PdfPageEventHelper {
        private final BaseFont fontRegular;
        private final BaseFont fontBold;
        private final BaseFont fontMono;
        private final byte[] symbolBytes;
        private final ProductionClosureDocumentDTO doc;
        private final String csv;
        private PdfTemplate totalPagesTemplate;

        HeaderFooterPageEvent(
                BaseFont fontRegular,
                BaseFont fontBold,
                BaseFont fontMono,
                byte[] symbolBytes,
                ProductionClosureDocumentDTO doc,
                String csv) {
            this.fontRegular = fontRegular;
            this.fontBold = fontBold;
            this.fontMono = fontMono;
            this.symbolBytes = symbolBytes;
            this.doc = doc;
            this.csv = csv;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            this.totalPagesTemplate = writer.getDirectContent().createTemplate(24f, 12f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float pageLeft = document.left();
            float pageRight = document.right();
            float pageTop = document.top();
            float pageBottom = document.bottom() - 15f;
            int pageNumber = writer.getPageNumber();

            if (pageNumber > 1) {
                float headerY = pageTop + 9f;
                if (symbolBytes != null) {
                    try {
                        Image symbolImg = Image.getInstance(symbolBytes);
                        symbolImg.scaleToFit(11f, 11f);
                        symbolImg.setAbsolutePosition(pageLeft, headerY - 1.5f);
                        cb.addImage(symbolImg);
                    } catch (Exception ignored) {
                    }
                }
                Long prodId = (doc.production() != null && doc.production().productionId() != null)
                        ? doc.production().productionId() : 0L;
                String docCode = (doc.metadata() != null && doc.metadata().documentCode() != null)
                        ? doc.metadata().documentCode() : "EMX-ACTA-PRD-" + prodId;

                cb.beginText();
                cb.setFontAndSize(fontBold, 7.4f);
                cb.setColorFill(PRIMARY_NAVY);
                cb.showTextAligned(Element.ALIGN_LEFT, "EVENTOMAX · ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO", pageLeft + 15f, headerY, 0);
                cb.setFontAndSize(fontMono, 6.8f);
                cb.setColorFill(SLATE_500);
                cb.showTextAligned(Element.ALIGN_RIGHT, docCode + " · CSV " + csv, pageRight, headerY, 0);
                cb.endText();

                cb.setColorStroke(SLATE_300);
                cb.setLineWidth(0.45f);
                cb.moveTo(pageLeft, headerY - 4f);
                cb.lineTo(pageRight, headerY - 4f);
                cb.stroke();
            }

            cb.setColorStroke(SLATE_300);
            cb.setLineWidth(0.45f);
            cb.moveTo(pageLeft, pageBottom + 9f);
            cb.lineTo(pageRight, pageBottom + 9f);
            cb.stroke();

            cb.beginText();
            cb.setFontAndSize(fontRegular, 6.6f);
            cb.setColorFill(SLATE_500);
            cb.showTextAligned(Element.ALIGN_LEFT,
                    "EventoMax Operaciones · Registro Operacional Auditado (Ley N° 19.799 · Sin efectos tributarios) · CSV " + csv,
                    pageLeft, pageBottom, 0);

            cb.setFontAndSize(fontRegular, 7.0f);
            cb.setColorFill(SLATE_900);
            cb.showTextAligned(Element.ALIGN_RIGHT, "Página " + pageNumber + " de ", pageRight - 10f, pageBottom, 0);
            cb.endText();

            cb.addTemplate(totalPagesTemplate, pageRight - 9f, pageBottom);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalPagesTemplate.beginText();
            totalPagesTemplate.setFontAndSize(fontBold, 7.0f);
            totalPagesTemplate.setColorFill(SLATE_900);
            totalPagesTemplate.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPagesTemplate.endText();
        }
    }
}
