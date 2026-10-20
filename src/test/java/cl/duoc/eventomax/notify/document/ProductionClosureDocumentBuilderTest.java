package cl.duoc.eventomax.notify.document;



import cl.duoc.eventomax.notify.client.catalog.CatalogReservationClient;

import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;

import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;

import cl.duoc.eventomax.notify.model.CrewTicket;

import cl.duoc.eventomax.notify.repository.CrewTicketRepository;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.Test;

import org.mockito.Mockito;



import java.math.BigDecimal;

import java.nio.file.Files;

import java.nio.file.Path;

import java.nio.file.Paths;

import java.time.Clock;

import java.time.Instant;

import java.time.LocalDateTime;

import java.time.ZoneOffset;

import java.util.List;

import java.util.Optional;



import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.*;



/**

 * Suite de pruebas unitarias exhaustivas para ProductionClosureDocumentBuilder.

 * Cubre los 24 escenarios requeridos por Gate 10 sin iniciar Spring Context ni infraestructura externa.

 */

class ProductionClosureDocumentBuilderTest {



    private static final Instant FIXED_NOW = Instant.parse("2026-10-19T22:57:22.951Z");

    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);



    private CrewTicketRepository crewTicketRepository;

    private CatalogReservationClient catalogReservationClient;

    private ProductionClosureDocumentBuilder builder;

    private final ObjectMapper objectMapper = new ObjectMapper();



    @BeforeEach

    void setUp() {

        crewTicketRepository = mock(CrewTicketRepository.class);

        catalogReservationClient = mock(CatalogReservationClient.class);

        builder = new ProductionClosureDocumentBuilder(catalogReservationClient, crewTicketRepository, fixedClock);

    }



    private MessageEnvelope<QuoteCommandPayload> createEnvelope(QuoteCommandPayload payload) {

        return new MessageEnvelope<>(

                "GenerateClosingQuote",

                "cmd-evt-12345678",

                "2026-10-19T22:57:22.100Z",

                "trace-distributed-999",

                "corr-flow-888",

                payload

        );

    }



    @Test

    @DisplayName("1. Payload enriquecido correcto: mapeo exhaustivo de todos los campos")

    void test1_buildFromEnrichedPayload_success() {

        var services = List.of(

                new QuoteCommandPayload.ContractedServicePayload(1L, "EP2-DEMO Iluminación Robótica", new BigDecimal("175.00"), "2026-10-19T13:46:53.249")

        );

        var equipment = List.of(

                new QuoteCommandPayload.EquipmentReservationPayload(1L, "EP2-DEMO Generador Eléctrico 50kVA", 2, "RESERVED"),

                new QuoteCommandPayload.EquipmentReservationPayload(2L, "EP2-DEMO Consola Digital 32 Canales", 3, "RESERVED")

        );

        var timeline = List.of(

                new QuoteCommandPayload.TimelineItemPayload(1, null, "SOLICITADO", "2026-10-19T15:38:29.982Z", "actor-org", "evt-1"),

                new QuoteCommandPayload.TimelineItemPayload(2, "SOLICITADO", "CONFIRMADO", "2026-10-19T16:46:53.257Z", "actor-prod", "evt-2"),

                new QuoteCommandPayload.TimelineItemPayload(3, "CONFIRMADO", "EN_MONTAJE", "2026-10-19T22:05:26.097Z", "actor-prod", "evt-3"),

                new QuoteCommandPayload.TimelineItemPayload(4, "EN_MONTAJE", "EN_EJECUCION", "2026-10-19T22:34:03.274Z", "actor-prod", "evt-4"),

                new QuoteCommandPayload.TimelineItemPayload(5, "EN_EJECUCION", "CERRADO", "2026-10-19T22:57:22.366Z", "actor-prod", "evt-5")

        );



        var payload = new QuoteCommandPayload(

                1L,

                "org-ep2-demo-001",

                "EP2-DEMO Festival Tecnológico 2026",

                "CERRADO",

                "2026-11-20T18:00:00",

                "Centro de Eventos EP2 — Santiago",

                "2026-10-19T19:57:22.361",

                "2026-10-19T12:38:29.901",

                "ig*********@gmail.com",

                null,

                null,

                services,

                equipment,

                timeline,

                "kafka-close-evt-777",

                "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds"

        );



        var envelope = createEnvelope(payload);

        ProductionClosureDocumentDTO doc = builder.build(envelope);



        assertNotNull(doc);

        assertEquals("EMX-ACTA-PRD-1", doc.metadata().documentCode());

        assertEquals("ACTA DE CIERRE OPERATIVO Y ADMINISTRATIVO DE PRODUCCIÓN", doc.metadata().documentTitle());

        assertEquals(FIXED_NOW, doc.metadata().generatedAt());

        assertEquals(1L, doc.production().productionId());

        assertEquals("EP2-DEMO Festival Tecnológico 2026", doc.production().productionName());

        assertEquals("CERRADO", doc.production().status());

        assertEquals(LocalDateTime.parse("2026-11-20T18:00:00"), doc.production().scheduledAt());

        assertEquals("Centro de Eventos EP2 — Santiago", doc.production().location());

        assertEquals("org-ep2-demo-001", doc.organizer().organizerId());

        assertEquals("ig*********@gmail.com", doc.organizer().maskedEmail());

        assertNull(doc.organizer().contactName());

        assertNull(doc.organizer().phone());



        assertEquals(5, doc.timeline().size());

        assertEquals(2, doc.equipment().size());

        assertEquals(1, doc.services().size());

        assertEquals("cmd-evt-12345678", doc.traceability().rabbitmqCommandEventId());

        assertEquals("kafka-close-evt-777", doc.traceability().kafkaClosingEventId());

        assertEquals("trace-distributed-999", doc.traceability().traceId());

        assertEquals("corr-flow-888", doc.traceability().correlationId());

        assertEquals("sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", doc.traceability().closingActorOid());

        assertFalse(doc.conformity().commercialConformityClaimed());

    }



    @Test

    @DisplayName("2. Payload antiguo de siete campos: retrocompatibilidad estricta")

    void test2_buildFromLegacy7FieldsPayload_success() {

        var payload = new QuoteCommandPayload(

                1L,

                "org-ep2-demo-001",

                "EP2-DEMO Festival Tecnológico 2026",

                "CERRADO",

                "2026-11-20T18:00:00",

                "Centro de Eventos EP2 — Santiago",

                "2026-10-19T19:57:22.361"

        );



        var envelope = createEnvelope(payload);

        ProductionClosureDocumentDTO doc = builder.build(envelope);



        assertNotNull(doc);

        assertEquals(1L, doc.production().productionId());

        assertEquals("CERRADO", doc.production().status());

        assertNull(doc.organizer().maskedEmail());

        assertNull(doc.organizer().contactName());

        assertNull(doc.organizer().phone());

        assertTrue(doc.timeline().isEmpty(), "Timeline debe ser lista vacía cuando no se proporciona");

        assertNull(doc.services(), "Servicios debe ser null (no provisto) cuando no se proporciona en payload antiguo");

        assertNull(doc.equipment(), "Equipamiento debe ser null (NOT_PROVIDED) en payload antiguo");

    }



    @Test

    @DisplayName("3. Deserialización JSON real con Jackson: retrocompatibilidad y campos nuevos")

    void test3_jacksonSerializationDeserialization_matchesContract() throws Exception {

        // JSON Histórico V1 (7 campos)

        String legacyJson = """

                {

                  "productionId": 1,

                  "organizerId": "org-ep2-demo-001",

                  "productionName": "EP2-DEMO Festival Tecnológico 2026",

                  "status": "CERRADO",

                  "scheduledAt": "2026-11-20T18:00:00",

                  "location": "Centro de Eventos EP2 — Santiago",

                  "closedAt": "2026-10-19T19:57:22"

                }

                """;



        QuoteCommandPayload parsedLegacy = objectMapper.readValue(legacyJson, QuoteCommandPayload.class);

        assertNotNull(parsedLegacy);

        assertEquals(1L, parsedLegacy.productionId());

        assertEquals("CERRADO", parsedLegacy.status());

        assertNull(parsedLegacy.services());

        assertNull(parsedLegacy.equipment());



        // JSON Enriquecido V2 con campos extra

        String enrichedJson = """

                {

                  "productionId": 1,

                  "organizerId": "org-ep2-demo-001",

                  "productionName": "EP2-DEMO Festival Tecnológico 2026",

                  "status": "CERRADO",

                  "scheduledAt": "2026-11-20T18:00:00",

                  "location": "Centro de Eventos EP2 — Santiago",

                  "closedAt": "2026-10-19T19:57:22",

                  "organizerContactMaskedEmail": "ig*********@gmail.com",

                  "kafkaClosingEventId": "k-evt-123",

                  "services": [

                    {

                      "serviceId": 1,

                      "serviceName": "EP2-DEMO Iluminación Robótica",

                      "unitPrice": 175.00,

                      "contractedAt": "2026-10-19T13:46:53"

                    }

                  ],

                  "unknownFutureFieldIgnored": "someValue"

                }

                """;



        QuoteCommandPayload parsedEnriched = objectMapper.readValue(enrichedJson, QuoteCommandPayload.class);

        assertNotNull(parsedEnriched);

        assertEquals("ig*********@gmail.com", parsedEnriched.organizerContactMaskedEmail());

        assertEquals("k-evt-123", parsedEnriched.kafkaClosingEventId());

        assertEquals(1, parsedEnriched.services().size());

        assertEquals(new BigDecimal("175.00"), parsedEnriched.services().get(0).unitPrice());



        // Serialización

        String serialized = objectMapper.writeValueAsString(parsedEnriched);

        assertTrue(serialized.contains("EP2-DEMO Iluminación Robótica"));

    }



    @Test

    @DisplayName("4. Campos opcionales ausentes: tolerancia a listas y valores nulos")

    void test4_optionalFieldsAbsent_fallbackGracefully() {

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", null, null, null,

                null, null, null, null, null, null, null, null, null

        );

        var envelope = createEnvelope(payload);

        var doc = builder.build(envelope);



        assertNotNull(doc);

        assertNull(doc.production().scheduledAt());

        assertNull(doc.production().closedAt());

        assertNull(doc.production().createdAt());

        assertEquals("N/A", doc.production().location());

        assertTrue(doc.timeline().isEmpty());

        assertNull(doc.services(), "Servicios debe ser null si no fue provisto");

    }



    @Test

    @DisplayName("5. Contacto con nombre y teléfono NULL: preservación estricta de ausencia de datos")

    void test5_contactWithNullNameAndPhone_preservesNulls() {

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Santiago", "2026-10-19T19:57:22",

                null, "ig*********@gmail.com", null, null, null, null, null, null, null

        );

        var envelope = createEnvelope(payload);

        var doc = builder.build(envelope);



        assertEquals("ig*********@gmail.com", doc.organizer().maskedEmail());

        assertNull(doc.organizer().contactName(), "contactName debe ser null, no inventado");

        assertNull(doc.organizer().phone(), "phone debe ser null, no inventado");

    }



    @Test

    @DisplayName("6. Ticket de cuadrilla disponible en base de datos local: mapeo exitoso")

    void test6_crewTicketAvailable_mappedCorrectly() {

        var ticket = new CrewTicket(

                1L,

                "33d5f659-8c07-44ec-b413-456abc5f0aa1",

                "EP2-DEMO Festival Tecnológico 2026",

                LocalDateTime.parse("2026-11-20T18:00:00"),

                "Centro de Eventos EP2 — Santiago",

                "EN_MONTAJE",

                "Hoja de ruta para montaje de equipamiento..."

        );

        when(crewTicketRepository.findTopByProductionIdOrderByIdDesc(1L)).thenReturn(Optional.of(ticket));



        var payload = new QuoteCommandPayload(1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var doc = builder.build(createEnvelope(payload));



        assertNotNull(doc.crewTicket());

        assertEquals("33d5f659-8c07-44ec-b413-456abc5f0aa1", doc.crewTicket().ticketEventId());

        assertEquals("EN_MONTAJE", doc.crewTicket().ticketStatus());

        assertEquals("Hoja de ruta para montaje de equipamiento...", doc.crewTicket().instructions());

    }



    @Test

    @DisplayName("7. Ticket de cuadrilla inexistente: representación de ausencia real")

    void test7_crewTicketNotFound_mapsToNull() {

        when(crewTicketRepository.findTopByProductionIdOrderByIdDesc(anyLong())).thenReturn(Optional.empty());



        var payload = new QuoteCommandPayload(1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var doc = builder.build(createEnvelope(payload));



        assertNull(doc.crewTicket(), "crewTicket debe ser null cuando no existe registro en base de datos");

    }



    @Test

    @DisplayName("8. Consulta Catalog con reservas: estado AVAILABLE mapeado correctamente")

    void test8_catalogClientAvailableWithReservations_mappedCorrectly() {

        var items = List.of(

                new CatalogReservationClient.CatalogReservationItem(1L, "EP2-DEMO Generador Eléctrico 50kVA", 2, "RESERVED"),

                new CatalogReservationClient.CatalogReservationItem(2L, "EP2-DEMO Consola Digital 32 Canales", 3, "RESERVED")

        );

        when(catalogReservationClient.getReservationsForProduction(1L))

                .thenReturn(CatalogReservationClient.CatalogReservationResult.available(items));



        var payload = new QuoteCommandPayload(1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var doc = builder.build(createEnvelope(payload));



        assertNotNull(doc.equipment());

        assertEquals(2, doc.equipment().size());

        assertEquals(1L, doc.equipment().get(0).equipmentId());

        assertEquals("EP2-DEMO Generador Eléctrico 50kVA", doc.equipment().get(0).equipmentName());

        assertEquals(2, doc.equipment().get(0).reservedQuantity());

    }



    @Test

    @DisplayName("9. Catalog confirma cero reservas: estado EMPTY mapeado a lista vacía")

    void test9_catalogClientConfirmsZeroReservations_mapsToEmptyList() {

        when(catalogReservationClient.getReservationsForProduction(1L))

                .thenReturn(CatalogReservationClient.CatalogReservationResult.empty());



        var payload = new QuoteCommandPayload(1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var doc = builder.build(createEnvelope(payload));



        assertNotNull(doc.equipment());

        assertTrue(doc.equipment().isEmpty(), "Debe ser lista vacía para confirmar cero reservas");

    }



    @Test

    @DisplayName("10. Catalog no disponible o timeout: estado UNAVAILABLE sin registros sintéticos de equipamiento")

    void test10_catalogClientUnavailableTimeout_mapsToUnavailableRecord() {

        when(catalogReservationClient.getReservationsForProduction(1L))

                .thenReturn(CatalogReservationClient.CatalogReservationResult.unavailable("Timeout de conexión 2000ms"));



        var payload = new QuoteCommandPayload(1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var doc = builder.build(createEnvelope(payload));



        assertNull(doc.equipment(), "Equipamiento debe ser null ante indisponibilidad, sin crear items sintéticos");

        assertEquals(ProductionClosureDocumentDTO.CatalogAvailabilityState.UNAVAILABLE, doc.catalogAvailability());

    }



    @Test

    @DisplayName("11. Información Catalog no proporcionada: estado NOT_PROVIDED mapeado a null")

    void test11_catalogInformationNotProvided_mapsToNull() {

        when(catalogReservationClient.getReservationsForProduction(1L))

                .thenReturn(CatalogReservationClient.CatalogReservationResult.notProvided());



        var payload = new QuoteCommandPayload(1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var doc = builder.build(createEnvelope(payload));



        assertNull(doc.equipment(), "Debe ser null cuando no fue provisto ni consultado");

    }



    @Test

    @DisplayName("12. Tarifa 175.00 sin moneda: preservación de precisión BigDecimal sin divisa")

    void test12_serviceUnitPrice175WithoutCurrency_preservedExact() {

        var services = List.of(

                new QuoteCommandPayload.ContractedServicePayload(1L, "EP2-DEMO Iluminación Robótica", new BigDecimal("175.00"), null)

        );

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22",

                null, null, null, null, services, null, null, null, null

        );

        var doc = builder.build(createEnvelope(payload));



        assertEquals(1, doc.services().size());

        assertEquals(new BigDecimal("175.00"), doc.services().get(0).unitPrice());

    }



    @Test

    @DisplayName("13. Tarifa NULL distinta de cero: preservación de tarifa ausente vs tarifa cero")

    void test13_serviceUnitPriceNull_preservedAsNull() {

        var services = List.of(

                new QuoteCommandPayload.ContractedServicePayload(1L, "Servicio Sin Tarifa", null, null),

                new QuoteCommandPayload.ContractedServicePayload(2L, "Servicio Gratuito Registrado", BigDecimal.ZERO, null)

        );

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22",

                null, null, null, null, services, null, null, null, null

        );

        var doc = builder.build(createEnvelope(payload));



        assertEquals(2, doc.services().size());

        assertNull(doc.services().get(0).unitPrice(), "Tarifa desconocida debe ser null, no cero");

        assertEquals(BigDecimal.ZERO, doc.services().get(1).unitPrice(), "Tarifa explícita cero debe ser cero");

    }



    @Test

    @DisplayName("14. Fechas Instant UTC: preservación exacta y generatedAt coincide con Clock")

    void test14_timestampsInstant_handledInUtc() {

        var timeline = List.of(

                new QuoteCommandPayload.TimelineItemPayload(1, null, "SOLICITADO", "2026-10-19T15:38:29.982Z", "actor", "evt-1")

        );

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22",

                null, null, null, null, null, null, timeline, null, null

        );

        var doc = builder.build(createEnvelope(payload));



        assertEquals(FIXED_NOW, doc.metadata().generatedAt());

        assertEquals(Instant.parse("2026-10-19T15:38:29.982Z"), doc.timeline().get(0).occurredAt());

    }



    @Test

    @DisplayName("15. Instant UTC terminado en Z: formateo explícito con sufijo UTC preservado")

    void test15_utcClosedAtEndingInZ_formattedWithExplicitUtcSuffix() {

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T22:57:22.361Z"

        );

        var doc = builder.build(createEnvelope(payload));



        assertEquals(LocalDateTime.parse("2026-11-20T18:00:00"), doc.production().scheduledAt());

        assertEquals("2026-10-19 22:57:22 UTC", doc.production().closedAtFormatted());

    }



    @Test

    @DisplayName("15b. Timestamp local sin zona: formateo preservado sin sufijo UTC inventado")

    void test15b_localClosedAtWithoutZone_formattedWithoutUtcSuffix() {

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22.361"

        );

        var doc = builder.build(createEnvelope(payload));



        assertEquals("2026-10-19 19:57:22", doc.production().closedAtFormatted());

        assertEquals(LocalDateTime.parse("2026-10-19T19:57:22.361"), doc.production().closedAt());

    }



    @Test

    @DisplayName("15c. Timestamp con offset explícito preservado")

    void test15c_offsetClosedAt_formattedCorrectly() {

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22-03:00"

        );

        var doc = builder.build(createEnvelope(payload));



        assertNotNull(doc.production().closedAtFormatted());

        assertTrue(doc.production().closedAtFormatted().contains("19:57:22"));

    }



    @Test

    @DisplayName("16. Timestamp histórico ausente: se conserva null y no se sustituye por Clock actual")

    void test16_historicTimestampAbsent_preservedAsNull() {

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", null, "Loc", null

        );

        var doc = builder.build(createEnvelope(payload));



        assertNull(doc.production().scheduledAt(), "Fecha programada ausente debe ser null");

        assertNull(doc.production().closedAt(), "Fecha de cierre ausente debe ser null");

        assertNull(doc.production().createdAt(), "Fecha de creación ausente debe ser null");

        assertNotEquals(FIXED_NOW, doc.production().closedAt());

    }



    @Test

    @DisplayName("17. Timeline desordenado: ordenamiento estricto por sequenceIndex")

    void test17_timelineUnordered_sortedChronologically() {

        var timeline = List.of(

                new QuoteCommandPayload.TimelineItemPayload(3, "CONFIRMADO", "EN_MONTAJE", "2026-10-19T22:05:00Z", "actor", "evt-3"),

                new QuoteCommandPayload.TimelineItemPayload(1, null, "SOLICITADO", "2026-10-19T15:38:00Z", "actor", "evt-1"),

                new QuoteCommandPayload.TimelineItemPayload(2, "SOLICITADO", "CONFIRMADO", "2026-10-19T16:46:00Z", "actor", "evt-2")

        );

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22",

                null, null, null, null, null, null, timeline, null, null

        );

        var doc = builder.build(createEnvelope(payload));



        assertEquals(1, doc.timeline().get(0).sequenceIndex());

        assertEquals(2, doc.timeline().get(1).sequenceIndex());

        assertEquals(3, doc.timeline().get(2).sequenceIndex());

        assertEquals("SOLICITADO", doc.timeline().get(0).toStatus());

        assertEquals("CONFIRMADO", doc.timeline().get(1).toStatus());

        assertEquals("EN_MONTAJE", doc.timeline().get(2).toStatus());

    }



    @Test

    @DisplayName("18. Timeline incompleto: sin fabricación de transiciones ausentes")

    void test18_timelineIncomplete_noFabrication() {

        var timeline = List.of(

                new QuoteCommandPayload.TimelineItemPayload(1, null, "SOLICITADO", "2026-10-19T15:38:00Z", "actor", "evt-1"),

                new QuoteCommandPayload.TimelineItemPayload(5, "EN_EJECUCION", "CERRADO", "2026-10-19T22:57:00Z", "actor", "evt-5")

        );

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22",

                null, null, null, null, null, null, timeline, null, null

        );

        var doc = builder.build(createEnvelope(payload));



        assertEquals(2, doc.timeline().size(), "No deben fabricarse transiciones intermedias");

        assertEquals(1, doc.timeline().get(0).sequenceIndex());

        assertEquals(5, doc.timeline().get(1).sequenceIndex());

    }



    @Test

    @DisplayName("19. Identificadores independientes: RabbitMQ vs Kafka vs Trace vs Correlation")

    void test19_distinctEventIdsTraceability() {

        var payload = new QuoteCommandPayload(

                1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22",

                null, null, null, null, null, null, null, "kafka-event-uuid-111", "actor-oid-222"

        );

        var envelope = new MessageEnvelope<>(

                "GenerateClosingQuote",

                "rabbitmq-cmd-uuid-333",

                "2026-10-19T22:57:22Z",

                "trace-uuid-444",

                "correlation-uuid-555",

                payload

        );

        var doc = builder.build(envelope);



        assertEquals("rabbitmq-cmd-uuid-333", doc.traceability().rabbitmqCommandEventId());

        assertEquals("kafka-event-uuid-111", doc.traceability().kafkaClosingEventId());

        assertEquals("trace-uuid-444", doc.traceability().traceId());

        assertEquals("correlation-uuid-555", doc.traceability().correlationId());

        assertEquals("actor-oid-222", doc.traceability().closingActorOid());

    }



    @Test

    @DisplayName("20. Validación de entrada: productionId inválido o estado no CERRADO lanzan excepciones controladas")

    void test20_invalidProductionIdOrStatus_throwsExpectedException() {

        // Production ID null

        var pIdNull = new QuoteCommandPayload(null, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        assertThrows(IllegalArgumentException.class, () -> builder.build(createEnvelope(pIdNull)));



        // Production ID negativo

        var pIdNeg = new QuoteCommandPayload(-5L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        assertThrows(IllegalArgumentException.class, () -> builder.build(createEnvelope(pIdNeg)));



        // Estado no CERRADO

        var pStatusExec = new QuoteCommandPayload(1L, "org-001", "Demo", "EN_EJECUCION", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        assertThrows(IllegalStateException.class, () -> builder.build(createEnvelope(pStatusExec)));



        // Envelope nulo

        assertThrows(IllegalArgumentException.class, () -> builder.build(null));

    }



    @Test

    @DisplayName("21. Dos producciones distintas: aislamiento estricto sin contaminación cruzada")

    void test21_twoDistinctProductions_isolatedNoContamination() {

        var ticket1 = new CrewTicket(1L, "evt-ticket-1", "Prod 1", LocalDateTime.now(), "Loc 1", "EN_MONTAJE", "Inst 1");

        var ticket2 = new CrewTicket(2L, "evt-ticket-2", "Prod 2", LocalDateTime.now(), "Loc 2", "EN_MONTAJE", "Inst 2");



        when(crewTicketRepository.findTopByProductionIdOrderByIdDesc(1L)).thenReturn(Optional.of(ticket1));

        when(crewTicketRepository.findTopByProductionIdOrderByIdDesc(2L)).thenReturn(Optional.of(ticket2));



        var p1 = new QuoteCommandPayload(1L, "org-001", "Prod 1", "CERRADO", "2026-11-20T18:00:00", "Loc 1", "2026-10-19T19:57:22");

        var p2 = new QuoteCommandPayload(2L, "org-002", "Prod 2", "CERRADO", "2026-11-20T18:00:00", "Loc 2", "2026-10-19T19:57:22");



        var doc1 = builder.build(createEnvelope(p1));

        var doc2 = builder.build(createEnvelope(p2));



        assertEquals("EMX-ACTA-PRD-1", doc1.metadata().documentCode());

        assertEquals("EMX-ACTA-PRD-2", doc2.metadata().documentCode());

        assertEquals("evt-ticket-1", doc1.crewTicket().ticketEventId());

        assertEquals("evt-ticket-2", doc2.crewTicket().ticketEventId());

        verify(crewTicketRepository, times(1)).findTopByProductionIdOrderByIdDesc(1L);

        verify(crewTicketRepository, times(1)).findTopByProductionIdOrderByIdDesc(2L);

    }



    @Test

    @DisplayName("22. Ausencia de PII en excepciones: mensajes seguros y controlados")

    void test22_noPiiInExceptionsOrLogging() {

        var payload = new QuoteCommandPayload(1L, "org-secret-email@corp.com", "Demo", "EN_MONTAJE", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var ex = assertThrows(IllegalStateException.class, () -> builder.build(createEnvelope(payload)));



        assertFalse(ex.getMessage().contains("org-secret-email@corp.com"), "El mensaje de excepción no debe contener credenciales ni emails");

        assertFalse(ex.getMessage().contains("password"));

        assertFalse(ex.getMessage().contains("Bearer"));

    }



    @Test

    @DisplayName("23. Integridad del DTO final: inmutabilidad y estructuras completas")

    void test23_dtoIntegrityAndImmutability() {

        var payload = new QuoteCommandPayload(1L, "org-001", "Demo", "CERRADO", "2026-11-20T18:00:00", "Loc", "2026-10-19T19:57:22");

        var doc = builder.build(createEnvelope(payload));



        assertNotNull(doc.metadata());

        assertNotNull(doc.production());

        assertNotNull(doc.organizer());

        assertNotNull(doc.administrativeDeclaration());

        assertNotNull(doc.traceability());

        assertNotNull(doc.conformity());

        assertEquals("PENDIENTE DE CONFORMIDAD", doc.conformity().producerApprovalStatus());

        assertEquals("PENDIENTE DE CONFORMIDAD", doc.conformity().organizerConformityStatus());

        assertFalse(doc.conformity().commercialConformityClaimed());

    }



    @Test

    @DisplayName("24. Compatibilidad integral con OpenPdfClosureDocumentGenerator: genera PDF válido sin alterar diseño")

    void test24_compatibilityWithOpenPdfClosureDocumentGenerator() throws Exception {

        // Ensamble de producción completa mediante el Builder

        var services = List.of(

                new QuoteCommandPayload.ContractedServicePayload(1L, "EP2-DEMO Iluminación Robótica", new BigDecimal("175.00"), "2026-10-19T13:46:53")

        );

        var equipment = List.of(

                new QuoteCommandPayload.EquipmentReservationPayload(1L, "EP2-DEMO Generador Eléctrico 50kVA", 2, "RESERVED"),

                new QuoteCommandPayload.EquipmentReservationPayload(2L, "EP2-DEMO Consola Digital 32 Canales", 3, "RESERVED")

        );

        var timeline = List.of(

                new QuoteCommandPayload.TimelineItemPayload(1, null, "SOLICITADO", "2026-10-19T15:38:29Z", "actor-1", "evt-1"),

                new QuoteCommandPayload.TimelineItemPayload(2, "SOLICITADO", "CONFIRMADO", "2026-10-19T16:46:53Z", "actor-2", "evt-2"),

                new QuoteCommandPayload.TimelineItemPayload(3, "CONFIRMADO", "EN_MONTAJE", "2026-10-19T22:05:26Z", "actor-2", "evt-3"),

                new QuoteCommandPayload.TimelineItemPayload(4, "EN_MONTAJE", "EN_EJECUCION", "2026-10-19T22:34:03Z", "actor-2", "evt-4"),

                new QuoteCommandPayload.TimelineItemPayload(5, "EN_EJECUCION", "CERRADO", "2026-10-19T22:57:22Z", "actor-2", "evt-5")

        );



        var payload = new QuoteCommandPayload(

                1L,

                "org-ep2-demo-001",

                "EP2-DEMO Festival Tecnológico 2026",

                "CERRADO",

                "2026-11-20T18:00:00",

                "Centro de Eventos EP2 — Santiago",

                "2026-10-19T19:57:22.361",

                "2026-10-19T12:38:29.901",

                "ig*********@gmail.com",

                null,

                null,

                services,

                equipment,

                timeline,

                "kafka-close-evt-777",

                "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds"

        );



        var ticket = new CrewTicket(

                1L,

                "33d5f659-8c07-44ec-b413-456abc5f0aa1",

                "EP2-DEMO Festival Tecnológico 2026",

                LocalDateTime.parse("2026-11-20T18:00:00"),

                "Centro de Eventos EP2 — Santiago",

                "EN_MONTAJE",

                "Hoja de ruta para montaje de equipamiento de la producción..."

        );

        when(crewTicketRepository.findTopByProductionIdOrderByIdDesc(1L)).thenReturn(Optional.of(ticket));



        ProductionClosureDocumentDTO doc = builder.build(createEnvelope(payload));



        // Renderizado del PDF mediante el generador oficial aprobado

        var generator = new OpenPdfClosureDocumentGenerator();

        byte[] pdfBytes = generator.generatePdfBytes(doc, false);



        assertNotNull(pdfBytes);

        assertTrue(pdfBytes.length > 5000, "El PDF generado debe contener estructura completa (> 5KB)");



        // Guardado exclusivo en target/test-output/ para verificación forense

        Path testOutputDir = Paths.get("target", "test-output");

        Files.createDirectories(testOutputDir);

        Path testPdfPath = testOutputDir.resolve("acta-cierre-builder-b2-1-test.pdf");

        Files.write(testPdfPath, pdfBytes);

        assertTrue(Files.exists(testPdfPath));



        // Validación de encabezado PDF estándar (%PDF-1.4)

        String header = new String(pdfBytes, 0, 8);

        assertTrue(header.startsWith("%PDF-"), "El documento generado debe tener un encabezado PDF válido");

    }

}

