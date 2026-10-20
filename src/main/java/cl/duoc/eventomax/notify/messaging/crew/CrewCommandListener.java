package cl.duoc.eventomax.notify.messaging.crew;

import cl.duoc.eventomax.notify.config.RabbitMQConfig;
import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.service.CrewTicketNotificationService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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

@Component
public class CrewCommandListener {

    private static final Logger log = LoggerFactory.getLogger(CrewCommandListener.class);
    private static final String EXPECTED_TYPE = "GenerateCrewTicket";

    private final ObjectMapper objectMapper;
    private final CrewTicketNotificationService crewTicketNotificationService;
    private final ProcessedEventStore processedEventStore;
    private final RetryTemplate retryTemplate;

    public CrewCommandListener(ObjectMapper objectMapper,
                               CrewTicketNotificationService crewTicketNotificationService,
                               ProcessedEventStore processedEventStore,
                               RetryTemplate retryTemplate) {
        this.objectMapper = objectMapper;
        this.crewTicketNotificationService = crewTicketNotificationService;
        this.processedEventStore = processedEventStore;
        this.retryTemplate = retryTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_CMD_CREW, ackMode = "MANUAL")
    public void handleCrewCommand(org.springframework.amqp.core.Message message,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            MessageEnvelope<CrewTicketPayload> envelope = objectMapper.readValue(
                    message.getBody(),
                    new TypeReference<MessageEnvelope<CrewTicketPayload>>() {}
            );

            if (!isValidEnvelope(envelope)) {
                log.warn("Envelope inválido recibido, enviando a DLQ");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            MDC.put("traceId", envelope.traceId());
            MDC.put("correlationId", envelope.correlationId());

            try {
                if (!EXPECTED_TYPE.equals(envelope.type())) {
                    log.warn("Type no soportado: '{}', esperado: '{}'. Enviando a DLQ",
                            envelope.type(), EXPECTED_TYPE);
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }

                boolean processed = processedEventStore.processOnce(
                        envelope.eventId(),
                        () -> retryTemplate.execute((RetryCallback<Void, RuntimeException>) context -> {
                            if (context.getRetryCount() > 0) {
                                log.warn("Reintento {} de procesamiento: eventId={}",
                                        context.getRetryCount(), envelope.eventId());
                            }
                            crewTicketNotificationService.processCrewTicket(envelope);
                            return null;
                        })
                );

                if (!processed) {
                    log.info("Evento duplicado ignorado: eventId={}", envelope.eventId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                channel.basicAck(deliveryTag, false);
                log.info("Mensaje procesado exitosamente: eventId={}", envelope.eventId());

            } finally {
                MDC.remove("traceId");
                MDC.remove("correlationId");
            }

        } catch (Exception e) {
            log.error("Error procesando mensaje de q.cmd.crew, enviando a DLQ", e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackEx) {
                log.error("Error al ejecutar NACK", nackEx);
            }
        }
    }

    private boolean isValidEnvelope(MessageEnvelope<CrewTicketPayload> envelope) {
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
