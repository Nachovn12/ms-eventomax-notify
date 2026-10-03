package cl.duoc.eventomax.notify.messaging.email;

import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.service.EmailNotificationService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailCommandListenerTest {

    private static final long DELIVERY_TAG = 1L;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private ProcessedEventStore processedEventStore;

    @Mock
    private Channel channel;

    private ObjectMapper objectMapper;
    private EmailCommandListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new EmailCommandListener(objectMapper, emailNotificationService, processedEventStore);
    }

    private MessageEnvelope<EmailProductionStatusPayload> createValidEnvelope() {
        var payload = new EmailProductionStatusPayload(
                100L, "org-001", "Concierto Rock", "CONFIRMED",
                "2026-12-01T20:00:00", "Santiago, Chile"
        );
        return new MessageEnvelope<>(
                "SendProductionStatusEmail",
                "evt-001",
                "2026-10-03T00:00:00Z",
                "trace-001",
                "corr-001",
                payload
        );
    }

    private Message createMessage(Object envelope) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        return new Message(body, new MessageProperties());
    }

    // --- A. MENSAJE VÁLIDO ---

    @Test
    @DisplayName("A. Mensaje válido: procesa, marca idempotencia y ACK")
    void validMessage_shouldProcessAndAck() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-001"), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable processor = invocation.getArgument(1);
            processor.run();
            return true;
        });

        listener.handleEmailCommand(message, channel, DELIVERY_TAG);

        verify(emailNotificationService, times(1)).processEmailNotification(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    // --- B. DUPLICADO ---

    @Test
    @DisplayName("B. Duplicado: no reprocesa, ACK")
    void duplicateMessage_shouldAckWithoutProcessing() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-001"), any(Runnable.class))).thenReturn(false);

        listener.handleEmailCommand(message, channel, DELIVERY_TAG);

        verify(emailNotificationService, never()).processEmailNotification(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    // --- C. TYPE INVÁLIDO ---

    @Test
    @DisplayName("C. Type inválido: NACK sin requeue")
    void invalidType_shouldNack() throws Exception {
        var payload = new EmailProductionStatusPayload(
                100L, "org-001", "Concierto", "CONFIRMED",
                "2026-12-01T20:00:00", "Santiago"
        );
        var envelope = new MessageEnvelope<>(
                "UnknownType", "evt-002", "2026-10-03T00:00:00Z",
                "trace-002", "corr-002", payload
        );
        Message message = createMessage(envelope);

        listener.handleEmailCommand(message, channel, DELIVERY_TAG);

        verify(emailNotificationService, never()).processEmailNotification(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    // --- D. ENVELOPE INVÁLIDO ---

    @Test
    @DisplayName("D1. Envelope inválido (eventId vacío): NACK")
    void invalidEnvelope_emptyEventId_shouldNack() throws Exception {
        var payload = new EmailProductionStatusPayload(
                100L, "org-001", "Concierto", "CONFIRMED",
                "2026-12-01T20:00:00", "Santiago"
        );
        var envelope = new MessageEnvelope<>(
                "SendProductionStatusEmail", "", "2026-10-03T00:00:00Z",
                "trace-003", "corr-003", payload
        );
        Message message = createMessage(envelope);

        listener.handleEmailCommand(message, channel, DELIVERY_TAG);

        verify(emailNotificationService, never()).processEmailNotification(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("D2. Envelope inválido (payload null): NACK")
    void invalidEnvelope_nullPayload_shouldNack() throws Exception {
        var envelope = new MessageEnvelope<EmailProductionStatusPayload>(
                "SendProductionStatusEmail", "evt-004", "2026-10-03T00:00:00Z",
                "trace-004", "corr-004", null
        );
        Message message = createMessage(envelope);

        listener.handleEmailCommand(message, channel, DELIVERY_TAG);

        verify(emailNotificationService, never()).processEmailNotification(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    // --- E. ERROR DEL SERVICIO ---

    @Test
    @DisplayName("E. Error del servicio: NACK sin marcar procesado")
    void serviceError_shouldNackWithoutMarkingProcessed() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-001"), any(Runnable.class))).thenThrow(new RuntimeException("Error simulado"));

        listener.handleEmailCommand(message, channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
