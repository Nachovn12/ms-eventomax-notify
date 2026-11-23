package cl.duoc.eventomax.notify.messaging.quote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload para el comando de generación de acta de cierre / documento quote ("GenerateClosingQuote").
 * <p>
 * Preserva compatibilidad retroactiva estricta con los contratos históricos de 7 y 16 campos,
 * admitiendo campos opcionales aditivos de identidad verificada y cuadrilla (Diseño C).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteCommandPayload(
        // 7 Campos Obligatorios Clásicos (Retrocompatibilidad total)
        Long productionId,
        String organizerId,
        String productionName,
        String status,
        String scheduledAt,
        String location,
        String closedAt,

        // 9 Campos Opcionales Enriquecidos (Documentales)
        String createdAt,
        String organizerContactMaskedEmail,
        String organizerContactName,
        String organizerContactPhone,
        List<ContractedServicePayload> services,
        List<EquipmentReservationPayload> equipment,
        List<TimelineItemPayload> timeline,
        String kafkaClosingEventId,
        String closingActorOid,

        // 5 Campos Opcionales Aditivos (Identidades Verificadas y Cuadrilla — Diseño C)
        String organizerDisplayName,
        String organizerResolutionSource,
        String closingActorDisplayName,
        String crewId,
        String crewName
) {

    /**
     * Constructor de compatibilidad para mensajes y tests históricos de 7 campos.
     */
    public QuoteCommandPayload(
            Long productionId,
            String organizerId,
            String productionName,
            String status,
            String scheduledAt,
            String location,
            String closedAt
    ) {
        this(
                productionId,
                organizerId,
                productionName,
                status,
                scheduledAt,
                location,
                closedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Constructor de compatibilidad para contratos previos de 16 campos.
     */
    public QuoteCommandPayload(
            Long productionId,
            String organizerId,
            String productionName,
            String status,
            String scheduledAt,
            String location,
            String closedAt,
            String createdAt,
            String organizerContactMaskedEmail,
            String organizerContactName,
            String organizerContactPhone,
            List<ContractedServicePayload> services,
            List<EquipmentReservationPayload> equipment,
            List<TimelineItemPayload> timeline,
            String kafkaClosingEventId,
            String closingActorOid
    ) {
        this(
                productionId,
                organizerId,
                productionName,
                status,
                scheduledAt,
                location,
                closedAt,
                createdAt,
                organizerContactMaskedEmail,
                organizerContactName,
                organizerContactPhone,
                services,
                equipment,
                timeline,
                kafkaClosingEventId,
                closingActorOid,
                null,
                null,
                null,
                null,
                null
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractedServicePayload(
            Long serviceId,
            String serviceName,
            BigDecimal unitPrice,
            String contractedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EquipmentReservationPayload(
            Long equipmentId,
            String equipmentName,
            Integer quantity,
            String reservationStatus
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimelineItemPayload(
            Integer sequenceIndex,
            String fromStatus,
            String toStatus,
            String occurredAt,
            String actor,
            String eventId,
            String actorDisplayName
    ) {
        /**
         * Constructor de compatibilidad para eventos de bitácora de 6 campos.
         */
        public TimelineItemPayload(
                Integer sequenceIndex,
                String fromStatus,
                String toStatus,
                String occurredAt,
                String actor,
                String eventId
        ) {
            this(sequenceIndex, fromStatus, toStatus, occurredAt, actor, eventId, null);
        }
    }
}
