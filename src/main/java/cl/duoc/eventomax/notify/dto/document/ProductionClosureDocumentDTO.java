package cl.duoc.eventomax.notify.dto.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Contrato DTO inmutable para la generación del Acta de Cierre Operativo con Anexo Técnico (Diseño C).
 * Diseñado bajo principios de neutralidad, tipado estricto, tolerancia a nulos y ausencia de datos ficticios.
 */
public record ProductionClosureDocumentDTO(
        DocumentMetadataDTO metadata,
        ProductionIdentityDTO production,
        OrganizerIdentityDTO organizer,
        List<OperationalTimelineItemDTO> timeline,
        List<ReservedEquipmentRecordDTO> equipment,
        List<ContractedServiceRecordDTO> services,
        CrewTicketRecordDTO crewTicket,
        AdministrativeDeclarationDTO administrativeDeclaration,
        TechnicalTraceabilityDTO traceability,
        ConformityStatusDTO conformity,
        CatalogAvailabilityState catalogAvailability,
        CrewAssignmentDTO crewAssignment
) {

    public ProductionClosureDocumentDTO(
            DocumentMetadataDTO metadata,
            ProductionIdentityDTO production,
            OrganizerIdentityDTO organizer,
            List<OperationalTimelineItemDTO> timeline,
            List<ReservedEquipmentRecordDTO> equipment,
            List<ContractedServiceRecordDTO> services,
            CrewTicketRecordDTO crewTicket,
            AdministrativeDeclarationDTO administrativeDeclaration,
            TechnicalTraceabilityDTO traceability,
            ConformityStatusDTO conformity,
            CatalogAvailabilityState catalogAvailability
    ) {
        this(
                metadata,
                production,
                organizer,
                timeline,
                equipment,
                services,
                crewTicket,
                administrativeDeclaration,
                traceability,
                conformity,
                catalogAvailability,
                null
        );
    }

    public ProductionClosureDocumentDTO(
            DocumentMetadataDTO metadata,
            ProductionIdentityDTO production,
            OrganizerIdentityDTO organizer,
            List<OperationalTimelineItemDTO> timeline,
            List<ReservedEquipmentRecordDTO> equipment,
            List<ContractedServiceRecordDTO> services,
            CrewTicketRecordDTO crewTicket,
            AdministrativeDeclarationDTO administrativeDeclaration,
            TechnicalTraceabilityDTO traceability,
            ConformityStatusDTO conformity
    ) {
        this(
                metadata,
                production,
                organizer,
                timeline,
                equipment,
                services,
                crewTicket,
                administrativeDeclaration,
                traceability,
                conformity,
                equipment != null && !equipment.isEmpty() ? CatalogAvailabilityState.AVAILABLE :
                (equipment != null ? CatalogAvailabilityState.EMPTY : CatalogAvailabilityState.NOT_PROVIDED),
                null
        );
    }

    public enum CatalogAvailabilityState {
        /** Reservas confirmadas existentes en catálogo */
        AVAILABLE,
        /** Consulta exitosa confirmando 0 reservas */
        EMPTY,
        /** Falla de red, timeout o error 5xx de catálogo */
        UNAVAILABLE,
        /** Consulta rechazada por Catálogo (HTTP 401/403). */
        UNAUTHORIZED,
        /** Información no provista en el comando o no solicitada */
        NOT_PROVIDED
    }

    public record DocumentMetadataDTO(
            String documentCode,
            String documentTitle,
            String version,
            Instant generatedAt,
            String emittingService
    ) {}

    public record ProductionIdentityDTO(
            Long productionId,
            String productionName,
            String status,
            LocalDateTime scheduledAt,
            String location,
            LocalDateTime createdAt,
            LocalDateTime closedAt,
            String closedAtFormatted
    ) {
        public ProductionIdentityDTO(
                Long productionId,
                String productionName,
                String status,
                LocalDateTime scheduledAt,
                String location,
                LocalDateTime createdAt,
                LocalDateTime closedAt
        ) {
            this(productionId, productionName, status, scheduledAt, location, createdAt, closedAt, null);
        }
    }

    public record OrganizerIdentityDTO(
            String organizerId,
            String maskedEmail,
            String contactName,
            String phone,
            String organizerDisplayName,
            String resolutionSource
    ) {
        public OrganizerIdentityDTO(
                String organizerId,
                String maskedEmail,
                String contactName,
                String phone
        ) {
            this(organizerId, maskedEmail, contactName, phone, null, null);
        }
    }

    public record OperationalTimelineItemDTO(
            int sequenceIndex,
            String fromStatus,
            String toStatus,
            Instant occurredAt,
            String actorOid,
            String eventId,
            String actorDisplayName
    ) {
        public OperationalTimelineItemDTO(
                int sequenceIndex,
                String fromStatus,
                String toStatus,
                Instant occurredAt,
                String actorOid,
                String eventId
        ) {
            this(sequenceIndex, fromStatus, toStatus, occurredAt, actorOid, eventId, null);
        }
    }

    public record ReservedEquipmentRecordDTO(
            Long equipmentId,
            String equipmentName,
            Integer reservedQuantity,
            String reservationStatus,
            Long reservationId
    ) {
        public ReservedEquipmentRecordDTO(
                Long equipmentId,
                String equipmentName,
                Integer reservedQuantity,
                String reservationStatus
        ) {
            this(equipmentId, equipmentName, reservedQuantity, reservationStatus, null);
        }
    }

    public record ContractedServiceRecordDTO(
            Long serviceId,
            String serviceName,
            BigDecimal unitPrice,
            LocalDateTime contractedAt
    ) {}

    public record CrewAssignmentDTO(
            String crewId,
            String crewName
    ) {}

    public record CrewTicketRecordDTO(
            Long ticketId,
            String ticketEventId,
            String ticketStatus,
            String instructions,
            LocalDateTime createdAt
    ) {}

    public record AdministrativeDeclarationDTO(
            String closureReason,
            String chronologicalObservation,
            String legalDisclaimer
    ) {}

    public record TechnicalTraceabilityDTO(
            String rabbitmqCommandEventId,
            String kafkaClosingEventId,
            String traceId,
            String correlationId,
            String closingActorOid,
            String closingActorDisplayName
    ) {
        public TechnicalTraceabilityDTO(
                String rabbitmqCommandEventId,
                String kafkaClosingEventId,
                String traceId,
                String correlationId,
                String closingActorOid
        ) {
            this(rabbitmqCommandEventId, kafkaClosingEventId, traceId, correlationId, closingActorOid, null);
        }
    }

    public record ConformityStatusDTO(
            String producerApprovalStatus,
            String organizerConformityStatus,
            boolean commercialConformityClaimed
    ) {}
}
