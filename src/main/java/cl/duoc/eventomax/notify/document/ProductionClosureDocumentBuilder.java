package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.client.catalog.CatalogReservationClient;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.repository.CrewTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ensamblador de documentos de acta de cierre operativo y administrativo.
 * <p>
 * Transforma un {@link MessageEnvelope} de comando {@code quote.gen} (clásico o enriquecido)
 * en un {@link ProductionClosureDocumentDTO} neutral, tipado e inmutable para el generador PDF.
 * <p>
 * Principios:
 * 1. Retrocompatibilidad total con payloads de 7 campos históricos.
 * 2. Cero suposición o fabricación de datos comerciales/monetarios.
 * 3. Preservación estricta de nulls.
 * 4. Resiliencia multinivel ante integración con Catalog (AVAILABLE, EMPTY, UNAVAILABLE, NOT_PROVIDED).
 * 5. Trazabilidad de instantes UTC explícita.
 */
@Component
public class ProductionClosureDocumentBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProductionClosureDocumentBuilder.class);

    private final CatalogReservationClient catalogReservationClient;
    private final CrewTicketRepository ticketRepository;
    private final Clock clock;

    public record EquipmentResolutionResult(
            List<ProductionClosureDocumentDTO.ReservedEquipmentRecordDTO> items,
            ProductionClosureDocumentDTO.CatalogAvailabilityState state
    ) {}

    public ProductionClosureDocumentBuilder() {
        this(null, null, Clock.systemUTC());
    }

    public ProductionClosureDocumentBuilder(CatalogReservationClient catalogReservationClient) {
        this(catalogReservationClient, null, Clock.systemUTC());
    }

    public ProductionClosureDocumentBuilder(CatalogReservationClient catalogReservationClient, CrewTicketRepository ticketRepository) {
        this(catalogReservationClient, ticketRepository, Clock.systemUTC());
    }

    @Autowired
    public ProductionClosureDocumentBuilder(
            @Autowired(required = false) CatalogReservationClient catalogReservationClient,
            @Autowired(required = false) CrewTicketRepository ticketRepository,
            @Autowired(required = false) Clock clock
    ) {
        this.catalogReservationClient = catalogReservationClient;
        this.ticketRepository = ticketRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Construye el DTO documental inmutable a partir del envelope del comando quote.gen.
     *
     * @param envelope Mensaje envelope recibido de RabbitMQ
     * @return DTO listo para renderizar en PDF
     */
    public ProductionClosureDocumentDTO build(MessageEnvelope<QuoteCommandPayload> envelope) {
        if (envelope == null || envelope.payload() == null) {
            throw new IllegalArgumentException("El envelope y el payload de quote.gen no pueden ser nulos");
        }

        QuoteCommandPayload payload = envelope.payload();

        if (payload.productionId() == null || payload.productionId() <= 0) {
            throw new IllegalArgumentException("productionId debe ser un identificador positivo válido");
        }
        if (!"CERRADO".equalsIgnoreCase(payload.status())) {
            throw new IllegalStateException("El acta de cierre solo puede generarse para producciones en estado CERRADO, recibido: " + payload.status());
        }

        // 1. Metadatos del Documento
        var metadata = new ProductionClosureDocumentDTO.DocumentMetadataDTO(
                "EMX-ACTA-PRD-" + payload.productionId(),
                "ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN",
                "1.0",
                Instant.now(this.clock),
                "ms-eventomax-notify"
        );

        // 2. Identificación de la Producción (con closedAtFormatted explícito)
        var production = new ProductionClosureDocumentDTO.ProductionIdentityDTO(
                payload.productionId(),
                payload.productionName() != null ? payload.productionName() : "N/A",
                "CERRADO",
                parseLocalDateTime(payload.scheduledAt()),
                payload.location() != null ? payload.location() : "N/A",
                parseLocalDateTime(payload.createdAt()),
                parseLocalDateTime(payload.closedAt()),
                formatClosedAt(payload.closedAt())
        );

        // 3. Contacto del Organizador (Preservación estricta de nulos)
        var organizer = new ProductionClosureDocumentDTO.OrganizerIdentityDTO(
                payload.organizerId() != null ? payload.organizerId() : "N/A",
                payload.organizerContactMaskedEmail(),
                payload.organizerContactName(),
                payload.organizerContactPhone()
        );

        // 4. Timeline Operacional (Ordenamiento correlativo estricto)
        List<ProductionClosureDocumentDTO.OperationalTimelineItemDTO> timeline = buildTimeline(payload.timeline());

        // 5. Equipamiento Asignado y Estado de Catálogo (Sin registros sintéticos)
        EquipmentResolutionResult eqRes = resolveEquipment(payload);
        List<ProductionClosureDocumentDTO.ReservedEquipmentRecordDTO> equipment = eqRes.items();
        ProductionClosureDocumentDTO.CatalogAvailabilityState catalogState = eqRes.state();

        // 6. Servicios Contratados (Preservación de BigDecimal y ausencia de divisa)
        List<ProductionClosureDocumentDTO.ContractedServiceRecordDTO> services = buildServices(payload.services());

        // 7. Ticket de Cuadrilla (Consulta a base de datos local de Notify aislada por productionId)
        ProductionClosureDocumentDTO.CrewTicketRecordDTO crewTicket = buildCrewTicket(payload.productionId());

        // 8. Declaración Administrativa Neutral y Fáctica
        var administrativeDeclaration = new ProductionClosureDocumentDTO.AdministrativeDeclarationDTO(
                "La producción ha alcanzado su estado terminal CERRADO. Los recursos han sido consolidados administrativamente.",
                "El cierre administrativo y documental fue efectuado con anterioridad a la fecha programada del evento por decisión operacional y logística registrada en el sistema.",
                "El presente documento constituye un registro interno de carácter estrictamente operativo y de control de inventario. No constituye factura, boleta, comprobante de pago ni instrumento tributario conforme a la normativa legal vigente."
        );

        // 9. Trazabilidad Técnica Distribuida (Diferenciación estricta de identificadores)
        var traceability = new ProductionClosureDocumentDTO.TechnicalTraceabilityDTO(
                envelope.eventId() != null ? envelope.eventId() : "N/A",
                payload.kafkaClosingEventId() != null ? payload.kafkaClosingEventId() : "N/A",
                envelope.traceId() != null ? envelope.traceId() : "N/A",
                envelope.correlationId() != null ? envelope.correlationId() : "N/A",
                payload.closingActorOid() != null ? payload.closingActorOid() : "N/A"
        );

        // 10. Estado de Conformidad (Fijo en PENDIENTE DE CONFORMIDAD sin conformidad comercial acreditada)
        var conformity = new ProductionClosureDocumentDTO.ConformityStatusDTO(
                "PENDIENTE DE CONFORMIDAD",
                "PENDIENTE DE CONFORMIDAD",
                false
        );

        return new ProductionClosureDocumentDTO(
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
                catalogState
        );
    }

    private List<ProductionClosureDocumentDTO.OperationalTimelineItemDTO> buildTimeline(List<QuoteCommandPayload.TimelineItemPayload> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<QuoteCommandPayload.TimelineItemPayload> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(it -> it.sequenceIndex() != null ? it.sequenceIndex() : 0));

        List<ProductionClosureDocumentDTO.OperationalTimelineItemDTO> result = new ArrayList<>();
        for (var it : sorted) {
            if (it == null) continue;
            result.add(new ProductionClosureDocumentDTO.OperationalTimelineItemDTO(
                    it.sequenceIndex() != null ? it.sequenceIndex() : 0,
                    it.fromStatus(),
                    it.toStatus(),
                    parseInstant(it.occurredAt()),
                    it.actor(),
                    it.eventId()
            ));
        }
        return List.copyOf(result);
    }

    private EquipmentResolutionResult resolveEquipment(QuoteCommandPayload payload) {
        if (payload == null) {
            return new EquipmentResolutionResult(null, ProductionClosureDocumentDTO.CatalogAvailabilityState.NOT_PROVIDED);
        }

        // Nivel 1: Provisto directamente en el Payload de Producciones
        if (payload.equipment() != null) {
            if (payload.equipment().isEmpty()) {
                return new EquipmentResolutionResult(List.of(), ProductionClosureDocumentDTO.CatalogAvailabilityState.EMPTY);
            }
            List<ProductionClosureDocumentDTO.ReservedEquipmentRecordDTO> list = new ArrayList<>();
            for (var eq : payload.equipment()) {
                if (eq == null) continue;
                list.add(new ProductionClosureDocumentDTO.ReservedEquipmentRecordDTO(
                        eq.equipmentId(),
                        eq.equipmentName(),
                        eq.quantity(),
                        eq.reservationStatus()
                ));
            }
            return new EquipmentResolutionResult(List.copyOf(list), ProductionClosureDocumentDTO.CatalogAvailabilityState.AVAILABLE);
        }

        // Nivel 2: Consulta resiliente mediante CatalogReservationClient
        if (this.catalogReservationClient != null) {
            try {
                CatalogReservationClient.CatalogReservationResult catResult =
                        this.catalogReservationClient.getReservationsForProduction(payload.productionId());

                if (catResult != null) {
                    if (catResult.state() == CatalogReservationClient.CatalogAvailabilityState.AVAILABLE) {
                        List<ProductionClosureDocumentDTO.ReservedEquipmentRecordDTO> list = new ArrayList<>();
                        for (var item : catResult.items()) {
                            if (item == null) continue;
                            list.add(new ProductionClosureDocumentDTO.ReservedEquipmentRecordDTO(
                                    item.equipmentId(),
                                    item.equipmentName(),
                                    item.quantity(),
                                    item.reservationStatus()
                            ));
                        }
                        return new EquipmentResolutionResult(List.copyOf(list), ProductionClosureDocumentDTO.CatalogAvailabilityState.AVAILABLE);
                    } else if (catResult.state() == CatalogReservationClient.CatalogAvailabilityState.EMPTY) {
                        return new EquipmentResolutionResult(List.of(), ProductionClosureDocumentDTO.CatalogAvailabilityState.EMPTY);
                    } else if (catResult.state() == CatalogReservationClient.CatalogAvailabilityState.UNAVAILABLE) {
                        // NO GENERAR REGISTRO SINTÉTICO: null en items y estado UNAVAILABLE
                        return new EquipmentResolutionResult(null, ProductionClosureDocumentDTO.CatalogAvailabilityState.UNAVAILABLE);
                    } else if (catResult.state() == CatalogReservationClient.CatalogAvailabilityState.NOT_PROVIDED) {
                        return new EquipmentResolutionResult(null, ProductionClosureDocumentDTO.CatalogAvailabilityState.NOT_PROVIDED);
                    }
                }
            } catch (Exception ex) {
                // Captura defensiva de cualquier excepción no controlada: NO GENERAR REGISTRO SINTÉTICO
                return new EquipmentResolutionResult(null, ProductionClosureDocumentDTO.CatalogAvailabilityState.UNAVAILABLE);
            }
        }

        // Nivel 3: Información no provista (payload antiguo sin catálogo configurado)
        return new EquipmentResolutionResult(null, ProductionClosureDocumentDTO.CatalogAvailabilityState.NOT_PROVIDED);
    }

    private List<ProductionClosureDocumentDTO.ContractedServiceRecordDTO> buildServices(List<QuoteCommandPayload.ContractedServicePayload> services) {
        if (services == null) {
            return null; // Preservar null para indicar explícitamente información no provista o fallida
        }
        if (services.isEmpty()) {
            return List.of(); // Lista vacía legítima: producción sin servicios
        }

        List<ProductionClosureDocumentDTO.ContractedServiceRecordDTO> result = new ArrayList<>();
        for (var srv : services) {
            if (srv == null) continue;
            result.add(new ProductionClosureDocumentDTO.ContractedServiceRecordDTO(
                    srv.serviceId(),
                    srv.serviceName(),
                    srv.unitPrice(), // BigDecimal puro
                    parseLocalDateTime(srv.contractedAt())
            ));
        }
        return List.copyOf(result);
    }

    private ProductionClosureDocumentDTO.CrewTicketRecordDTO buildCrewTicket(Long productionId) {
        if (this.ticketRepository == null || productionId == null) {
            return null;
        }

        try {
            var ticketOpt = this.ticketRepository.findTopByProductionIdOrderByIdDesc(productionId);
            if (ticketOpt.isPresent()) {
                var t = ticketOpt.get();
                return new ProductionClosureDocumentDTO.CrewTicketRecordDTO(
                        t.getId(),
                        t.getEventId(),
                        t.getStatus() != null ? t.getStatus() : "N/A",
                        t.getInstructions(),
                        t.getCreatedAt()
                );
            }
        } catch (Exception ex) {
            log.warn("No se pudo consultar ticket de cuadrilla para producción {}: {}", productionId, ex.getMessage());
        }

        return null;
    }

    public static String formatClosedAt(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            // 1. UTC Instant terminado en 'Z'
            if (dateStr.endsWith("Z")) {
                Instant inst = Instant.parse(dateStr);
                return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(inst) + " UTC";
            }
            // 2. Con offset explícito (+ o - con zona)
            if (dateStr.contains("+") || (dateStr.length() > 10 && dateStr.substring(10).contains("-"))) {
                OffsetDateTime odt = OffsetDateTime.parse(dateStr);
                if (odt.getOffset().equals(ZoneOffset.UTC)) {
                    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(odt) + " UTC";
                }
                return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").format(odt);
            }
            // 3. Timestamp local sin zona (histórico o sin sufijo) - no atribuirle UTC retroactivamente
            LocalDateTime ldt = LocalDateTime.parse(dateStr.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(ldt);
        } catch (Exception ex) {
            return dateStr;
        }
    }

    public static LocalDateTime parseLocalDateTime(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            if (dateStr.endsWith("Z") || dateStr.contains("+") || (dateStr.length() > 10 && dateStr.substring(10).contains("-"))) {
                try {
                    return OffsetDateTime.parse(dateStr).toLocalDateTime();
                } catch (DateTimeParseException ignored) {
                    return Instant.parse(dateStr).atZone(ZoneOffset.UTC).toLocalDateTime();
                }
            }
            return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(dateStr.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static Instant parseInstant(String instantStr) {
        if (instantStr == null || instantStr.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(instantStr);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(instantStr.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .toInstant(ZoneOffset.UTC);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
