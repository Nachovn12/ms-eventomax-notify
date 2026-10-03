package cl.duoc.eventomax.notify.idempotency;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementación in-memory del almacén de eventos procesados.
 * <p>
 * Utiliza un {@link ConcurrentHashMap} para garantizar thread-safety
 * y atomicidad por clave en operaciones concurrentes.
 * <p>
 * <strong>Limitación:</strong> La idempotencia in-memory NO es durable.
 * Se pierde al reiniciar el contenedor. Una solución persistente/distribuida
 * (e.g. Redis, base de datos) queda fuera de este incremento.
 */
@Component
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final ConcurrentHashMap<String, Boolean> processedEvents = new ConcurrentHashMap<>();

    @Override
    public boolean isProcessed(String eventId) {
        return processedEvents.containsKey(eventId);
    }

    @Override
    public void markAsProcessed(String eventId) {
        processedEvents.put(eventId, Boolean.TRUE);
    }

    @Override
    public boolean processOnce(String eventId, Runnable processor) {
        AtomicBoolean executed = new AtomicBoolean(false);

        try {
            processedEvents.computeIfAbsent(eventId, key -> {
                processor.run();
                executed.set(true);
                return Boolean.TRUE;
            });
            return executed.get();
        } catch (RuntimeException e) {
            // Si el processor lanza excepción, computeIfAbsent la propaga
            // y NO inserta la clave en el mapa, permitiendo reintentos.
            throw e;
        }
    }
}
