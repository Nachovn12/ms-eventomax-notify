package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingCrewTicketSender implements CrewTicketSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingCrewTicketSender.class);

    @Override
    public void send(CrewTicketPayload payload) {
        log.info("[CREW TICKET SIMULADO] Generando ticket de cuadrilla: productionId={}, productionName='{}', status={}, scheduledAt='{}', location='{}'",
                payload.productionId(),
                payload.productionName(),
                payload.status(),
                payload.scheduledAt(),
                payload.location());
    }
}
