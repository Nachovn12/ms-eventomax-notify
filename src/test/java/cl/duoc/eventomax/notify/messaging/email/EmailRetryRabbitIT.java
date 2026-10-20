package cl.duoc.eventomax.notify.messaging.email;

import cl.duoc.eventomax.notify.config.RabbitMQConfig;
import cl.duoc.eventomax.notify.idempotency.ProcessedEventStore;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.sender.CrewTicketSender;
import cl.duoc.eventomax.notify.sender.EmailSender;
import cl.duoc.eventomax.notify.sender.PdfQuoteGenerator;
import cl.duoc.eventomax.notify.sender.QuoteGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suite de integración contra un broker RabbitMQ real, efímero y aislado mediante Testcontainers.
 * <p>
 * Verifica de extremo a extremo la topología declarada en {@link RabbitMQConfig},
 * la política de reintentos de {@code NotificationRetryConfig}, la idempotencia en
 * {@link ProcessedEventStore} y los consumidores {@link EmailCommandListener},
 * {@code CrewCommandListener} y {@code QuoteCommandListener} (incluyendo generación real de PDF 1.4).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:notify_rabbit_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                "eventomax.notify.retry.max-attempts=3",
                "eventomax.notify.quote.provider=legacy",
                "eventomax.notify.retry.initial-interval-ms=40",
                "eventomax.notify.quote.output-dir=./target/test-quotes"
        }
)
@Testcontainers
@Import(EmailRetryRabbitIT.TestConfig.class)
class EmailRetryRabbitIT {

    private static final Logger log = LoggerFactory.getLogger(EmailRetryRabbitIT.class);

    private static final int RABBITMQ_AMQP_PORT = 5672;
    private static final String PERSISTENT_DEMO_CONTAINER_NAME = "eventomax-test-rabbitmq";
    private static final String PERSISTENT_EP2_CONTAINER_NAME = "eventomax-rabbitmq-ep2";
    private static final String PERSISTENT_DEMO_CONTAINER_ID_PREFIX = "1292e1150a05";
    private static final String PERSISTENT_EP2_CONTAINER_ID_PREFIX = "4fd477bc189b";
    private static final AtomicBoolean CONNECTION_LOGGED = new AtomicBoolean(false);
    private static final Path TEST_QUOTE_OUTPUT_DIR = Path.of("target", "test-quotes");

    @Container
    static final GenericContainer<?> RABBITMQ_CONTAINER = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:4.3.6-management")
    ).withExposedPorts(RABBITMQ_AMQP_PORT);

    @DynamicPropertySource
    static void registerRabbitProperties(DynamicPropertyRegistry registry) {
        int mappedPort = RABBITMQ_CONTAINER.getMappedPort(RABBITMQ_AMQP_PORT);
        if (mappedPort == RABBITMQ_AMQP_PORT) {
            throw new IllegalStateException(
                    "Bloqueo de aislamiento: el puerto mapeado por Testcontainers no puede ser 5672."
            );
        }
        String host = RABBITMQ_CONTAINER.getHost();
        registry.add("spring.rabbitmq.addresses", () -> host + ":" + mappedPort);
        registry.add("spring.rabbitmq.host", () -> host);
        registry.add("spring.rabbitmq.port", () -> mappedPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventStore processedEventStore;

    @Autowired
    private ProgrammableEmailSender emailSender;

    @Autowired
    private ProgrammableCrewTicketSender crewTicketSender;

    @Autowired
    private ProgrammableQuoteGenerator quoteGenerator;

    @BeforeEach
    void setUp() {
        assertIsolatedEphemeralBrokerConnection();
        emailSender.reset();
        crewTicketSender.reset();
        quoteGenerator.reset();
        purgeAllTestQueues();
    }

    @Test
    @DisplayName("1. Email por cmd.direct (email.send): consume, ejecuta sender 1 vez, marca idempotencia y hace ACK")
    void emailDirect_validMessage_shouldConsumeProcessAndAck() throws Exception {
        String eventId = UUID.randomUUID().toString();
        var envelope = createEmailEnvelope("SendProductionStatusEmail", eventId, 101L, "CONFIRMADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, envelope);

        awaitCondition(
                "Consumo y ACK de email por cmd.direct",
                () -> processedEventStore.isProcessed(eventId)
                        && emailSender.getAttempts() == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
        );

        assertEquals(1, emailSender.getAttempts());
        assertEquals(1, emailSender.getSentPayloads().size());
        assertEquals(101L, emailSender.getSentPayloads().getFirst().productionId());
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ));
    }

    @Test
    @DisplayName("2. Email por cmd.topic (email.status): enruta a q.cmd.email, consume y hace ACK")
    void emailTopic_validMessage_shouldRouteConsumeAndAck() throws Exception {
        String eventId = UUID.randomUUID().toString();
        var envelope = createEmailEnvelope("SendProductionStatusEmail", eventId, 102L, "EN_OPERACION");

        publishEnvelope(RabbitMQConfig.CMD_TOPIC_EXCHANGE, "email.status", envelope);

        awaitCondition(
                "Consumo y ACK de email por cmd.topic",
                () -> processedEventStore.isProcessed(eventId)
                        && emailSender.getAttempts() == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
        );

        assertEquals(1, emailSender.getAttempts());
        assertEquals(102L, emailSender.getSentPayloads().getFirst().productionId());
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ));
    }

    @Test
    @DisplayName("3. Email con error permanente: agota 3 intentos, no marca idempotencia, hace NACK y deriva a q.cmd.email.dlq")
    void testMessageFailsAndGoesToDlq() throws Exception {
        emailSender.failAlways();

        String eventId = UUID.randomUUID().toString();
        var envelope = createEmailEnvelope("SendProductionStatusEmail", eventId, 103L, "CONFIRMADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, envelope);

        awaitCondition(
                "Tres intentos fallidos de email y enrutamiento a q.cmd.email.dlq",
                () -> emailSender.getAttempts() == 3
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ) == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
        );

        assertEquals(3, emailSender.getAttempts(), "El servicio debió ejecutar exactamente 3 intentos");
        assertEquals(0, emailSender.getSentPayloads().size(), "No debe registrar envíos exitosos");
        assertFalse(processedEventStore.isProcessed(eventId), "El eventId NO debe quedar marcado como procesado");
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL));
        assertEquals(1, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ));

        Message dlqMessage = rabbitTemplate.receive(RabbitMQConfig.Q_CMD_EMAIL_DLQ, 2000);
        assertNotNull(dlqMessage, "El mensaje debe poder leerse desde q.cmd.email.dlq");
        assertTrue(new String(dlqMessage.getBody(), StandardCharsets.UTF_8).contains(eventId));
    }

    @Test
    @DisplayName("4. Email con fallo transitorio (2 errores y éxito en 3.er intento): hace ACK, marca idempotencia y DLQ queda vacía")
    void emailTransientFailure_failsTwiceThenSucceeds_shouldAckWithoutDlq() throws Exception {
        emailSender.failTimesThenSucceed(2);

        String eventId = UUID.randomUUID().toString();
        var envelope = createEmailEnvelope("SendProductionStatusEmail", eventId, 104L, "FINALIZADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, envelope);

        awaitCondition(
                "Recuperación en el tercer intento de email",
                () -> processedEventStore.isProcessed(eventId)
                        && emailSender.getAttempts() == 3
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
        );

        assertEquals(3, emailSender.getAttempts(), "Debe haber ejecutado 2 fallos + 1 intento exitoso");
        assertEquals(1, emailSender.getSentPayloads().size());
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ));
    }

    @Test
    @DisplayName("5. Email duplicado (mismo eventId): procesa solo una vez, hace ACK del duplicado y no envía a DLQ")
    void emailDuplicateEvent_sameEventId_shouldProcessOnceAndAckDuplicate() throws Exception {
        String duplicateEventId = UUID.randomUUID().toString();
        var firstEnvelope = createEmailEnvelope("SendProductionStatusEmail", duplicateEventId, 105L, "CONFIRMADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, firstEnvelope);

        awaitCondition(
                "Primer procesamiento de email antes del duplicado",
                () -> processedEventStore.isProcessed(duplicateEventId)
                        && emailSender.getAttemptsForProduction(105L) == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
        );

        // Publicar duplicado con el mismo eventId y enseguida un mensaje centinela para confirmar drenado FIFO
        String sentinelEventId = UUID.randomUUID().toString();
        var duplicateEnvelope = createEmailEnvelope("SendProductionStatusEmail", duplicateEventId, 105L, "CONFIRMADO");
        var sentinelEnvelope = createEmailEnvelope("SendProductionStatusEmail", sentinelEventId, 106L, "CONFIRMADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, duplicateEnvelope);
        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, sentinelEnvelope);

        awaitCondition(
                "Drenado de duplicado y centinela en q.cmd.email",
                () -> processedEventStore.isProcessed(sentinelEventId)
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
        );

        assertEquals(1, emailSender.getAttemptsForProduction(105L), "El evento duplicado NO debe re-ejecutar el sender");
        assertEquals(1, emailSender.getAttemptsForProduction(106L), "El evento centinela debe procesarse normalmente");
        assertEquals(2, emailSender.getAttempts());
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ));
    }

    @Test
    @DisplayName("6. Email inválido (JSON malformado y type no soportado): NACK inmediato a q.cmd.email.dlq sin reintentos")
    void emailInvalidMessages_malformedJsonAndUnsupportedType_shouldNackDirectlyToDlq() throws Exception {
        publishRawJson(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, "{ json-corrupto-ep2");

        String unsupportedEventId = UUID.randomUUID().toString();
        var unsupportedEnvelope = createEmailEnvelope("UnsupportedEmailCommand", unsupportedEventId, 107L, "CONFIRMADO");
        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_EMAIL_SEND, unsupportedEnvelope);

        awaitCondition(
                "Enrutamiento inmediato de 2 mensajes inválidos a q.cmd.email.dlq",
                () -> getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ) == 2
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
        );

        assertEquals(0, emailSender.getAttempts(), "Mensajes inválidos no deben invocar el sender ni reintentar");
        assertFalse(processedEventStore.isProcessed(unsupportedEventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL));
        assertEquals(2, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ));
    }

    @Test
    @DisplayName("7. Cuadrilla por cmd.direct (crew.ticket): consume, ejecuta sender 1 vez, marca idempotencia y hace ACK")
    void crewDirect_validMessage_shouldConsumeProcessAndAck() throws Exception {
        String eventId = UUID.randomUUID().toString();
        var envelope = createCrewEnvelope("GenerateCrewTicket", eventId, 201L, "EN_MONTAJE");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, envelope);

        awaitCondition(
                "Consumo y ACK de cuadrilla por cmd.direct",
                () -> processedEventStore.isProcessed(eventId)
                        && crewTicketSender.getAttempts() == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
        );

        assertEquals(1, crewTicketSender.getAttempts());
        assertEquals(1, crewTicketSender.getSentPayloads().size());
        assertEquals(201L, crewTicketSender.getSentPayloads().getFirst().productionId());
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ));
    }

    @Test
    @DisplayName("8. Cuadrilla por cmd.topic (crew.stage.audio): enruta a q.cmd.crew, consume y hace ACK")
    void crewTopic_validMessage_shouldRouteConsumeAndAck() throws Exception {
        String eventId = UUID.randomUUID().toString();
        var envelope = createCrewEnvelope("GenerateCrewTicket", eventId, 202L, "CONFIRMADO");

        publishEnvelope(RabbitMQConfig.CMD_TOPIC_EXCHANGE, "crew.stage.audio", envelope);

        awaitCondition(
                "Consumo y ACK de cuadrilla por cmd.topic",
                () -> processedEventStore.isProcessed(eventId)
                        && crewTicketSender.getAttempts() == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
        );

        assertEquals(1, crewTicketSender.getAttempts());
        assertEquals(202L, crewTicketSender.getSentPayloads().getFirst().productionId());
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ));
    }

    @Test
    @DisplayName("9. Cuadrilla con error permanente: agota 3 intentos, no marca idempotencia, hace NACK y deriva a q.cmd.crew.dlq")
    void crewPermanentFailure_exhaustsThreeRetries_shouldNackAndRouteToDlq() throws Exception {
        crewTicketSender.failAlways();

        String eventId = UUID.randomUUID().toString();
        var envelope = createCrewEnvelope("GenerateCrewTicket", eventId, 203L, "EN_MONTAJE");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, envelope);

        awaitCondition(
                "Tres intentos fallidos de cuadrilla y enrutamiento a q.cmd.crew.dlq",
                () -> crewTicketSender.getAttempts() == 3
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ) == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
        );

        assertEquals(3, crewTicketSender.getAttempts());
        assertEquals(0, crewTicketSender.getSentPayloads().size());
        assertFalse(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW));
        assertEquals(1, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ));

        Message dlqMessage = rabbitTemplate.receive(RabbitMQConfig.Q_CMD_CREW_DLQ, 2000);
        assertNotNull(dlqMessage, "El mensaje debe poder leerse desde q.cmd.crew.dlq");
        assertTrue(new String(dlqMessage.getBody(), StandardCharsets.UTF_8).contains(eventId));
    }

    @Test
    @DisplayName("10. Cuadrilla con fallo transitorio (2 errores y éxito en 3.er intento): hace ACK, marca idempotencia y DLQ queda vacía")
    void crewTransientFailure_failsTwiceThenSucceeds_shouldAckWithoutDlq() throws Exception {
        crewTicketSender.failTimesThenSucceed(2);

        String eventId = UUID.randomUUID().toString();
        var envelope = createCrewEnvelope("GenerateCrewTicket", eventId, 204L, "EN_MONTAJE");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, envelope);

        awaitCondition(
                "Recuperación en el tercer intento de cuadrilla",
                () -> processedEventStore.isProcessed(eventId)
                        && crewTicketSender.getAttempts() == 3
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
        );

        assertEquals(3, crewTicketSender.getAttempts());
        assertEquals(1, crewTicketSender.getSentPayloads().size());
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ));
    }

    @Test
    @DisplayName("11. Cuadrilla duplicado (mismo eventId): procesa solo una vez, hace ACK del duplicado y no envía a DLQ")
    void crewDuplicateEvent_sameEventId_shouldProcessOnceAndAckDuplicate() throws Exception {
        String duplicateEventId = UUID.randomUUID().toString();
        var firstEnvelope = createCrewEnvelope("GenerateCrewTicket", duplicateEventId, 205L, "EN_MONTAJE");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, firstEnvelope);

        awaitCondition(
                "Primer procesamiento de cuadrilla antes del duplicado",
                () -> processedEventStore.isProcessed(duplicateEventId)
                        && crewTicketSender.getAttemptsForProduction(205L) == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
        );

        String sentinelEventId = UUID.randomUUID().toString();
        var duplicateEnvelope = createCrewEnvelope("GenerateCrewTicket", duplicateEventId, 205L, "EN_MONTAJE");
        var sentinelEnvelope = createCrewEnvelope("GenerateCrewTicket", sentinelEventId, 206L, "EN_MONTAJE");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, duplicateEnvelope);
        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, sentinelEnvelope);

        awaitCondition(
                "Drenado de duplicado y centinela en q.cmd.crew",
                () -> processedEventStore.isProcessed(sentinelEventId)
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
        );

        assertEquals(1, crewTicketSender.getAttemptsForProduction(205L), "El duplicado NO debe re-ejecutar el sender de cuadrilla");
        assertEquals(1, crewTicketSender.getAttemptsForProduction(206L));
        assertEquals(2, crewTicketSender.getAttempts());
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ));
    }

    @Test
    @DisplayName("12. Cuadrilla inválida (sobre incompleto y type no soportado): NACK inmediato a q.cmd.crew.dlq sin reintentos")
    void crewInvalidMessages_incompleteEnvelopeAndUnsupportedType_shouldNackDirectlyToDlq() throws Exception {
        var blankEventIdEnvelope = createCrewEnvelope("GenerateCrewTicket", "", 207L, "EN_MONTAJE");
        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, blankEventIdEnvelope);

        String unsupportedEventId = UUID.randomUUID().toString();
        var unsupportedTypeEnvelope = createCrewEnvelope("UnsupportedCrewCommand", unsupportedEventId, 208L, "EN_MONTAJE");
        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_CREW_TICKET, unsupportedTypeEnvelope);

        awaitCondition(
                "Enrutamiento inmediato de 2 mensajes inválidos a q.cmd.crew.dlq",
                () -> getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ) == 2
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
        );

        assertEquals(0, crewTicketSender.getAttempts(), "Mensajes inválidos de cuadrilla no deben invocar el sender");
        assertFalse(processedEventStore.isProcessed(unsupportedEventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW));
        assertEquals(2, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ));
    }

    /**
     * Escenario 13 — Compatibilidad de contrato con la salida real capturada de {@code ms-eventomax-productions}.
     * <p>
     * A diferencia de los escenarios 1 a 12 (que utilizan fixtures construidos con los records locales de Notify),
     * este escenario reproduce exactamente los bytes JSON, {@code MessageDeliveryMode.PERSISTENT} y el único
     * header de tipo AMQP ({@code __TypeId__ = cl.duoc.eventomax.productions.messaging.common.MessageEnvelope},
     * sin {@code __ContentTypeId__}) capturados del {@code RabbitMQCommandPublisher} + {@code Jackson2JsonMessageConverter}
     * reales de {@code ms-eventomax-productions} durante las transiciones {@code CONFIRMADO}, {@code EN_MONTAJE}
     * y {@code CERRADO} (incluyendo {@code GenerateClosingQuote} en {@code q.cmd.quote}).
     */
    @Test
    @DisplayName("13. Contrato real capturado de Productions (__TypeId__ externo, PERSISTENT, LocalDateTime): consume Email, Cuadrilla y Cotización PDF y hace ACK")
    void productionsPublisherCapturedContract_emailAndCrew_shouldConsumeProcessAndAckWithoutTypeIdConflict() throws Exception {
        String confirmedEmailEventId = UUID.randomUUID().toString();
        String setupEmailEventId = UUID.randomUUID().toString();
        String closedEmailEventId = UUID.randomUUID().toString();
        String setupCrewEventId = UUID.randomUUID().toString();
        String closedQuoteEventId = UUID.randomUUID().toString();

        String capturedConfirmedEmailJson = """
                {"type":"SendProductionStatusEmail","eventId":"%s","timestamp":"2026-10-16T22:39:53.473195Z","traceId":"trace-ep2-lifecycle-001","correlationId":"corr-ep2-lifecycle-001","payload":{"productionId":301,"organizerId":"org-ep2","productionName":"Festival Integración EP2","status":"CONFIRMADO","scheduledAt":"2026-12-15T20:30","location":"Movistar Arena"}}
                """.strip().formatted(confirmedEmailEventId);

        String capturedSetupEmailJson = """
                {"type":"SendProductionStatusEmail","eventId":"%s","timestamp":"2026-10-16T22:39:53.544568300Z","traceId":"trace-ep2-lifecycle-001","correlationId":"corr-ep2-lifecycle-001","payload":{"productionId":301,"organizerId":"org-ep2","productionName":"Festival Integración EP2","status":"EN_MONTAJE","scheduledAt":"2026-12-15T20:30","location":"Movistar Arena"}}
                """.strip().formatted(setupEmailEventId);

        String capturedClosedEmailJson = """
                {"type":"SendProductionStatusEmail","eventId":"%s","timestamp":"2026-10-16T22:39:53.632932400Z","traceId":"trace-ep2-lifecycle-001","correlationId":"corr-ep2-lifecycle-001","payload":{"productionId":301,"organizerId":"org-ep2","productionName":"Festival Integración EP2","status":"CERRADO","scheduledAt":"2026-12-15T20:30","location":"Movistar Arena"}}
                """.strip().formatted(closedEmailEventId);

        String capturedSetupCrewJson = """
                {"type":"GenerateCrewTicket","eventId":"%s","timestamp":"2026-10-16T22:39:53.542059200Z","traceId":"trace-ep2-lifecycle-001","correlationId":"corr-ep2-lifecycle-001","payload":{"productionId":301,"productionName":"Festival Integración EP2","scheduledAt":"2026-12-15T20:30","location":"Movistar Arena","status":"EN_MONTAJE"}}
                """.strip().formatted(setupCrewEventId);

        String capturedClosedQuoteJson = """
                {"type":"GenerateClosingQuote","eventId":"%s","timestamp":"2026-10-16T22:39:53.640112Z","traceId":"trace-ep2-lifecycle-001","correlationId":"corr-ep2-lifecycle-001","payload":{"productionId":301,"organizerId":"org-ep2","productionName":"Festival Integración EP2","status":"CERRADO","scheduledAt":"2026-12-15T20:30","location":"Movistar Arena","closedAt":"2026-10-16T22:39:53.635Z"}}
                """.strip().formatted(closedQuoteEventId);

        publishCapturedProductionsWireMessage(
                RabbitMQConfig.CMD_DIRECT_EXCHANGE,
                RabbitMQConfig.RK_EMAIL_SEND,
                capturedConfirmedEmailJson
        );
        publishCapturedProductionsWireMessage(
                RabbitMQConfig.CMD_DIRECT_EXCHANGE,
                RabbitMQConfig.RK_EMAIL_SEND,
                capturedSetupEmailJson
        );
        publishCapturedProductionsWireMessage(
                RabbitMQConfig.CMD_DIRECT_EXCHANGE,
                RabbitMQConfig.RK_CREW_TICKET,
                capturedSetupCrewJson
        );
        publishCapturedProductionsWireMessage(
                RabbitMQConfig.CMD_DIRECT_EXCHANGE,
                RabbitMQConfig.RK_EMAIL_SEND,
                capturedClosedEmailJson
        );
        publishCapturedProductionsWireMessage(
                RabbitMQConfig.CMD_DIRECT_EXCHANGE,
                RabbitMQConfig.RK_QUOTE_GEN,
                capturedClosedQuoteJson
        );

        awaitCondition(
                "Consumo completo de los 5 mensajes con formato real capturado de Productions",
                () -> processedEventStore.isProcessed(confirmedEmailEventId)
                        && processedEventStore.isProcessed(setupEmailEventId)
                        && processedEventStore.isProcessed(closedEmailEventId)
                        && processedEventStore.isProcessed(setupCrewEventId)
                        && processedEventStore.isProcessed(closedQuoteEventId)
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL) == 0
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW) == 0
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        assertEquals(3, emailSender.getAttemptsForProduction(301L));
        assertEquals(
                List.of("CONFIRMADO", "EN_MONTAJE", "CERRADO"),
                emailSender.getSentPayloads().stream().map(EmailProductionStatusPayload::status).toList()
        );
        assertEquals("2026-12-15T20:30", emailSender.getSentPayloads().getFirst().scheduledAt());

        assertEquals(1, crewTicketSender.getAttemptsForProduction(301L));
        assertEquals("EN_MONTAJE", crewTicketSender.getSentPayloads().getFirst().status());
        assertEquals("2026-12-15T20:30", crewTicketSender.getSentPayloads().getFirst().scheduledAt());

        assertEquals(1, quoteGenerator.getAttemptsForProduction(301L));
        assertEquals("CERRADO", quoteGenerator.getGeneratedPayloads().getFirst().status());
        assertEquals(1, quoteGenerator.getGeneratedPaths().size());
        assertTrue(Files.exists(quoteGenerator.getGeneratedPaths().getFirst()));

        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_EMAIL_DLQ));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_CREW_DLQ));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ));
    }

    @Test
    @DisplayName("14. Cotización PDF por cmd.direct (quote.gen): consume, genera PDF 1.4 real en disco, marca idempotencia y hace ACK")
    void quoteDirect_validMessage_shouldConsumeGenerateRealPdfAndAck() throws Exception {
        String eventId = UUID.randomUUID().toString();
        var envelope = createQuoteEnvelope("GenerateClosingQuote", eventId, 401L, "CERRADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, envelope);

        awaitCondition(
                "Consumo, generación de PDF 1.4 real y ACK de quote por cmd.direct",
                () -> processedEventStore.isProcessed(eventId)
                        && quoteGenerator.getAttempts() == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        assertEquals(1, quoteGenerator.getAttempts());
        assertEquals(1, quoteGenerator.getGeneratedPayloads().size());
        assertEquals(401L, quoteGenerator.getGeneratedPayloads().getFirst().productionId());
        assertEquals(1, quoteGenerator.getGeneratedPaths().size());

        Path pdfPath = quoteGenerator.getGeneratedPaths().getFirst();
        assertTrue(Files.exists(pdfPath), "El archivo PDF debe existir en disco");
        assertTrue(Files.size(pdfPath) > 500, "El archivo PDF debe tener tamaño real mayor a 500 bytes");
        String pdfContent = Files.readString(pdfPath, StandardCharsets.ISO_8859_1);
        assertTrue(pdfContent.startsWith("%PDF-1.4\n"), "Debe iniciar con cabecera %PDF-1.4");
        assertTrue(pdfContent.contains("/Type /Catalog"));
        assertTrue(pdfContent.contains("/Type /Page"));
        assertTrue(pdfContent.contains("xref\n"));
        assertTrue(pdfContent.endsWith("%%EOF\n"));
        assertTrue(pdfContent.contains("401"));
        assertTrue(pdfContent.contains("CERRADO"));

        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ));
    }

    @Test
    @DisplayName("15. Cotización PDF por cmd.topic (quote.closing): enruta a q.cmd.quote, consume, genera PDF real y hace ACK")
    void quoteTopic_validMessage_shouldRouteConsumeGeneratePdfAndAck() throws Exception {
        String eventId = UUID.randomUUID().toString();
        var envelope = createQuoteEnvelope("GenerateClosingQuote", eventId, 402L, "CERRADO");

        publishEnvelope(RabbitMQConfig.CMD_TOPIC_EXCHANGE, "quote.closing", envelope);

        awaitCondition(
                "Consumo y ACK de quote por cmd.topic (quote.closing)",
                () -> processedEventStore.isProcessed(eventId)
                        && quoteGenerator.getAttempts() == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        assertEquals(1, quoteGenerator.getAttempts());
        assertEquals(402L, quoteGenerator.getGeneratedPayloads().getFirst().productionId());
        assertTrue(Files.exists(quoteGenerator.getGeneratedPaths().getFirst()));
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ));
    }

    @Test
    @DisplayName("16. Cotización PDF con error permanente: agota 3 intentos (1 inicial + 2 reintentos), no marca idempotencia, hace NACK y deriva a q.cmd.quote.dlq")
    void quotePermanentFailure_exhaustsThreeAttempts_shouldNackAndRouteToDlq() throws Exception {
        quoteGenerator.failAlways();

        String eventId = UUID.randomUUID().toString();
        var envelope = createQuoteEnvelope("GenerateClosingQuote", eventId, 403L, "CERRADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, envelope);

        awaitCondition(
                "Tres intentos fallidos de quote y enrutamiento a q.cmd.quote.dlq",
                () -> quoteGenerator.getAttempts() == 3
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ) == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        assertEquals(3, quoteGenerator.getAttempts(), "Debe ejecutar exactamente 3 intentos (1 inicial + 2 reintentos)");
        assertEquals(0, quoteGenerator.getGeneratedPayloads().size());
        assertFalse(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE));
        assertEquals(1, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ));

        Message dlqMessage = rabbitTemplate.receive(RabbitMQConfig.Q_CMD_QUOTE_DLQ, 2000);
        assertNotNull(dlqMessage, "El mensaje debe poder leerse desde q.cmd.quote.dlq");
        assertTrue(new String(dlqMessage.getBody(), StandardCharsets.UTF_8).contains(eventId));
    }

    @Test
    @DisplayName("17. Cotización PDF con fallo transitorio (2 errores y éxito en 3.er intento): hace ACK, genera PDF real y DLQ queda vacía")
    void quoteTransientFailure_failsTwiceThenSucceeds_shouldAckWithoutDlq() throws Exception {
        quoteGenerator.failTimesThenSucceed(2);

        String eventId = UUID.randomUUID().toString();
        var envelope = createQuoteEnvelope("GenerateClosingQuote", eventId, 404L, "CERRADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, envelope);

        awaitCondition(
                "Recuperación en el tercer intento de quote",
                () -> processedEventStore.isProcessed(eventId)
                        && quoteGenerator.getAttempts() == 3
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        assertEquals(3, quoteGenerator.getAttempts(), "Debe haber ejecutado 2 fallos + 1 intento exitoso");
        assertEquals(1, quoteGenerator.getGeneratedPayloads().size());
        assertTrue(Files.exists(quoteGenerator.getGeneratedPaths().getFirst()));
        assertTrue(processedEventStore.isProcessed(eventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ));
    }

    @Test
    @DisplayName("18. Cotización PDF duplicada (mismo eventId): procesa solo una vez, hace ACK del duplicado y no envía a DLQ")
    void quoteDuplicateEvent_sameEventId_shouldProcessOnceAndAckDuplicate() throws Exception {
        String duplicateEventId = UUID.randomUUID().toString();
        var firstEnvelope = createQuoteEnvelope("GenerateClosingQuote", duplicateEventId, 405L, "CERRADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, firstEnvelope);

        awaitCondition(
                "Primer procesamiento de quote antes del duplicado",
                () -> processedEventStore.isProcessed(duplicateEventId)
                        && quoteGenerator.getAttemptsForProduction(405L) == 1
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        String sentinelEventId = UUID.randomUUID().toString();
        var duplicateEnvelope = createQuoteEnvelope("GenerateClosingQuote", duplicateEventId, 405L, "CERRADO");
        var sentinelEnvelope = createQuoteEnvelope("GenerateClosingQuote", sentinelEventId, 406L, "CERRADO");

        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, duplicateEnvelope);
        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, sentinelEnvelope);

        awaitCondition(
                "Drenado de duplicado y centinela en q.cmd.quote",
                () -> processedEventStore.isProcessed(sentinelEventId)
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        assertEquals(1, quoteGenerator.getAttemptsForProduction(405L), "El duplicado NO debe regenerar el PDF");
        assertEquals(1, quoteGenerator.getAttemptsForProduction(406L));
        assertEquals(2, quoteGenerator.getAttempts());
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ));
    }

    @Test
    @DisplayName("19. Cotización PDF inválida (JSON malformado y type no soportado): NACK inmediato a q.cmd.quote.dlq sin reintentos")
    void quoteInvalidMessages_malformedJsonAndUnsupportedType_shouldNackDirectlyToDlq() throws Exception {
        publishRawJson(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, "{ json-corrupto-quote-ep2");

        String unsupportedEventId = UUID.randomUUID().toString();
        var unsupportedTypeEnvelope = createQuoteEnvelope("UnsupportedQuoteCommand", unsupportedEventId, 407L, "CERRADO");
        publishEnvelope(RabbitMQConfig.CMD_DIRECT_EXCHANGE, RabbitMQConfig.RK_QUOTE_GEN, unsupportedTypeEnvelope);

        awaitCondition(
                "Enrutamiento inmediato de 2 mensajes inválidos a q.cmd.quote.dlq",
                () -> getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ) == 2
                        && getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE) == 0
        );

        assertEquals(0, quoteGenerator.getAttempts(), "Mensajes inválidos de quote no deben invocar el generador ni reintentar");
        assertFalse(processedEventStore.isProcessed(unsupportedEventId));
        assertEquals(0, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE));
        assertEquals(2, getQueueMessageCount(RabbitMQConfig.Q_CMD_QUOTE_DLQ));
    }

    private void assertIsolatedEphemeralBrokerConnection() {
        assertTrue(RABBITMQ_CONTAINER.isRunning(), "El contenedor RabbitMQ de Testcontainers debe estar activo");

        String containerId = RABBITMQ_CONTAINER.getContainerId();
        String containerName = RABBITMQ_CONTAINER.getContainerInfo().getName();
        int mappedPort = RABBITMQ_CONTAINER.getMappedPort(RABBITMQ_AMQP_PORT);
        String expectedHost = RABBITMQ_CONTAINER.getHost();
        String expectedAddresses = expectedHost + ":" + mappedPort;

        assertNotNull(containerId, "El containerId de Testcontainers no puede ser null");
        assertFalse(
                containerName.contains(PERSISTENT_DEMO_CONTAINER_NAME)
                        || containerName.contains(PERSISTENT_EP2_CONTAINER_NAME),
                "Prohibido conectarse al contenedor persistente de RabbitMQ"
        );
        assertFalse(
                containerId.startsWith(PERSISTENT_DEMO_CONTAINER_ID_PREFIX)
                        || containerId.startsWith(PERSISTENT_EP2_CONTAINER_ID_PREFIX),
                "Prohibido reutilizar el ID del contenedor persistente de RabbitMQ"
        );
        assertNotEquals(
                RABBITMQ_AMQP_PORT,
                mappedPort,
                "El puerto mapeado del contenedor efímero debe ser distinto de 5672"
        );

        assertEquals(
                expectedHost,
                connectionFactory.getHost(),
                "ConnectionFactory debe apuntar al host del broker efímero"
        );
        assertEquals(
                mappedPort,
                connectionFactory.getPort(),
                "ConnectionFactory debe apuntar exclusivamente al puerto efímero de Testcontainers"
        );
        assertNotEquals(
                RABBITMQ_AMQP_PORT,
                connectionFactory.getPort(),
                "ConnectionFactory tiene prohibido apuntar al puerto 5672 del broker persistente"
        );

        assertEquals(expectedAddresses, environment.getProperty("spring.rabbitmq.addresses"));
        assertEquals(mappedPort, environment.getProperty("spring.rabbitmq.port", Integer.class));
        assertEquals(3, environment.getProperty("eventomax.notify.retry.max-attempts", Integer.class));
        assertEquals(40L, environment.getProperty("eventomax.notify.retry.initial-interval-ms", Long.class));

        if (CONNECTION_LOGGED.compareAndSet(false, true)) {
            log.info(
                    "Aislamiento Testcontainers verificado: containerId={}, containerName={}, host={}, ephemeralPort={}, addresses={}",
                    containerId.substring(0, 12),
                    containerName,
                    connectionFactory.getHost(),
                    connectionFactory.getPort(),
                    expectedAddresses
            );
        }
    }

    private void purgeAllTestQueues() {
        assertIsolatedEphemeralBrokerConnection();
        amqpAdmin.purgeQueue(RabbitMQConfig.Q_CMD_EMAIL, false);
        amqpAdmin.purgeQueue(RabbitMQConfig.Q_CMD_EMAIL_DLQ, false);
        amqpAdmin.purgeQueue(RabbitMQConfig.Q_CMD_CREW, false);
        amqpAdmin.purgeQueue(RabbitMQConfig.Q_CMD_CREW_DLQ, false);
        amqpAdmin.purgeQueue(RabbitMQConfig.Q_CMD_QUOTE, false);
        amqpAdmin.purgeQueue(RabbitMQConfig.Q_CMD_QUOTE_DLQ, false);
    }

    private MessageEnvelope<EmailProductionStatusPayload> createEmailEnvelope(
            String type,
            String eventId,
            Long productionId,
            String status
    ) {
        var payload = new EmailProductionStatusPayload(
                productionId,
                "org-ep2",
                "Producción Integración Testcontainers #" + productionId,
                status,
                "2026-10-20T20:00:00",
                "Santiago"
        );
        return new MessageEnvelope<>(
                type,
                eventId,
                "2026-10-16T19:00:00Z",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                payload
        );
    }

    private MessageEnvelope<CrewTicketPayload> createCrewEnvelope(
            String type,
            String eventId,
            Long productionId,
            String status
    ) {
        var payload = new CrewTicketPayload(
                productionId,
                "Montaje Testcontainers #" + productionId,
                "2026-10-20T20:00:00",
                "Movistar Arena",
                status
        );
        return new MessageEnvelope<>(
                type,
                eventId,
                "2026-10-16T19:00:00Z",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                payload
        );
    }

    private MessageEnvelope<QuoteCommandPayload> createQuoteEnvelope(
            String type,
            String eventId,
            Long productionId,
            String status
    ) {
        var payload = new QuoteCommandPayload(
                productionId,
                "org-ep2",
                "Cierre Producción Testcontainers #" + productionId,
                status,
                "2026-10-20T20:00:00",
                "Movistar Arena",
                "2026-10-20T23:55:00Z"
        );
        return new MessageEnvelope<>(
                type,
                eventId,
                "2026-10-16T19:00:00Z",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                payload
        );
    }

    private void publishEnvelope(String exchange, String routingKey, Object envelope) throws Exception {
        assertIsolatedEphemeralBrokerConnection();
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        MessageProperties msgProps = new MessageProperties();
        msgProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(exchange, routingKey, new Message(body, msgProps));
    }

    private void publishRawJson(String exchange, String routingKey, String rawJson) {
        assertIsolatedEphemeralBrokerConnection();
        MessageProperties msgProps = new MessageProperties();
        msgProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        byte[] body = rawJson.getBytes(StandardCharsets.UTF_8);
        rabbitTemplate.send(exchange, routingKey, new Message(body, msgProps));
    }

    private void publishCapturedProductionsWireMessage(String exchange, String routingKey, String rawJson) {
        assertIsolatedEphemeralBrokerConnection();
        MessageProperties msgProps = new MessageProperties();
        msgProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        msgProps.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        msgProps.setHeader("__TypeId__", "cl.duoc.eventomax.productions.messaging.common.MessageEnvelope");
        byte[] body = rawJson.getBytes(StandardCharsets.UTF_8);
        rabbitTemplate.send(exchange, routingKey, new Message(body, msgProps));
    }

    private void awaitCondition(String description, BooleanSupplier condition) throws InterruptedException {
        long deadlineMs = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadlineMs) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(40);
        }
        assertTrue(condition.getAsBoolean(), "Tiempo de espera agotado verificando: " + description);
    }

    private int getQueueMessageCount(String queueName) {
        Properties props = amqpAdmin.getQueueProperties(queueName);
        if (props == null) {
            return 0;
        }
        Object count = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        if (count instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public ProgrammableEmailSender programmableEmailSender() {
            return new ProgrammableEmailSender();
        }

        @Bean
        @Primary
        public ProgrammableCrewTicketSender programmableCrewTicketSender() {
            return new ProgrammableCrewTicketSender();
        }

        @Bean
        @Primary
        public ProgrammableQuoteGenerator programmableQuoteGenerator() {
            return new ProgrammableQuoteGenerator(new PdfQuoteGenerator(TEST_QUOTE_OUTPUT_DIR.toString()));
        }
    }

    static class ProgrammableEmailSender implements EmailSender {

        private final AtomicInteger attempts = new AtomicInteger(0);
        private final AtomicInteger remainingFailures = new AtomicInteger(0);
        private final AtomicBoolean alwaysFail = new AtomicBoolean(false);
        private final Map<Long, AtomicInteger> attemptsByProduction = new ConcurrentHashMap<>();
        private final List<EmailProductionStatusPayload> sentPayloads = new CopyOnWriteArrayList<>();

        @Override
        public void send(EmailProductionStatusPayload payload) {
            int currentAttempt = attempts.incrementAndGet();
            if (payload != null && payload.productionId() != null) {
                attemptsByProduction
                        .computeIfAbsent(payload.productionId(), ignored -> new AtomicInteger(0))
                        .incrementAndGet();
            }
            if (alwaysFail.get() || remainingFailures.getAndDecrement() > 0) {
                throw new RuntimeException("Fallo controlado de EmailSender (intento " + currentAttempt + ")");
            }
            sentPayloads.add(payload);
        }

        void reset() {
            attempts.set(0);
            remainingFailures.set(0);
            alwaysFail.set(false);
            attemptsByProduction.clear();
            sentPayloads.clear();
        }

        void failAlways() {
            alwaysFail.set(true);
            remainingFailures.set(0);
        }

        void failTimesThenSucceed(int failures) {
            alwaysFail.set(false);
            remainingFailures.set(failures);
        }

        int getAttempts() {
            return attempts.get();
        }

        int getAttemptsForProduction(long productionId) {
            AtomicInteger count = attemptsByProduction.get(productionId);
            return count != null ? count.get() : 0;
        }

        List<EmailProductionStatusPayload> getSentPayloads() {
            return sentPayloads;
        }
    }

    static class ProgrammableCrewTicketSender implements CrewTicketSender {

        private final AtomicInteger attempts = new AtomicInteger(0);
        private final AtomicInteger remainingFailures = new AtomicInteger(0);
        private final AtomicBoolean alwaysFail = new AtomicBoolean(false);
        private final Map<Long, AtomicInteger> attemptsByProduction = new ConcurrentHashMap<>();
        private final List<CrewTicketPayload> sentPayloads = new CopyOnWriteArrayList<>();

        @Override
        public void send(CrewTicketPayload payload) {
            int currentAttempt = attempts.incrementAndGet();
            if (payload != null && payload.productionId() != null) {
                attemptsByProduction
                        .computeIfAbsent(payload.productionId(), ignored -> new AtomicInteger(0))
                        .incrementAndGet();
            }
            if (alwaysFail.get() || remainingFailures.getAndDecrement() > 0) {
                throw new RuntimeException("Fallo controlado de CrewTicketSender (intento " + currentAttempt + ")");
            }
            sentPayloads.add(payload);
        }

        void reset() {
            attempts.set(0);
            remainingFailures.set(0);
            alwaysFail.set(false);
            attemptsByProduction.clear();
            sentPayloads.clear();
        }

        void failAlways() {
            alwaysFail.set(true);
            remainingFailures.set(0);
        }

        void failTimesThenSucceed(int failures) {
            alwaysFail.set(false);
            remainingFailures.set(failures);
        }

        int getAttempts() {
            return attempts.get();
        }

        int getAttemptsForProduction(long productionId) {
            AtomicInteger count = attemptsByProduction.get(productionId);
            return count != null ? count.get() : 0;
        }

        List<CrewTicketPayload> getSentPayloads() {
            return sentPayloads;
        }
    }

    static class ProgrammableQuoteGenerator implements QuoteGenerator {

        private final PdfQuoteGenerator delegate;
        private final AtomicInteger attempts = new AtomicInteger(0);
        private final AtomicInteger remainingFailures = new AtomicInteger(0);
        private final AtomicBoolean alwaysFail = new AtomicBoolean(false);
        private final Map<Long, AtomicInteger> attemptsByProduction = new ConcurrentHashMap<>();
        private final List<QuoteCommandPayload> generatedPayloads = new CopyOnWriteArrayList<>();
        private final List<Path> generatedPaths = new CopyOnWriteArrayList<>();

        ProgrammableQuoteGenerator(PdfQuoteGenerator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Path generate(MessageEnvelope<QuoteCommandPayload> envelope) {
            int currentAttempt = attempts.incrementAndGet();
            QuoteCommandPayload payload = envelope != null ? envelope.payload() : null;
            if (payload != null && payload.productionId() != null) {
                attemptsByProduction
                        .computeIfAbsent(payload.productionId(), ignored -> new AtomicInteger(0))
                        .incrementAndGet();
            }
            if (alwaysFail.get() || remainingFailures.getAndDecrement() > 0) {
                throw new RuntimeException("Fallo controlado de QuoteGenerator (intento " + currentAttempt + ")");
            }
            Path path = delegate.generate(envelope);
            generatedPayloads.add(payload);
            generatedPaths.add(path);
            return path;
        }

        void reset() {
            attempts.set(0);
            remainingFailures.set(0);
            alwaysFail.set(false);
            attemptsByProduction.clear();
            generatedPayloads.clear();
            generatedPaths.clear();
        }

        void failAlways() {
            alwaysFail.set(true);
            remainingFailures.set(0);
        }

        void failTimesThenSucceed(int failures) {
            alwaysFail.set(false);
            remainingFailures.set(failures);
        }

        int getAttempts() {
            return attempts.get();
        }

        int getAttemptsForProduction(long productionId) {
            AtomicInteger count = attemptsByProduction.get(productionId);
            return count != null ? count.get() : 0;
        }

        List<QuoteCommandPayload> getGeneratedPayloads() {
            return generatedPayloads;
        }

        List<Path> getGeneratedPaths() {
            return generatedPaths;
        }
    }
}
