package cl.duoc.eventomax.notify.closure;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;

/** Validates identity and bytes, never uses a mutable latest.pdf alias. */
public final class ClosurePdf {
    private ClosurePdf() {}
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static byte[] identify(byte[] bytes, long productionId, UUID documentId) {
        try (var out = new ByteArrayOutputStream()) {
            var reader = new PdfReader(bytes);
            try {
                var info = new HashMap<>(reader.getInfo());
                info.put("EventoMaxProductionId", Long.toString(productionId));
                info.put("EventoMaxDocumentId", documentId.toString());
                info.put("EventoMaxDocumentType", "ENTERPRISE_CLOSURE");
                var stamper = new PdfStamper(reader, out);
                stamper.setMoreInfo(info);
                stamper.close();
                return out.toByteArray();
            } finally { reader.close(); }
        } catch (Exception ex) { throw new IllegalArgumentException("INVALID_PDF"); }
    }
    public static void verify(byte[] bytes, long productionId, UUID documentId,
                              String expectedSha, long expectedSize, int limit) {
        if (bytes == null || bytes.length == 0 || bytes.length > limit || bytes.length != expectedSize
                || !sha256(bytes).equals(expectedSha)) throw new IllegalArgumentException("PDF_CHECKSUM_OR_SIZE");
        try {
            var reader = new PdfReader(bytes);
            try {
                var info = reader.getInfo();
                if (reader.getNumberOfPages() < 1 || reader.isEncrypted()
                        || !Long.toString(productionId).equals(info.get("EventoMaxProductionId"))
                        || !documentId.toString().equals(info.get("EventoMaxDocumentId"))
                        || !"ENTERPRISE_CLOSURE".equals(info.get("EventoMaxDocumentType"))) {
                    throw new IllegalArgumentException("PDF_IDENTITY");
                }
            } finally { reader.close(); }
        } catch (Exception ex) { throw new IllegalArgumentException("PDF_IDENTITY_OR_STRUCTURE"); }
    }
}
