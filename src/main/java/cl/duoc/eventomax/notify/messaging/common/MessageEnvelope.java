package cl.duoc.eventomax.notify.messaging.common;

/**
 * Envelope genérico que encapsula todo mensaje de comando.
 * <p>
 * Compatible con el contrato publicado por ms-eventomax-productions.
 *
 * @param type            Tipo de comando (e.g. "SendProductionStatusEmail")
 * @param eventId         Identificador único del evento para idempotencia
 * @param timestamp       Marca de tiempo ISO-8601 del evento
 * @param traceId         ID de traza para observabilidad distribuida
 * @param correlationId   ID de correlación para seguimiento de flujo
 * @param payload         Contenido específico del comando
 */
public record MessageEnvelope<T>(
        String type,
        String eventId,
        String timestamp,
        String traceId,
        String correlationId,
        T payload
) {}
