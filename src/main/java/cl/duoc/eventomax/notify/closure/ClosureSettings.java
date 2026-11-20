package cl.duoc.eventomax.notify.closure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("eventomax.notify.closure")
public record ClosureSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean generationEnabled,
        @DefaultValue("false") boolean dispatchEnabled,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("15m") Duration documentWait,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10000000") int maxPdfBytes) {
    public ClosureSettings {
        if (lease == null || lease.isNegative() || lease.isZero() || documentWait == null
                || documentWait.isNegative() || documentWait.isZero() || maxAttempts < 1 || maxPdfBytes < 1024) {
            throw new IllegalArgumentException("Invalid durable closure settings");
        }
    }
}
