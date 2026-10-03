package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.sender.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Servicio de aplicación que coordina el envío de notificaciones de email.
 * Delega el envío real a la abstracción {@link EmailSender}.
 */
@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final EmailSender emailSender;

    public EmailNotificationService(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    /**
     * Procesa una notificación de email de estado de producción.
     *
     * @param envelope el envelope completo con el payload de email
     */
    public void processEmailNotification(MessageEnvelope<EmailProductionStatusPayload> envelope) {
        log.info("Procesando notificación de email: productionId={}, status={}",
                envelope.payload().productionId(),
                envelope.payload().status());

        emailSender.send(envelope.payload());
    }
}
