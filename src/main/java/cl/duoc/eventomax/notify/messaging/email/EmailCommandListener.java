package cl.duoc.eventomax.notify.messaging.email;

import cl.duoc.eventomax.notify.config.RabbitMQConfig;
import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.service.EmailNotificationService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.RetryCallback;
import org.springframework.stereotype.Component;

/**
 * Consumidor de la cola {@code q.cmd.email}.
 * <p>
 * Procesa comandos de tipo {@code SendProductionStatusEmail} con ACK/NACK manual.
 * Los mensajes inválidos o con type incorrecto se envían a la DLQ (NACK sin requeue).
 */
@Component
public class EmailCommandListener {

    private static final Logger log = LoggerFactory.getLogger(EmailCommandListener.class);
    private static final String EXPECTED_TYPE = "SendProductionStatusEmail";

    private final ObjectMapper objectMapper;
    private final EmailNotificationService emailNotificationService;
    private final ProcessedEventStore processedEventStore;
    private final RetryTemplate retryTemplate;

    public EmailCommandListener(ObjectMapper objectMapper,
                                EmailNotificationService emailNotificationService,
                                ProcessedEventStore processedEventStore,
                                RetryTemplate retryTemplate) {
        this.objectMapper = objectMapper;
        this.emailNotificationService = emailNotificationService;
        this.processedEventStore = processedEventStore;
        this.retryTemplate = retryTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_CMD_EMAIL, ackMode = "MANUAL")
    public void handleEmailCommand(org.springframework.amqp.core.Message message,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            // 1. Deserializar el body JSON de forma explícita
            MessageEnvelope<EmailProductionStatusPayload> envelope = objectMapper.readValue(
                    message.getBody(),
                    new TypeReference<MessageEnvelope<EmailProductionStatusPayload>>() {}
            );

            // 2. Validar campos obligatorios del envelope
            if (!isValidEnvelope(envelope)) {
                log.warn("Envelope inválido recibido, enviando a DLQ");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            // 3. Configurar MDC para trazabilidad
            MDC.put("traceId", envelope.traceId());
            MDC.put("correlationId", envelope.correlationId());

            try {
                // 4. Validar type esperado
                if (!EXPECTED_TYPE.equals(envelope.type())) {
                    log.warn("Type no soportado: '{}', esperado: '{}'. Enviando a DLQ",
                            envelope.type(), EXPECTED_TYPE);
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }

                // 5. & 6. & 7. Procesar con idempotencia atómica y reintentos controlados
                boolean processed = processedEventStore.processOnce(
                        envelope.eventId(),
                        () -> retryTemplate.execute((RetryCallback<Void, RuntimeException>) context -> {
                            if (context.getRetryCount() > 0) {
                                log.warn("Reintento {} de procesamiento: eventId={}",
                                        context.getRetryCount(), envelope.eventId());
                            }
                            emailNotificationService.processEmailNotification(envelope);
                            return null;
                        })
                );

                if (!processed) {
                    log.info("Evento duplicado ignorado: eventId={}", envelope.eventId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // 8. ACK
                channel.basicAck(deliveryTag, false);
                log.info("Mensaje procesado exitosamente: eventId={}", envelope.eventId());

            } finally {
                MDC.remove("traceId");
                MDC.remove("correlationId");
            }

        } catch (Exception e) {
            // Error de deserialización u otro error no recuperable
            log.error("Error procesando mensaje de q.cmd.email, enviando a DLQ", e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackEx) {
                log.error("Error al ejecutar NACK", nackEx);
            }
        }
    }

    private boolean isValidEnvelope(MessageEnvelope<EmailProductionStatusPayload> envelope) {
        return envelope != null
                && isNotBlank(envelope.type())
                && isNotBlank(envelope.eventId())
                && isNotBlank(envelope.timestamp())
                && isNotBlank(envelope.traceId())
                && isNotBlank(envelope.correlationId())
                && envelope.payload() != null;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
