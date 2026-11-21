package cl.duoc.eventomax.notify.messaging;

import cl.duoc.eventomax.notify.config.RabbitMQConfig;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates that when spring.rabbitmq.listener.simple.auto-startup=false:
 * 1. Listener containers are registered but NOT running.
 * 2. Published messages into q.cmd.email, q.cmd.quote, and q.cmd.crew are NOT consumed or ACKed.
 * 3. Messages remain intact in their queues with 0 active consumers.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "eventomax.notify.closure.worker-enabled=false",
        "eventomax.notify.closure.dispatch-enabled=false",
        "EVENTOMAX_NOTIFY_EMAIL_PROVIDER=logging",
        "EVENTOMAX_NOTIFY_CREW_PROVIDER=logging"
})
@Testcontainers
class RabbitPausedStartupTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.3.6-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "guest")
            .withEnv("RABBITMQ_DEFAULT_PASS", "guest")
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
    }

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void whenAutoStartupIsFalse_listenersDoNotConsumeOrAckMessages() throws Exception {
        // 1. Verify container registry state
        var containers = registry.getListenerContainers();
        assertThat(containers).isNotEmpty();
        for (var container : containers) {
            assertThat(container.isRunning())
                    .as("Listener container must NOT be running when auto-startup=false")
                    .isFalse();
        }

        // 2. Connect directly via raw RabbitMQ client and declare/publish to queues
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername("guest");
        factory.setPassword("guest");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // Declare queues idempotently if needed
            channel.queueDeclare(RabbitMQConfig.Q_CMD_EMAIL, true, false, false, null);
            channel.queueDeclare(RabbitMQConfig.Q_CMD_QUOTE, true, false, false, null);
            channel.queueDeclare(RabbitMQConfig.Q_CMD_CREW, true, false, false, null);

            // Verify 0 active consumers on each queue
            assertThat(channel.consumerCount(RabbitMQConfig.Q_CMD_EMAIL)).isEqualTo(0);
            assertThat(channel.consumerCount(RabbitMQConfig.Q_CMD_QUOTE)).isEqualTo(0);
            assertThat(channel.consumerCount(RabbitMQConfig.Q_CMD_CREW)).isEqualTo(0);

            // Publish one message to each queue
            var emailPayload = new EmailProductionStatusPayload(10L, "org-1", "test@example.com", "Concert", "CONFIRMADO", "2026-12-01T18:00:00", "Arena");
            var emailEnvelope = new MessageEnvelope<>("SendProductionStatusEmail", "evt-email-1", "2026-11-20T20:00:00Z", "trace-1", "corr-1", emailPayload);
            channel.basicPublish("", RabbitMQConfig.Q_CMD_EMAIL, null, objectMapper.writeValueAsBytes(emailEnvelope));

            var quotePayload = new QuoteCommandPayload(10L, "org-1", "Concert", "CERRADO", "2026-12-01T18:00:00", "Arena", "2026-11-20T20:00:00Z");
            var quoteEnvelope = new MessageEnvelope<>("GenerateClosingQuote", "evt-quote-1", "2026-11-20T20:00:00Z", "trace-1", "corr-1", quotePayload);
            channel.basicPublish("", RabbitMQConfig.Q_CMD_QUOTE, null, objectMapper.writeValueAsBytes(quoteEnvelope));

            var crewPayload = new CrewTicketPayload(10L, "Concert", "2026-12-01T18:00:00", "Arena", "CONFIRMADO");
            var crewEnvelope = new MessageEnvelope<>("SendCrewTicket", "evt-crew-1", "2026-11-20T20:00:00Z", "trace-1", "corr-1", crewPayload);
            channel.basicPublish("", RabbitMQConfig.Q_CMD_CREW, null, objectMapper.writeValueAsBytes(crewEnvelope));

            // Sleep brief moment to verify no background consumption occurs
            Thread.sleep(1500);

            // Check that messages are still queued and unconsumed (messageCount == 1, consumerCount == 0)
            var emailQueueState = channel.queueDeclarePassive(RabbitMQConfig.Q_CMD_EMAIL);
            assertThat(emailQueueState.getMessageCount()).isEqualTo(1);
            assertThat(emailQueueState.getConsumerCount()).isEqualTo(0);

            var quoteQueueState = channel.queueDeclarePassive(RabbitMQConfig.Q_CMD_QUOTE);
            assertThat(quoteQueueState.getMessageCount()).isEqualTo(1);
            assertThat(quoteQueueState.getConsumerCount()).isEqualTo(0);

            var crewQueueState = channel.queueDeclarePassive(RabbitMQConfig.Q_CMD_CREW);
            assertThat(crewQueueState.getMessageCount()).isEqualTo(1);
            assertThat(crewQueueState.getConsumerCount()).isEqualTo(0);
        }
    }
}
