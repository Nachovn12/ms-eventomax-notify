package cl.duoc.eventomax.notify.messaging.quote;

/**
 * Payload para el comando de generación de acta de cierre / documento quote ("GenerateClosingQuote").
 * <p>
 * Utiliza exclusivamente los datos reales del dominio de Producciones al alcanzar el estado {@code CERRADO},
 * sin inventar valores financieros.
 */
public record QuoteCommandPayload(
        Long productionId,
        String organizerId,
        String productionName,
        String status,
        String scheduledAt,
        String location,
        String closedAt
) {
}

