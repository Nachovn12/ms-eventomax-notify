package cl.duoc.eventomax.notify.messaging.crew;

import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.service.CrewTicketNotificationService;
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
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrewCommandListenerTest {

    private static final long DELIVERY_TAG = 1L;

    @Mock
    private CrewTicketNotificationService crewTicketNotificationService;

    @Mock
    private ProcessedEventStore processedEventStore;

    @Mock
    private Channel channel;

    private ObjectMapper objectMapper;
    private RetryTemplate retryTemplate;
    private CrewCommandListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        retryTemplate = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        retryTemplate.setRetryPolicy(retryPolicy);

        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(0L);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        listener = new CrewCommandListener(objectMapper, crewTicketNotificationService, processedEventStore, retryTemplate);
    }

    private MessageEnvelope<CrewTicketPayload> createValidEnvelope() {
        var payload = new CrewTicketPayload(
                100L, "Concierto EventoMax", "2026-12-01T20:00:00", "Santiago, Chile", "EN_MONTAJE"
        );
        return new MessageEnvelope<>(
                "GenerateCrewTicket",
                "evt-crew-001",
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

    @Test
    @DisplayName("A. Mensaje válido: procesa, marca idempotencia y ACK")
    void validMessage_shouldProcessAndAck() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-crew-001"), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable processor = invocation.getArgument(1);
            processor.run();
            return true;
        });

        listener.handleCrewCommand(message, channel, DELIVERY_TAG);

        verify(crewTicketNotificationService, times(1)).processCrewTicket(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("B. Duplicado: no reprocesa, ACK")
    void duplicateMessage_shouldAckWithoutProcessing() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-crew-001"), any(Runnable.class))).thenReturn(false);

        listener.handleCrewCommand(message, channel, DELIVERY_TAG);

        verify(crewTicketNotificationService, never()).processCrewTicket(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("C. Type inválido: NACK sin requeue")
    void invalidType_shouldNack() throws Exception {
        var payload = new CrewTicketPayload(
                100L, "Concierto", "2026-12-01T20:00:00", "Santiago", "EN_MONTAJE"
        );
        var envelope = new MessageEnvelope<>(
                "UnknownType", "evt-crew-002", "2026-10-03T00:00:00Z",
                "trace-002", "corr-002", payload
        );
        Message message = createMessage(envelope);

        listener.handleCrewCommand(message, channel, DELIVERY_TAG);

        verify(crewTicketNotificationService, never()).processCrewTicket(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("D1. Envelope inválido (eventId vacío): NACK")
    void invalidEnvelope_emptyEventId_shouldNack() throws Exception {
        var payload = new CrewTicketPayload(
                100L, "Concierto", "2026-12-01T20:00:00", "Santiago", "EN_MONTAJE"
        );
        var envelope = new MessageEnvelope<>(
                "GenerateCrewTicket", "", "2026-10-03T00:00:00Z",
                "trace-003", "corr-003", payload
        );
        Message message = createMessage(envelope);

        listener.handleCrewCommand(message, channel, DELIVERY_TAG);

        verify(crewTicketNotificationService, never()).processCrewTicket(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("D2. Envelope inválido (payload null): NACK")
    void invalidEnvelope_nullPayload_shouldNack() throws Exception {
        var envelope = new MessageEnvelope<CrewTicketPayload>(
                "GenerateCrewTicket", "evt-crew-004", "2026-10-03T00:00:00Z",
                "trace-004", "corr-004", null
        );
        Message message = createMessage(envelope);

        listener.handleCrewCommand(message, channel, DELIVERY_TAG);

        verify(crewTicketNotificationService, never()).processCrewTicket(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("E1. Falla en 3 intentos: NACK sin marcar procesado, exception propagada")
    void serviceError_fails3Times_shouldNackWithoutMarkingProcessed() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-crew-001"), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable processor = invocation.getArgument(1);
            processor.run();
            return true;
        });

        doThrow(new RuntimeException("Error simulado 1"))
                .doThrow(new RuntimeException("Error simulado 2"))
                .doThrow(new RuntimeException("Error simulado 3"))
                .when(crewTicketNotificationService).processCrewTicket(any());

        listener.handleCrewCommand(message, channel, DELIVERY_TAG);

        verify(crewTicketNotificationService, times(3)).processCrewTicket(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("E2. Falla 2 veces y éxito en el tercer intento: ACK")
    void serviceError_fails2TimesThenSucceeds_shouldAck() throws Exception {
        var envelope = createValidEnvelope();
        Message message = createMessage(envelope);

        when(processedEventStore.processOnce(eq("evt-crew-001"), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable processor = invocation.getArgument(1);
            processor.run();
            return true;
        });

        doThrow(new RuntimeException("Error simulado 1"))
                .doThrow(new RuntimeException("Error simulado 2"))
                .doNothing()
                .when(crewTicketNotificationService).processCrewTicket(any());

        listener.handleCrewCommand(message, channel, DELIVERY_TAG);

        verify(crewTicketNotificationService, times(3)).processCrewTicket(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
}
