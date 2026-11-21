package cl.duoc.eventomax.notify.closure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties("eventomax.notify.closure")
public record ClosureSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean workerEnabled,
        @DefaultValue("true") boolean generationEnabled,
        @DefaultValue("false") boolean dispatchEnabled,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("15m") Duration documentWait,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10000000") int maxPdfBytes,
        @DefaultValue("") List<Long> authorizedProductions) {

    public ClosureSettings {
        if (lease == null || lease.isNegative() || lease.isZero() || documentWait == null
                || documentWait.isNegative() || documentWait.isZero() || maxAttempts < 1 || maxPdfBytes < 1024) {
            throw new IllegalArgumentException("Invalid durable closure settings");
        }
        if (authorizedProductions == null || authorizedProductions.isEmpty()) {
            authorizedProductions = List.of();
        } else {
            Set<Long> seen = new HashSet<>();
            for (Long id : authorizedProductions) {
                if (id == null || id <= 0) {
                    throw new IllegalArgumentException("Authorized production IDs must be strictly positive: " + id);
                }
                if (!seen.add(id)) {
                    throw new IllegalArgumentException("Duplicate production ID in cohort: " + id);
                }
            }
            authorizedProductions = List.copyOf(authorizedProductions);
        }
    }

    public Set<Long> authorizedProductionsSet() {
        return Set.copyOf(authorizedProductions);
    }
}
