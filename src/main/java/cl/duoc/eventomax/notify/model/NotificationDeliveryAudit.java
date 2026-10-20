package cl.duoc.eventomax.notify.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "notification_delivery_audit")
public class NotificationDeliveryAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "production_id")
    private Long productionId;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    @Column(length = 255)
    private String recipient;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public NotificationDeliveryAudit() {
    }

    public NotificationDeliveryAudit(Long productionId, String eventId, String notificationType, String recipient, String status, String errorDetail) {
        this.productionId = productionId;
        this.eventId = eventId;
        this.notificationType = notificationType;
        this.recipient = recipient;
        this.status = status;
        this.errorDetail = errorDetail;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getProductionId() {
        return productionId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

