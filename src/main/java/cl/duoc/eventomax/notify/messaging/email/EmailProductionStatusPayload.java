package cl.duoc.eventomax.notify.messaging.email;

/**
 * Payload del comando de notificación de estado de producción.
 * <p>
 * Compatible con el contrato publicado por ms-eventomax-productions.
 */
public record EmailProductionStatusPayload(
        Long productionId,
        String organizerId,
        String productionName,
        String status,
        String scheduledAt,
        String location
) {}
