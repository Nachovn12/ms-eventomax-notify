package cl.duoc.eventomax.notify.messaging.crew;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload para el comando de ticket/hoja de ruta de cuadrilla.
 * <p>
 * Corresponde al contrato V1 del comando "GenerateCrewTicket" con soporte de asignación de cuadrilla.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CrewTicketPayload(
        Long productionId,
        String productionName,
        String scheduledAt,
        String location,
        String status,
        String crewId,
        String crewName
) {
    public CrewTicketPayload(Long productionId, String productionName, String scheduledAt, String location, String status) {
        this(productionId, productionName, scheduledAt, location, status, null, null);
    }
}
