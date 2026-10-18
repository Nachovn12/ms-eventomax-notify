package cl.duoc.eventomax.notify.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Configuración de política de reintentos acotados para los consumidores AMQP.
 * <p>
 * <strong>Semántica de intentos vs. reintentos:</strong> En {@link SimpleRetryPolicy},
 * {@code maxAttempts = 3} significa exactamente <strong>3 intentos totales</strong> de ejecución
 * (1 intento inicial + 2 reintentos adicionales), y <em>no</em> 3 reintentos adicionales
 * (lo cual equivaldría a 4 ejecuciones totales). Tras fallar el 3.er intento total,
 * la excepción se propaga al listener para ejecutar {@code basicNack(deliveryTag, false, false)}
 * y enrutar el mensaje a su DLQ correspondiente vía {@code cmd.dead.dlx}.
 */
@Configuration
public class NotificationRetryConfig {

    @Value("${eventomax.notify.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${eventomax.notify.retry.initial-interval-ms:500}")
    private long initialIntervalMs;

    @Bean
    public RetryTemplate retryTemplate() {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser >= 1");
        }
        if (initialIntervalMs < 0) {
            throw new IllegalArgumentException("initialIntervalMs debe ser >= 0");
        }

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(maxAttempts);

        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(initialIntervalMs);

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);

        return template;
    }
}

