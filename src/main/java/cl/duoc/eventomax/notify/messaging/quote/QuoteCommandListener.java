package cl.duoc.eventomax.notify.messaging.quote;

import cl.duoc.eventomax.notify.config.RabbitMQConfig;
import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.service.QuoteNotificationService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumidor de la cola {@code q.cmd.quote}.
 * <p>
 * Procesa comandos de tipo {@code GenerateClosingQuote} con ACK/NACK manual,
 * idempotencia por {@code eventId}, reintentos acotados (3 intentos totales =
 * 1 intento inicial + 2 reintentos adicionales) y derivación a {@code q.cmd.quote.dlq}
 * vía {@code cmd.dead.dlx}.
 */
@Component
public class QuoteCommandListener {

    private static final Logger log = LoggerFactory.getLogger(QuoteCommandListener.class);
    public static final String EXPECTED_TYPE = "GenerateClosingQuote";

    private final ObjectMapper objectMapper;
    private final QuoteNotificationService quoteNotificationService;
    private final ProcessedEventStore processedEventStore;
    private final RetryTemplate retryTemplate;

    public QuoteCommandListener(ObjectMapper objectMapper,
                                QuoteNotificationService quoteNotificationService,
                                ProcessedEventStore processedEventStore,
                                RetryTemplate retryTemplate) {
        this.objectMapper = objectMapper;
        this.quoteNotificationService = quoteNotificationService;
        this.processedEventStore = processedEventStore;
        this.retryTemplate = retryTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_CMD_QUOTE, ackMode = "MANUAL")
    public void handleQuoteCommand(org.springframework.amqp.core.Message message,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            MessageEnvelope<QuoteCommandPayload> envelope = objectMapper.readValue(
                    message.getBody(),
                    new TypeReference<MessageEnvelope<QuoteCommandPayload>>() {}
            );

            if (!isValidEnvelope(envelope)) {
                log.warn("Envelope o payload inválido recibido en q.cmd.quote, enviando a DLQ sin reintentos");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            MDC.put("traceId", envelope.traceId());
            MDC.put("correlationId", envelope.correlationId());

            try {
                if (!EXPECTED_TYPE.equals(envelope.type())) {
                    log.warn("Type no soportado en q.cmd.quote: '{}', esperado: '{}', traceId={}, correlationId={}. Enviando a DLQ",
                            envelope.type(), EXPECTED_TYPE, envelope.traceId(), envelope.correlationId());
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }

                if (!isValidPayload(envelope.payload())) {
                    log.warn("Payload incompleto en q.cmd.quote: eventId={}, traceId={}, correlationId={}. Enviando a DLQ",
                            envelope.eventId(), envelope.traceId(), envelope.correlationId());
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }

                boolean processed = processedEventStore.processOnce(
                        envelope.eventId(),
                        () -> retryTemplate.execute((RetryCallback<Void, RuntimeException>) context -> {
                            int retryCount = context.getRetryCount();
                            if (retryCount > 0) {
                                log.warn("Reintento adicional {} (intento total #{}) para q.cmd.quote: eventId={}, traceId={}, correlationId={}",
                                        retryCount, retryCount + 1, envelope.eventId(), envelope.traceId(), envelope.correlationId());
                            }
                            quoteNotificationService.processQuoteGeneration(envelope);
                            return null;
                        })
                );

                if (!processed) {
                    log.info("Evento quote duplicado ignorado: eventId={}, traceId={}, correlationId={}",
                            envelope.eventId(), envelope.traceId(), envelope.correlationId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                channel.basicAck(deliveryTag, false);
                log.info("Comando quote procesado exitosamente: eventId={}, traceId={}, correlationId={}",
                        envelope.eventId(), envelope.traceId(), envelope.correlationId());

            } finally {
                MDC.remove("traceId");
                MDC.remove("correlationId");
            }

        } catch (Exception e) {
            log.error("Error procesando mensaje de q.cmd.quote tras agotar intentos o deserializar, enviando a DLQ", e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackEx) {
                log.error("Error al ejecutar NACK en q.cmd.quote", nackEx);
            }
        }
    }

    private boolean isValidEnvelope(MessageEnvelope<QuoteCommandPayload> envelope) {
        return envelope != null
                && isNotBlank(envelope.type())
                && isNotBlank(envelope.eventId())
                && isNotBlank(envelope.timestamp())
                && isNotBlank(envelope.traceId())
                && isNotBlank(envelope.correlationId())
                && envelope.payload() != null;
    }

    private boolean isValidPayload(QuoteCommandPayload payload) {
        return payload != null
                && payload.productionId() != null
                && payload.productionId() > 0
                && isNotBlank(payload.organizerId())
                && isNotBlank(payload.productionName())
                && isNotBlank(payload.status())
                && isNotBlank(payload.scheduledAt())
                && isNotBlank(payload.location());
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}

