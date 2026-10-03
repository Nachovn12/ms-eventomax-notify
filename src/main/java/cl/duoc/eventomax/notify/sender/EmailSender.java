package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;

/**
 * Abstracción para el envío de emails.
 * <p>
 * Permite intercambiar la implementación (logging, SMTP, SES, etc.)
 * sin modificar la lógica de negocio.
 */
public interface EmailSender {

    /**
     * Envía una notificación de email basada en el payload de estado de producción.
     *
     * @param payload datos de la notificación
     */
    void send(EmailProductionStatusPayload payload);
}
