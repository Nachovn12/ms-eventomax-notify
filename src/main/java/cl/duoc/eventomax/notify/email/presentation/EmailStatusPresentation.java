package cl.duoc.eventomax.notify.email.presentation;

import java.util.Locale;

public record EmailStatusPresentation(String label, String background, String accent, String title,
                                      String subject, String message) {
    public static EmailStatusPresentation of(String raw) {
        return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
            case "CONFIRMADO" -> new EmailStatusPresentation("Confirmado", "#EFF6FF", "#1D4ED8",
                    "Tu producción está confirmada", "Producción #%d confirmada",
                    "La planificación de tu evento ha sido registrada correctamente.");
            case "EN_MONTAJE" -> new EmailStatusPresentation("En montaje", "#FFFBEB", "#92400E",
                    "El montaje está en curso", "Montaje iniciado · Producción #%d",
                    "El equipo técnico ha iniciado la etapa de montaje de tu producción.");
            case "CERRADO" -> new EmailStatusPresentation("Cerrado", "#ECFDF5", "#047857",
                    "Tu producción ha sido cerrada", "Producción #%d cerrada",
                    "El cierre operativo de tu producción ha sido registrado.");
            default -> new EmailStatusPresentation("Actualizado", "#EFF6FF", "#1D4ED8",
                    "Tu producción tiene una actualización", "Actualización · Producción #%d",
                    "Se ha registrado una actualización del estado de tu producción en EventoMax.");
        };
    }
}
