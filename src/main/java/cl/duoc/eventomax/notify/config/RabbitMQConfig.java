package cl.duoc.eventomax.notify.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaración idempotente de la topología RabbitMQ.
 * <p>
 * Los exchanges, queues y bindings definidos aquí son exactamente compatibles
 * con la infraestructura declarada en infra-eventomax. Spring AMQP los declara
 * de forma idempotente: si ya existen con las mismas propiedades, no produce error.
 */
@Configuration
public class RabbitMQConfig {

    // --- Exchange names ---
    public static final String CMD_DIRECT_EXCHANGE = "cmd.direct";
    public static final String CMD_TOPIC_EXCHANGE = "cmd.topic";
    public static final String CMD_DEAD_DLX_EXCHANGE = "cmd.dead.dlx";

    // --- Queue names ---
    public static final String Q_CMD_EMAIL = "q.cmd.email";
    public static final String Q_CMD_EMAIL_DLQ = "q.cmd.email.dlq";

    // --- Routing keys ---
    public static final String RK_EMAIL_SEND = "email.send";
    public static final String RK_EMAIL_TOPIC = "email.*";
    public static final String RK_EMAIL_DLQ = "q.cmd.email.dlq";

    public static final String Q_CMD_CREW = "q.cmd.crew";
    public static final String Q_CMD_CREW_DLQ = "q.cmd.crew.dlq";

    public static final String RK_CREW_TICKET = "crew.ticket";
    public static final String RK_CREW_TOPIC = "crew.#";
    public static final String RK_CREW_DLQ = "q.cmd.crew.dlq";

    // --- Exchanges ---

    @Bean
    public DirectExchange cmdDirectExchange() {
        return new DirectExchange(CMD_DIRECT_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange cmdTopicExchange() {
        return new TopicExchange(CMD_TOPIC_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange cmdDeadDlxExchange() {
        return new DirectExchange(CMD_DEAD_DLX_EXCHANGE, true, false);
    }

    // --- Queues ---

    @Bean
    public Queue qCmdEmail() {
        return QueueBuilder.durable(Q_CMD_EMAIL)
                .withArgument("x-dead-letter-exchange", CMD_DEAD_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_EMAIL_DLQ)
                .build();
    }

    @Bean
    public Queue qCmdEmailDlq() {
        return QueueBuilder.durable(Q_CMD_EMAIL_DLQ).build();
    }

    @Bean
    public Queue qCmdCrew() {
        return QueueBuilder.durable(Q_CMD_CREW)
                .withArgument("x-dead-letter-exchange", CMD_DEAD_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_CREW_DLQ)
                .build();
    }

    @Bean
    public Queue qCmdCrewDlq() {
        return QueueBuilder.durable(Q_CMD_CREW_DLQ).build();
    }

    // --- Bindings ---

    @Bean
    public Binding bindingDirectEmail() {
        return BindingBuilder.bind(qCmdEmail())
                .to(cmdDirectExchange())
                .with(RK_EMAIL_SEND);
    }

    @Bean
    public Binding bindingTopicEmail() {
        return BindingBuilder.bind(qCmdEmail())
                .to(cmdTopicExchange())
                .with(RK_EMAIL_TOPIC);
    }

    @Bean
    public Binding bindingDlqEmail() {
        return BindingBuilder.bind(qCmdEmailDlq())
                .to(cmdDeadDlxExchange())
                .with(RK_EMAIL_DLQ);
    }

    @Bean
    public Binding bindingDirectCrew() {
        return BindingBuilder.bind(qCmdCrew())
                .to(cmdDirectExchange())
                .with(RK_CREW_TICKET);
    }

    @Bean
    public Binding bindingTopicCrew() {
        return BindingBuilder.bind(qCmdCrew())
                .to(cmdTopicExchange())
                .with(RK_CREW_TOPIC);
    }

    @Bean
    public Binding bindingDlqCrew() {
        return BindingBuilder.bind(qCmdCrewDlq())
                .to(cmdDeadDlxExchange())
                .with(RK_CREW_DLQ);
    }

}
