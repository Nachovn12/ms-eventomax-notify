package cl.duoc.eventomax.notify.repository;

import cl.duoc.eventomax.notify.model.CrewTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CrewTicketRepository extends JpaRepository<CrewTicket, Long> {

    Optional<CrewTicket> findByProductionId(Long productionId);

    Optional<CrewTicket> findTopByProductionIdOrderByIdDesc(Long productionId);

    Optional<CrewTicket> findByEventId(String eventId);

    boolean existsByEventId(String eventId);
}
