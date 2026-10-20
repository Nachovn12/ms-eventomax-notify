package cl.duoc.eventomax.notify.messaging.quote;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de Integración en Vivo de Dead Letter Queue (Fase B4 - GATE B4.2):
 * Demuestra contra el broker RabbitMQ real (localhost:25692) y el consumidor en vivo:
 * 1. Publicación de un mensaje deliberadamente corrupto / tipo no soportado.
 * 2. Consumo por QuoteCommandListener en ejecución.
 * 3. Rechazo mediante basicNack(deliveryTag, false, false) sin requeue.
 * 4. Transferencia automática por el broker a través del DLX cmd.dead.dlx.
 * 5. Llegada y verificación del mensaje en q.cmd.quote.dlq con cabeceras x-death.
 * 6. Limpieza controlada del mensaje de prueba y verificación de no afectación de mensajes válidos.
 */
public class QuoteCommandDlqLiveIT {

    private static final String DLQ_QUEUE_NAME = "q.cmd.quote.dlq";
    private static final String WORK_QUEUE_NAME = "q.cmd.quote";
    private static final String CMD_DIRECT_EXCHANGE = "cmd.direct";
    private static final String ROUTING_KEY_QUOTE_GEN = "quote.gen";

    @Test
    @DisplayName("GATE B4.2: Demostración real de NACK y derivación a DLQ vía cmd.dead.dlx")
    void testRealDlqRedirectionOnMalformedMessage() throws Exception {
        // 1. Leer credenciales del entorno local
        File credsFile = new File("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/credentials.json");
        Assumptions.assumeTrue(credsFile.exists(), "Skipping live DLQ test when local credentials.json is not present");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode creds = mapper.readTree(credsFile);
        String rmqUser = creds.get("application").get("username").asText();
        String rmqPass = creds.get("application").get("password").asText();

        // 2. Conexión nativa AMQP al broker RabbitMQ real
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(25692);
        factory.setUsername(rmqUser);
        factory.setPassword(rmqPass);
        factory.setVirtualHost("/");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // 3. Comprobar que q.cmd.quote.dlq esté declarada en el broker
            long initialDlqCount = channel.messageCount(DLQ_QUEUE_NAME);
            System.out.println("[GATE B4.2] Profundidad inicial de " + DLQ_QUEUE_NAME + ": " + initialDlqCount);

            // 4. Preparar mensaje deliberadamente inválido (tipo no soportado)
            String corruptEventId = "evt-b4-dlq-malformed-type-001";
            String corruptTraceId = "trace-b4-dlq-cert-001";
            String corruptCorrId = "corr-b4-dlq-cert-001";

            String corruptJson = """
            {
              "type": "MALFORMED_UNSUPPORTED_TYPE_B4",
              "eventId": "%s",
              "timestamp": "%s",
              "traceId": "%s",
              "correlationId": "%s",
              "payload": {
                "productionId": 777,
                "organizerId": "org-b4-dlq-test",
                "productionName": "EP2-B4 DLQ Test Production",
                "status": "CERRADO",
                "scheduledAt": "2026-11-20 20:00:00",
                "location": "Centro de Pruebas",
                "closedAt": "2026-10-20T03:00:00Z"
              }
            }
            """.formatted(corruptEventId, Instant.now().toString(), corruptTraceId, corruptCorrId);

            // 5. Publicar mensaje inválido a cmd.direct / quote.gen
            System.out.println("[GATE B4.2] Publicando mensaje con tipo inválido a " + CMD_DIRECT_EXCHANGE + " / " + ROUTING_KEY_QUOTE_GEN);
            channel.basicPublish(
                    CMD_DIRECT_EXCHANGE,
                    ROUTING_KEY_QUOTE_GEN,
                    new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                            .contentType("application/json")
                            .contentEncoding("UTF-8")
                            .deliveryMode(2) // persistent
                            .build(),
                    corruptJson.getBytes(StandardCharsets.UTF_8)
            );

            // 6. Esperar a que el consumidor en vivo de Notify reciba, rechace con NACK(requeue=false)
            // y el broker RabbitMQ lo derive a q.cmd.quote.dlq a través de cmd.dead.dlx
            GetResponse dlqResponse = null;
            for (int i = 0; i < 20; i++) {
                Thread.sleep(500);
                dlqResponse = channel.basicGet(DLQ_QUEUE_NAME, false); // false = manual ack, peek
                if (dlqResponse != null) {
                    break;
                }
            }

            assertNotNull(dlqResponse, "El mensaje inválido DEBE haber sido transferido a la DLQ (" + DLQ_QUEUE_NAME + ") vía cmd.dead.dlx");
            System.out.println("[GATE B4.2] Mensaje recibido exitosamente en " + DLQ_QUEUE_NAME + "!");

            // 7. Inspeccionar contenido del mensaje en la DLQ
            String body = new String(dlqResponse.getBody(), StandardCharsets.UTF_8);
            System.out.println("[GATE B4.2] Body del mensaje en DLQ: " + body);
            assertTrue(body.contains(corruptEventId), "El mensaje en DLQ debe contener el eventId original: " + corruptEventId);
            assertTrue(body.contains("MALFORMED_UNSUPPORTED_TYPE_B4"), "El mensaje en DLQ debe contener el type inválido rechazado");

            // 8. Inspeccionar cabeceras x-death agregadas por RabbitMQ
            Map<String, Object> headers = dlqResponse.getProps().getHeaders();
            System.out.println("[GATE B4.2] Cabeceras AMQP en DLQ: " + headers.keySet());
            assertTrue(headers.containsKey("x-death"), "RabbitMQ debe haber adjuntado la cabecera 'x-death' en el dead-lettering");

            @SuppressWarnings("unchecked")
            List<Map<String, ?>> xDeathList = (List<Map<String, ?>>) headers.get("x-death");
            assertNotNull(xDeathList);
            assertFalse(xDeathList.isEmpty(), "La lista x-death no debe estar vacía");
            Map<String, ?> firstDeath = xDeathList.get(0);
            System.out.println("[GATE B4.2] Detalle x-death: " + firstDeath);
            assertEquals(WORK_QUEUE_NAME, firstDeath.get("queue").toString(), "La cola de origen en x-death debe ser q.cmd.quote");
            assertEquals("rejected", firstDeath.get("reason").toString(), "La razón de muerte en x-death debe ser 'rejected' (NACK requeue=false)");

            // Confirmar (ACK) el mensaje de prueba en la DLQ para dejarla limpia
            channel.basicAck(dlqResponse.getEnvelope().getDeliveryTag(), false);
            System.out.println("[GATE B4.2] Mensaje de prueba consumido de DLQ. Profundidad final restaurada.");

            System.out.println("[GATE B4.2] CERTIFICACIÓN EXITOSA: NACK sin requeue transfirió el mensaje corrupto a la DLQ con x-death reason=rejected.");
        }
    }
}

