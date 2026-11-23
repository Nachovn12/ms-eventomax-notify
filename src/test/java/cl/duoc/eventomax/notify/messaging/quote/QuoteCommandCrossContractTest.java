package cl.duoc.eventomax.notify.messaging.quote;

import cl.duoc.eventomax.notify.document.ProductionClosureDocumentBuilder;
import cl.duoc.eventomax.notify.dto.document.ProductionClosureDocumentDTO;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba contractual reproducible entre ms-eventomax-productions y ms-eventomax-notify.
 * <p>
 * Valida que los mensajes JSON generados por el publicador de Producciones B2.2
 * son deserializados y consumidos fielmente por Notify B2.1/PRE-B3 sin requerir
 * una biblioteca compartida ni modificar procesos en ejecución.
 */
class QuoteCommandCrossContractTest {

    private ObjectMapper objectMapper;
    private ProductionClosureDocumentBuilder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        builder = new ProductionClosureDocumentBuilder();
    }

    @Test
    @DisplayName("1. Contrato Enriquecido: Deserialización de JSON canónico emitido por Productions B2.2")
    void test1_enrichedJsonFromProductions_deserializedAccurately() throws Exception {
        // Wire JSON exacto emitido por RabbitMQCommandPublisher en Productions B2.2
        String productionsWireJson = """
        {
          "type": "GenerateClosingQuote",
          "eventId": "274e40d9-cb8e-4205-93a7-c62605d298bd",
          "timestamp": "2026-10-19T22:57:22.500Z",
          "traceId": "trace-ep2-demo-001",
          "correlationId": "corr-ep2-demo-001",
          "payload": {
            "productionId": 1,
            "organizerId": "org-ep2-demo-001",
            "productionName": "EP2-DEMO Festival Tecnológico 2026",
            "status": "CERRADO",
            "scheduledAt": "2026-11-20T18:00",
            "location": "Centro de Eventos EP2 – Santiago",
            "closedAt": "2026-10-19T22:57:22.361Z",
            "createdAt": "2026-10-19T10:00:00",
            "organizerContactMaskedEmail": "ig*********@gmail.com",
            "organizerContactName": "Ignacio Docente",
            "organizerContactPhone": "+56912345678",
            "services": [
              {
                "serviceId": 1,
                "serviceName": "EP2-DEMO Iluminación Robótica",
                "unitPrice": 175.00,
                "contractedAt": "2026-10-19T13:46:53"
              }
            ],
            "equipment": null,
            "timeline": [
              {
                "sequenceIndex": 1,
                "fromStatus": null,
                "toStatus": "SOLICITADO",
                "occurredAt": "2026-10-19T15:38:29Z",
                "actor": "organizador-oid",
                "eventId": "evt-1"
              },
              {
                "sequenceIndex": 2,
                "fromStatus": "SOLICITADO",
                "toStatus": "CONFIRMADO",
                "occurredAt": "2026-10-19T16:46:53Z",
                "actor": "productor-oid",
                "eventId": "evt-2"
              },
              {
                "sequenceIndex": 3,
                "fromStatus": "EN_EJECUCION",
                "toStatus": "CERRADO",
                "occurredAt": "2026-10-19T22:57:22Z",
                "actor": "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds",
                "eventId": "274e40d9-cb8e-4205-93a7-c62605d298bd"
              }
            ],
            "kafkaClosingEventId": "274e40d9-cb8e-4205-93a7-c62605d298bd",
            "closingActorOid": "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds"
          }
        }
        """;

        MessageEnvelope<QuoteCommandPayload> envelope = objectMapper.readValue(
                productionsWireJson,
                new TypeReference<>() {}
        );

        assertNotNull(envelope);
        assertEquals("GenerateClosingQuote", envelope.type());
        assertEquals("274e40d9-cb8e-4205-93a7-c62605d298bd", envelope.eventId());
        assertEquals("trace-ep2-demo-001", envelope.traceId());

        QuoteCommandPayload payload = envelope.payload();
        assertNotNull(payload);
        assertEquals(1L, payload.productionId());
        assertEquals("org-ep2-demo-001", payload.organizerId());
        assertEquals("EP2-DEMO Festival Tecnológico 2026", payload.productionName());
        assertEquals("CERRADO", payload.status());
        assertEquals("2026-11-20T18:00", payload.scheduledAt());
        assertEquals("2026-10-19T22:57:22.361Z", payload.closedAt());
        assertEquals("ig*********@gmail.com", payload.organizerContactMaskedEmail());

        // Sub-records anidados
        assertNotNull(payload.services());
        assertEquals(1, payload.services().size());
        assertEquals(1L, payload.services().get(0).serviceId());
        assertEquals("EP2-DEMO Iluminación Robótica", payload.services().get(0).serviceName());
        assertEquals(new BigDecimal("175.00"), payload.services().get(0).unitPrice());

        assertNotNull(payload.timeline());
        assertEquals(3, payload.timeline().size());
        assertEquals("SOLICITADO", payload.timeline().get(0).toStatus());
        assertEquals("CERRADO", payload.timeline().get(2).toStatus());

        assertEquals("274e40d9-cb8e-4205-93a7-c62605d298bd", payload.kafkaClosingEventId());
        assertEquals("sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", payload.closingActorOid());

        // Verificación de ensamblado a DTO documental
        ProductionClosureDocumentDTO doc = builder.build(envelope);
        assertNotNull(doc);
        assertEquals("2026-10-19 22:57:22 UTC", doc.production().closedAtFormatted());
        assertEquals(LocalDateTime.parse("2026-11-20T18:00:00"), doc.production().scheduledAt());
        assertEquals(3, doc.timeline().size());
        assertEquals(1, doc.services().size());
        assertEquals(ProductionClosureDocumentDTO.CatalogAvailabilityState.NOT_PROVIDED, doc.catalogAvailability());
    }

    @Test
    @DisplayName("2. Contrato Clásico: Deserialización de payload histórico de 7 campos")
    void test2_legacySevenFieldJson_deserializedAccurately() throws Exception {
        String legacyWireJson = """
        {
          "type": "GenerateClosingQuote",
          "eventId": "hist-001-uuid",
          "timestamp": "2026-10-19T23:00:00Z",
          "traceId": "trace-hist-001",
          "correlationId": "corr-hist-001",
          "payload": {
            "productionId": 1,
            "organizerId": "org-ep2-demo-001",
            "productionName": "EP2-DEMO Festival Tecnológico 2026",
            "status": "CERRADO",
            "scheduledAt": "2026-11-20T18:00",
            "location": "Centro de Eventos EP2 – Santiago",
            "closedAt": "2026-10-19T19:57:22"
          }
        }
        """;

        MessageEnvelope<QuoteCommandPayload> envelope = objectMapper.readValue(
                legacyWireJson,
                new TypeReference<>() {}
        );

        assertNotNull(envelope);
        QuoteCommandPayload payload = envelope.payload();
        assertNotNull(payload);
        assertEquals(1L, payload.productionId());
        assertNull(payload.services());
        assertNull(payload.equipment());
        assertNull(payload.timeline());
        assertNull(payload.kafkaClosingEventId());
        assertNull(payload.closingActorOid());

        ProductionClosureDocumentDTO doc = builder.build(envelope);
        assertNotNull(doc);
        // Histórico sin Z: preserva formato local sin atribuirle UTC
        assertEquals("2026-10-19 19:57:22", doc.production().closedAtFormatted());
        assertNull(doc.services()); // no provisto
        assertNull(doc.equipment()); // no provisto
        assertEquals(ProductionClosureDocumentDTO.CatalogAvailabilityState.NOT_PROVIDED, doc.catalogAvailability());
    }

    @Test
    @DisplayName("3. Tolerancia de Contrato: Deserialización con campos desconocidos futuros (@JsonIgnoreProperties)")
    void test3_unknownFieldsIgnoredGracefully() throws Exception {
        String futureJson = """
        {
          "type": "GenerateClosingQuote",
          "eventId": "future-uuid",
          "timestamp": "2026-10-20T00:00:00Z",
          "traceId": "future-trace",
          "correlationId": "future-corr",
          "unknownRootField": "ignored",
          "payload": {
            "productionId": 99,
            "organizerId": "org-future",
            "productionName": "Futuro Evento",
            "status": "CERRADO",
            "scheduledAt": "2027-01-01T20:00",
            "location": "Arena",
            "closedAt": "2027-01-02T02:00:00Z",
            "futureFieldA": 12345,
            "futureFieldB": { "nested": true }
          }
        }
        """;

        assertDoesNotThrow(() -> {
            MessageEnvelope<QuoteCommandPayload> envelope = objectMapper.readValue(
                    futureJson,
                    new TypeReference<>() {}
            );
            assertEquals(99L, envelope.payload().productionId());
        });
    }

    @Test
    @DisplayName("4. Contrato Diseño C (21 campos): Deserialización fiel del JSON emitido por Productions PR #31")
    void test4_designC21FieldJsonFromProductions_deserializedAndMappedAccurately() throws Exception {
        String designCWireJson = """
        {
          "type": "GenerateClosingQuote",
          "eventId": "rmq-cmd-design-c-001",
          "timestamp": "2026-11-23T01:00:00Z",
          "traceId": "trace-design-c-001",
          "correlationId": "corr-design-c-001",
          "payload": {
            "productionId": 42,
            "organizerId": "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222",
            "productionName": "Cumbre Ficticia Diseño C 2026",
            "status": "CERRADO",
            "scheduledAt": "2026-12-15T19:00",
            "location": "Recinto Ficticio — Santiago",
            "closedAt": "2026-11-23T00:59:55Z",
            "createdAt": "2026-11-20T14:00:00",
            "organizerContactMaskedEmail": "or*******@ejemplo.invalid",
            "organizerContactName": "Contacto Externo Ficticio",
            "organizerContactPhone": null,
            "services": [
              {
                "serviceId": 10,
                "serviceName": "Ingeniería de Sonido FOH (Prueba)",
                "unitPrice": 950000.00,
                "contractedAt": "2026-11-20T15:00:00"
              }
            ],
            "equipment": null,
            "timeline": [
              {
                "sequenceIndex": 1,
                "fromStatus": null,
                "toStatus": "SOLICITADO",
                "occurredAt": "2026-11-20T14:00:00Z",
                "actor": "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222",
                "eventId": "evt-hist-001"
              },
              {
                "sequenceIndex": 2,
                "fromStatus": "EN_EJECUCION",
                "toStatus": "CERRADO",
                "occurredAt": "2026-11-23T00:59:55Z",
                "actor": "11111111-1111-1111-1111-111111111111:33333333-3333-3333-3333-333333333333",
                "eventId": "evt-close-002",
                "actorDisplayName": "Carlos Paredes Ficticio"
              }
            ],
            "kafkaClosingEventId": "evt-close-002",
            "closingActorOid": "11111111-1111-1111-1111-111111111111:33333333-3333-3333-3333-333333333333",
            "organizerDisplayName": "Andrea Morales Ficticia",
            "organizerResolutionSource": "ENTRA_GRAPH",
            "closingActorDisplayName": "Carlos Paredes Ficticio",
            "crewId": "crew-prueba-sur",
            "crewName": "Cuadrilla Técnica Ficticia Sur"
          }
        }
        """;

        MessageEnvelope<QuoteCommandPayload> envelope = objectMapper.readValue(
                designCWireJson,
                new TypeReference<>() {}
        );

        QuoteCommandPayload payload = envelope.payload();
        assertEquals("Andrea Morales Ficticia", payload.organizerDisplayName());
        assertEquals("ENTRA_GRAPH", payload.organizerResolutionSource());
        assertEquals("Carlos Paredes Ficticio", payload.closingActorDisplayName());
        assertEquals("crew-prueba-sur", payload.crewId());
        assertEquals("Cuadrilla Técnica Ficticia Sur", payload.crewName());
        assertNull(payload.timeline().get(0).actorDisplayName(), "Evento histórico sin actorDisplayName debe quedar null");
        assertEquals("Carlos Paredes Ficticio", payload.timeline().get(1).actorDisplayName());

        ProductionClosureDocumentDTO doc = builder.build(envelope);
        assertEquals("ACTA DE CIERRE OPERATIVO", doc.metadata().documentTitle());
        assertEquals("2.0", doc.metadata().version());
        assertEquals("Andrea Morales Ficticia", doc.organizer().organizerDisplayName());
        assertEquals("ENTRA_GRAPH", doc.organizer().resolutionSource());
        assertEquals("Carlos Paredes Ficticio", doc.traceability().closingActorDisplayName());
        assertNotNull(doc.crewAssignment());
        assertEquals("crew-prueba-sur", doc.crewAssignment().crewId());
        assertEquals("Cuadrilla Técnica Ficticia Sur", doc.crewAssignment().crewName());
        assertNull(doc.timeline().get(0).actorDisplayName());
        assertEquals("Carlos Paredes Ficticio", doc.timeline().get(1).actorDisplayName());
        assertEquals("Conformidad no gestionada en esta versión", doc.conformity().organizerConformityStatus());
        assertFalse(doc.conformity().commercialConformityClaimed());
    }
}
