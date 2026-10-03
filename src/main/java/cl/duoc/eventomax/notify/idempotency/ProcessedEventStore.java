package cl.duoc.eventomax.notify.idempotency;

/**
 * Abstracción para el almacén de eventos procesados (idempotencia).
 * <p>
 * Permite verificar si un eventId ya fue procesado y marcarlo como tal.
 * La operación principal es {@link #processOnce}, que garantiza atomicidad
 * dentro de una misma instancia JVM.
 * <p>
 * <strong>Limitación:</strong> esta abstracción NO resuelve idempotencia
 * entre múltiples contenedores ni entre reinicios del proceso.
 */
public interface ProcessedEventStore {

    /**
     * Verifica si el evento ya fue procesado.
     *
     * @param eventId identificador único del evento
     * @return true si ya fue procesado
     */
    boolean isProcessed(String eventId);

    /**
     * Marca un evento como procesado.
     *
     * @param eventId identificador único del evento
     */
    void markAsProcessed(String eventId);

    /**
     * Ejecuta {@code processor} de forma atómica para el {@code eventId} dado,
     * garantizando que solo un thread lo ejecute dentro de esta JVM.
     * <p>
     * Semántica:
     * <ul>
     *   <li>Retorna {@code true} si este llamado ejecutó el processor exitosamente
     *       y registró el eventId como procesado.</li>
     *   <li>Retorna {@code false} si el eventId ya estaba procesado
     *       (el processor NO se ejecuta).</li>
     *   <li>Si el processor lanza {@link RuntimeException}, la excepción se propaga
     *       y el eventId NO queda registrado como procesado.</li>
     * </ul>
     *
     * @param eventId   identificador único del evento
     * @param processor acción a ejecutar si el evento no fue procesado previamente
     * @return true si el processor se ejecutó exitosamente, false si ya estaba procesado
     */
    boolean processOnce(String eventId, Runnable processor);
}
