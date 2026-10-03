package cl.duoc.eventomax.notify.messaging.crew;

/**
 * Payload para el comando de ticket/hoja de ruta de cuadrilla.
 * <p>
 * Corresponde al contrato V1 del comando "GenerateCrewTicket".
 */
public record CrewTicketPayload(
        Long productionId,
        String productionName,
        String scheduledAt,
        String location,
        String status
) {
}
