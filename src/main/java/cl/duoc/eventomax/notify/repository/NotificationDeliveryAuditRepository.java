package cl.duoc.eventomax.notify.repository;

import cl.duoc.eventomax.notify.model.NotificationDeliveryAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationDeliveryAuditRepository extends JpaRepository<NotificationDeliveryAudit, Long> {

    List<NotificationDeliveryAudit> findByProductionIdOrderByCreatedAtDesc(Long productionId);

    List<NotificationDeliveryAudit> findByEventId(String eventId);
}

