package cl.duoc.eventomax.notify.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

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
