package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import cl.duoc.eventomax.notify.sender.CrewTicketSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CrewTicketNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CrewTicketNotificationService.class);

    private final CrewTicketSender crewTicketSender;

    public CrewTicketNotificationService(CrewTicketSender crewTicketSender) {
        this.crewTicketSender = crewTicketSender;
    }

    public void processCrewTicket(MessageEnvelope<CrewTicketPayload> envelope) {
        log.info("Procesando comando de ticket de cuadrilla: productionId={}, status={}",
                envelope.payload().productionId(),
                envelope.payload().status());

        crewTicketSender.send(envelope.payload());
    }
}
