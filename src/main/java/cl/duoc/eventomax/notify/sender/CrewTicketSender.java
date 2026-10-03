package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.crew.CrewTicketPayload;

public interface CrewTicketSender {
    void send(CrewTicketPayload payload);
}
