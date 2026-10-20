package cl.duoc.eventomax.notify.controller;

import cl.duoc.eventomax.notify.model.CrewTicket;
import cl.duoc.eventomax.notify.repository.CrewTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notify/tickets")
public class NotifyTicketController {

    private static final Logger log = LoggerFactory.getLogger(NotifyTicketController.class);

    private final CrewTicketRepository crewTicketRepository;

    public NotifyTicketController(CrewTicketRepository crewTicketRepository) {
        this.crewTicketRepository = crewTicketRepository;
    }

    @GetMapping("/{productionId}")
    public ResponseEntity<CrewTicket> getTicketByProductionId(@PathVariable Long productionId) {
        log.info("Consultando ticket de cuadrilla para productionId={}", productionId);
        return crewTicketRepository.findTopByProductionIdOrderByIdDesc(productionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

