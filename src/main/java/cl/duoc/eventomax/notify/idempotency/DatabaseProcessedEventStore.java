package cl.duoc.eventomax.notify.idempotency;

import cl.duoc.eventomax.notify.model.ProcessedEvent;
import cl.duoc.eventomax.notify.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@Primary
public class DatabaseProcessedEventStore implements ProcessedEventStore {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProcessedEventStore.class);

    private final ProcessedEventRepository repository;

    public DatabaseProcessedEventStore(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        return repository.existsByEventId(eventId.trim());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        String cleanId = eventId.trim();
        if (!repository.existsByEventId(cleanId)) {
            String traceId = MDC.get("traceId");
            String correlationId = MDC.get("correlationId");
            try {
                repository.save(new ProcessedEvent(
                        cleanId,
                        "UNKNOWN",
                        LocalDateTime.now(),
                        traceId,
                        correlationId
                ));
            } catch (DataIntegrityViolationException e) {
                log.debug("Evento ya registrado concurrentemente: eventId={}", cleanId);
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOnce(String eventId, Runnable processor) {
        if (eventId == null || eventId.isBlank()) {
            processor.run();
            return true;
        }

        String cleanId = eventId.trim();
        if (repository.existsByEventId(cleanId)) {
            log.info("[IDEMPOTENCIA_DURABLE] Evento duplicado detectado en base de datos: eventId={}", cleanId);
            return false;
        }

        // Ejecutar la tarea de negocio
        processor.run();

        // Tras ejecución exitosa, registrar el evento de forma duradera
        try {
            String traceId = MDC.get("traceId");
            String correlationId = MDC.get("correlationId");
            repository.save(new ProcessedEvent(
                    cleanId,
                    "COMMAND_PROCESSED",
                    LocalDateTime.now(),
                    traceId,
                    correlationId
            ));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("[IDEMPOTENCIA_RACE] Conflicto concurrente al registrar eventId={}. Marcado como duplicado.", cleanId);
            return false;
        }
    }
}

