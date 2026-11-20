package cl.duoc.eventomax.notify.email.presentation;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** Visible data only: no recipient, organizer identity, trace, document UUID or storage key. */
public record EmailPresentationModel(long folio, String name, String scheduledAt, String location,
                                     EmailStatusPresentation status, boolean verifiedPdf,
                                     boolean logoAvailable, Optional<String> portalLink) {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM uuuu · HH:mm", Locale.forLanguageTag("es-CL"));

    public static EmailPresentationModel from(EmailProductionStatusPayload p, boolean verifiedPdf,
                                               boolean logo, Optional<String> link) {
        if (p.productionId() == null || p.productionId() < 1) throw new IllegalArgumentException("INVALID_PRODUCTION_FOLIO");
        boolean closed = "CERRADO".equalsIgnoreCase(p.status() == null ? "" : p.status().trim());
        if (closed != verifiedPdf) throw new IllegalArgumentException("VERIFIED_CLOSURE_REQUIRED");
        return new EmailPresentationModel(p.productionId(), text(p.productionName(), "Nombre no disponible"),
                date(p.scheduledAt()), text(p.location(), "Recinto no disponible"),
                EmailStatusPresentation.of(p.status()), verifiedPdf, logo, link);
    }

    static String text(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        StringBuilder result = new StringBuilder();
        raw.codePoints().filter(c -> !Character.isISOControl(c) && c != 0x202E && c != 0x202D)
                .forEach(result::appendCodePoint);
        return result.toString().trim().isEmpty() ? fallback : result.toString().trim();
    }

    static String date(String raw) {
        if (raw == null || raw.isBlank()) return "Fecha no disponible";
        try {
            var zoned = OffsetDateTime.parse(raw.trim()).atZoneSameInstant(SANTIAGO);
            return DATE.format(zoned) + " (America/Santiago, UTC" + zoned.getOffset() + ")";
        } catch (java.time.format.DateTimeParseException ignored) { }
        try { return DATE.format(LocalDateTime.parse(raw.trim())) + " (zona no informada)"; }
        catch (java.time.format.DateTimeParseException ignored) { return "Fecha no disponible"; }
    }
}
