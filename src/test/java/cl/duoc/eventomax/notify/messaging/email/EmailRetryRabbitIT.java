package cl.duoc.eventomax.notify.messaging.email;

import cl.duoc.eventomax.notify.config.RabbitMQConfig;
import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.sender.EmailSender;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Prueba de integraciÃ³n manual contra RabbitMQ real.
 * <p>
 * Demuestra:
 * - Mensaje vÃ¡lido recibido desde el broker.
 * - RetryTemplate ejecuta exactamente 3 intentos tras fallos simulados.
 * - Tras agotar intentos, se propaga excepciÃ³n y Listener envÃ­a NACK.
 * - El NACK con requeue=false deriva a DLQ gracias a la topologÃ­a.
 * - No se marca la idempotencia (permanece false).
 * <p>
 * Requisitos:
 * - Requiere broker RabbitMQ externo en ejecuciÃ³n.
 * - Requiere la variable de entorno RUN_RABBIT_IT=true.
 * - No forma parte del build normal.
 * - Utiliza la topologÃ­a real de EventoMax.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eventomax.notify.retry.max-attempts=3",
                "eventomax.notify.retry.initial-interval-ms=50"
        }
)
@EnabledIfEnvironmentVariable(named = "RUN_RABBIT_IT", matches = "true")
@org.springframework.context.annotation.Import(EmailRetryRabbitIT.TestConfig.class)
class EmailRetryRabbitIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventStore processedEventStore;

    @Autowired
    private FailingEmailSender failingEmailSender;

    @Test
    void testMessageFailsAndGoesToDlq() throws Exception {
        // 1. Obtener baseline de la DLQ
        Properties dlqProps = amqpAdmin.getQueueProperties(RabbitMQConfig.Q_CMD_EMAIL_DLQ);
        int dlqBaseline = getMessageCount(dlqProps);

        // 2. Generar payload vÃ¡lido y eventId Ãºnico
        String eventId = UUID.randomUUID().toString();
        var payload = new EmailProductionStatusPayload(
                123L, "org-1", "Integration Test Concert", "CONFIRMED",
                "2026-10-01T20:00:00", "Remote"
        );
        var envelope = new MessageEnvelope<>(
                "SendProductionStatusEmail",
                eventId,
                "2026-10-03T00:00:00Z",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                payload
        );

        // 3. Serializar y crear Message
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        MessageProperties msgProps = new MessageProperties();
        msgProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(body, msgProps);

        // 4. Publicar
        rabbitTemplate.send(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, message);

        // 5. Esperar acotadamente (polling manual) max 5 seg
        int maxChecks = 50;
        int check = 0;
        int currentDlq = dlqBaseline;

        while (check < maxChecks) {
            Properties props = amqpAdmin.getQueueProperties(RabbitMQConfig.Q_CMD_EMAIL_DLQ);
            currentDlq = getMessageCount(props);

            if (failingEmailSender.getAttempts() == 3 && currentDlq >= dlqBaseline + 1) {
                break;
            }

            Thread.sleep(100);
            check++;
        }

        // 6. Assertions finales
        assertEquals(3, failingEmailSender.getAttempts(), "El servicio debiÃ³ fallar exactamente 3 veces");
        assertEquals(dlqBaseline + 1, currentDlq, "El mensaje debiÃ³ llegar a la DLQ");
        assertFalse(processedEventStore.isProcessed(eventId), "El eventId NO debe quedar procesado");

        Properties emailQueueProps = amqpAdmin.getQueueProperties(RabbitMQConfig.Q_CMD_EMAIL);
        assertEquals(0, getMessageCount(emailQueueProps), "q.cmd.email debe quedar sin mensajes despuÃ©s del NACK sin requeue");
    }

    private int getMessageCount(Properties props) {
        if (props == null) {
            return 0;
        }

        Object count = props.get(
                org.springframework.amqp.rabbit.core.RabbitAdmin.QUEUE_MESSAGE_COUNT
        );

        if (count instanceof Number number) {
            return number.intValue();
        }

        return 0;
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public FailingEmailSender failingEmailSender() {
            return new FailingEmailSender();
        }
    }

    static class FailingEmailSender implements EmailSender {

        private final AtomicInteger attempts = new AtomicInteger(0);

        @Override
        public void send(cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload payload) {
            attempts.incrementAndGet();
            throw new RuntimeException("Fallo controlado de integraciÃ³n (intento: " + attempts.get() + ")");
        }

        public int getAttempts() {
            return attempts.get();
        }
    }
}
