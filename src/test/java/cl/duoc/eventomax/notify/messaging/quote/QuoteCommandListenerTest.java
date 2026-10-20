package cl.duoc.eventomax.notify.messaging.quote;

import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.service.QuoteNotificationService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteCommandListenerTest {

    private static final long DELIVERY_TAG = 1L;

    @Mock
    private QuoteNotificationService quoteNotificationService;

    @Mock
    private ProcessedEventStore processedEventStore;

    @Mock
    private Channel channel;

    private ObjectMapper objectMapper;
    private RetryTemplate retryTemplate;
    private QuoteCommandListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        retryTemplate = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3); // 3 intentos totales (1 inicial + 2 reintentos adicionales)
        retryTemplate.setRetryPolicy(retryPolicy);

        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(0L);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        listener = new QuoteCommandListener(objectMapper, quoteNotificationService, processedEventStore, retryTemplate);
    }

    private MessageEnvelope<QuoteCommandPayload> createValidEnvelope() {
        var payload = new QuoteCommandPayload(
                300L,
                "org-ep2",
                "Festival Cierre EP2",
                "CERRADO",
                "2026-12-15T20:30:00",
                "Movistar Arena",
                "2026-12-16T01:00:00Z"
        );
        return new MessageEnvelope<>(
                "GenerateClosingQuote",
                "evt-quote-001",
                "2026-12-16T01:00:01Z",
                "trace-quote-001",
                "corr-quote-001",
                payload
        );
    }

    private Message createMessage(Object envelope) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        return new Message(body, new MessageProperties());
    }

    @Test
    @DisplayName("A. Mensaje quote válido: genera PDF, marca idempotencia y ejecuta ACK")
    void validMessage_shouldProcessAndAck() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-quote-001"), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable processor = invocation.getArgument(1);
            processor.run();
            return true;
        });

        listener.handleQuoteCommand(message, channel, DELIVERY_TAG);

        verify(quoteNotificationService, times(1)).processQuoteGeneration(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("B. Duplicado por eventId: ignora reprocesamiento y ejecuta ACK")
    void duplicateMessage_shouldAckWithoutProcessing() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-quote-001"), any(Runnable.class))).thenReturn(false);

        listener.handleQuoteCommand(message, channel, DELIVERY_TAG);

        verify(quoteNotificationService, never()).processQuoteGeneration(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("C. Type inválido: NACK sin requeue (envío directo a DLQ)")
    void invalidType_shouldNackWithoutRequeue() throws Exception {
        var payload = new QuoteCommandPayload(
                300L, "org-ep2", "Festival", "CERRADO", "2026-12-15T20:30", "Santiago", null
        );
        var envelope = new MessageEnvelope<>(
                "UnknownQuoteCommand", "evt-quote-002", "2026-12-16T01:00:00Z",
                "trace-002", "corr-002", payload
        );
        Message message = createMessage(envelope);

        listener.handleQuoteCommand(message, channel, DELIVERY_TAG);

        verify(quoteNotificationService, never()).processQuoteGeneration(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("D. Payload inválido (organizerId o location vacío): NACK sin requeue")
    void invalidPayload_missingFields_shouldNackWithoutRequeue() throws Exception {
        var payload = new QuoteCommandPayload(
                300L, "", "Festival", "CERRADO", "2026-12-15T20:30", "Santiago", null
        );
        var envelope = new MessageEnvelope<>(
                "GenerateClosingQuote", "evt-quote-003", "2026-12-16T01:00:00Z",
                "trace-003", "corr-003", payload
        );
        Message message = createMessage(envelope);

        listener.handleQuoteCommand(message, channel, DELIVERY_TAG);

        verify(quoteNotificationService, never()).processQuoteGeneration(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("E1. Error permanente: agota 3 intentos totales (1 inicial + 2 reintentos) y hace NACK sin requeue")
    void serviceError_fails3AttemptsTotal_shouldNackWithoutRequeue() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-quote-001"), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable processor = invocation.getArgument(1);
            processor.run();
            return true;
        });

        doThrow(new RuntimeException("Fallo disco 1"))
                .doThrow(new RuntimeException("Fallo disco 2"))
                .doThrow(new RuntimeException("Fallo disco 3"))
                .when(quoteNotificationService).processQuoteGeneration(any());

        listener.handleQuoteCommand(message, channel, DELIVERY_TAG);

        verify(quoteNotificationService, times(3)).processQuoteGeneration(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("E2. Error transitorio: falla 2 veces y se recupera en el 3.er intento total con ACK")
    void serviceError_fails2TimesThenSucceedsOnThirdAttempt_shouldAck() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-quote-001"), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable processor = invocation.getArgument(1);
            processor.run();
            return true;
        });

        doThrow(new RuntimeException("Fallo transitorio 1"))
                .doThrow(new RuntimeException("Fallo transitorio 2"))
                .doReturn(Path.of("data/quotes/test.pdf"))
                .when(quoteNotificationService).processQuoteGeneration(any());

        listener.handleQuoteCommand(message, channel, DELIVERY_TAG);

        verify(quoteNotificationService, times(3)).processQuoteGeneration(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
}

