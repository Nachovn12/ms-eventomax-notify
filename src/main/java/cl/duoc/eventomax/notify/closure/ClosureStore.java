package cl.duoc.eventomax.notify.closure;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL is the durable work queue. No transaction spans PDF/storage/SMTP I/O. */
@Repository
@ConditionalOnProperty(name = "eventomax.notify.closure.enabled", havingValue = "true", matchIfMissing = true)
public class ClosureStore {
    public record Document(UUID id, long productionId, String organizerId, String commandId, String envelope,
                           String state, String key, String sha, Long size, int attempts, UUID token) {}
    public record Delivery(UUID id, long productionId, String envelope, UUID documentId, int attempts, UUID token) {}
    private static final RowMapper<Document> DOCUMENT = (r, n) -> new Document(
            r.getObject("id", UUID.class), r.getLong("production_id"), r.getString("organizer_id"),
            r.getString("command_event_id"), r.getString("envelope_json"), r.getString("state"),
            r.getString("storage_key"), r.getString("sha256"), r.getObject("size_bytes", Long.class),
            r.getInt("attempts"), r.getObject("lease_token", UUID.class));
    private static final RowMapper<Delivery> DELIVERY = (r, n) -> new Delivery(
            r.getObject("id", UUID.class), r.getLong("production_id"), r.getString("envelope_json"),
            r.getObject("document_id", UUID.class), r.getInt("attempts"), r.getObject("lease_token", UUID.class));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ClosureSettings settings;

    public ClosureStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, ClosureSettings settings) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(5);
        this.json = json;
        this.settings = settings;
        this.tx = new TransactionTemplate(manager);
        // Returning to the listener must mean this transaction has actually committed.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(5);
    }

    public void acceptEmail(MessageEnvelope<EmailProductionStatusPayload> envelope) {
        validate(envelope, envelope.payload().productionId(), envelope.payload().organizerId(), envelope.payload().status());
        tx.executeWithoutResult(status -> {
            if (!inbox("EMAIL", envelope.eventId(), envelope.payload().productionId(), encode(envelope))) return;
            var p = envelope.payload();
            String initial = RecipientCheck.valid(p.recipientEmail()) ? "WAITING_DOCUMENT" : "FAILED_PERMANENT";
            jdbc.update("""
                    INSERT INTO notify.closure_deliveries
                      (id, production_id, organizer_id, command_event_id, envelope_json, state, document_deadline, error_code)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), ?)
                    ON CONFLICT (production_id, notification_kind) DO NOTHING
                    """, UUID.randomUUID(), p.productionId(), p.organizerId(), envelope.eventId(), encode(envelope),
                    initial, settings.documentWait().toMillis(), "FAILED_PERMANENT".equals(initial) ? "NO_AUTHORIZED_CONTACT" : null);
            var existing = jdbc.query("SELECT * FROM notify.closure_deliveries WHERE production_id = ?", DELIVERY, p.productionId()).getFirst();
            var first = email(existing.envelope()).payload();
            if (!Objects.equals(first.organizerId(), p.organizerId())
                    || !Objects.equals(trim(first.recipientEmail()), trim(p.recipientEmail()))) {
                throw new IllegalArgumentException("CLOSURE_CONTACT_CONFLICT");
            }
        });
    }

    public void acceptDocument(MessageEnvelope<QuoteCommandPayload> envelope) {
        validate(envelope, envelope.payload().productionId(), envelope.payload().organizerId(), envelope.payload().status());
        tx.executeWithoutResult(status -> {
            if (!inbox("DOCUMENT", envelope.eventId(), envelope.payload().productionId(), encode(envelope))) return;
            var p = envelope.payload();
            jdbc.update("""
                    INSERT INTO notify.closure_documents
                        (id, production_id, organizer_id, command_event_id, envelope_json, state)
                    VALUES (?, ?, ?, ?, ?, 'PENDING') ON CONFLICT (production_id) DO NOTHING
                    """, UUID.randomUUID(), p.productionId(), p.organizerId(), envelope.eventId(), encode(envelope));
            if (!document(p.productionId()).orElseThrow().organizerId().equals(p.organizerId())) {
                throw new IllegalArgumentException("CLOSURE_ORGANIZER_CONFLICT");
            }
        });
    }

    private boolean inbox(String consumer, String eventId, long productionId, String envelope) {
        String hash = ClosurePdf.sha256(envelope.getBytes(StandardCharsets.UTF_8));
        int inserted = jdbc.update("""
                INSERT INTO notify.closure_inbox (consumer, event_id, production_id, envelope_json, envelope_sha256)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (consumer, event_id) DO NOTHING
                """, consumer, eventId, productionId, envelope, hash);
        if (inserted == 0) {
            String old = jdbc.queryForObject("SELECT envelope_sha256 FROM notify.closure_inbox WHERE consumer=? AND event_id=?",
                    String.class, consumer, eventId);
            if (!hash.equals(old)) throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
        }
        return inserted == 1;
    }

    public Optional<Document> claimDocument() {
        return tx.execute(status -> {
            var candidates = jdbc.query("""
                    SELECT * FROM notify.closure_documents
                    WHERE state IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= CURRENT_TIMESTAMP AND attempts < ?
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, DOCUMENT, settings.maxAttempts());
            if (candidates.isEmpty()) return Optional.empty();
            UUID id = candidates.getFirst().id(), token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE notify.closure_documents SET state='GENERATING', attempts=attempts+1,
                    lease_token=?, lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') WHERE id=?
                    """, token, settings.lease().toMillis(), id);
            return Optional.of(jdbc.query("SELECT * FROM notify.closure_documents WHERE id=?", DOCUMENT, id).getFirst());
        });
    }

    public boolean prepared(Document doc, String key, String hash, long size) {
        return jdbc.update("""
                UPDATE notify.closure_documents SET storage_key=?, sha256=?, size_bytes=?
                WHERE id=? AND state='GENERATING' AND lease_token=? AND lease_until > CURRENT_TIMESTAMP
                """, key, hash, size, doc.id(), doc.token()) == 1;
    }

    public boolean documentReady(Document doc) {
        return tx.execute(status -> jdbc.update("""
                UPDATE notify.closure_documents SET state='READY', ready_at=CURRENT_TIMESTAMP,
                lease_until=NULL, error_code=NULL WHERE id=? AND state='GENERATING' AND lease_token=?
                AND lease_until > CURRENT_TIMESTAMP
                """, doc.id(), doc.token()) == 1);
    }

    public void documentFailed(Document doc, String code, boolean permanent) {
        String state = permanent || doc.attempts() >= settings.maxAttempts() ? "FAILED" : "RETRY_WAIT";
        jdbc.update("""
                UPDATE notify.closure_documents SET state=?, error_code=?, lease_until=NULL,
                next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                WHERE id=? AND state='GENERATING' AND lease_token=?
                """, state, code, retryDelay(doc.attempts()).toMillis(), doc.id(), doc.token());
    }

    public void reconcile() {
        tx.executeWithoutResult(status -> {
            // An expired SMTP claim is ambiguous, including a crash before the actual socket write.
            // Match finish(): lock deliveries before attempts. RETURNING preserves the exact expired
            // lease identity after SENDING becomes UNKNOWN, without touching another lease's attempt.
            jdbc.update("""
                    WITH expired AS (
                        UPDATE notify.closure_deliveries SET state='UNKNOWN', error_code='LEASE_EXPIRED'
                        WHERE state='SENDING' AND lease_until <= CURRENT_TIMESTAMP
                        RETURNING id, lease_token
                    )
                    UPDATE notify.closure_smtp_attempts a SET state='UNKNOWN', error_code='LEASE_EXPIRED', finished_at=CURRENT_TIMESTAMP
                    FROM expired e WHERE a.delivery_id=e.id AND a.lease_token=e.lease_token
                    AND a.state='SENDING'
                    """);
            jdbc.update("""
                    UPDATE notify.closure_documents SET state=CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                    error_code='GENERATION_LEASE_EXPIRED', next_attempt_at=CURRENT_TIMESTAMP
                    WHERE state='GENERATING' AND lease_until <= CURRENT_TIMESTAMP
                    """, settings.maxAttempts());
            jdbc.update("""
                    UPDATE notify.closure_deliveries j SET state='FAILED_DOCUMENT', error_code='DOCUMENT_ORGANIZER_CONFLICT'
                    FROM notify.closure_documents d WHERE j.production_id=d.production_id AND j.state='WAITING_DOCUMENT'
                    AND j.organizer_id <> d.organizer_id
                    """);
            jdbc.update("""
                    UPDATE notify.closure_deliveries j SET state='READY', document_id=d.id, error_code=NULL
                    FROM notify.closure_documents d WHERE j.production_id=d.production_id AND j.organizer_id=d.organizer_id
                    AND j.state='WAITING_DOCUMENT' AND d.state='READY' AND CURRENT_TIMESTAMP < j.document_deadline
                    """);
            jdbc.update("""
                    UPDATE notify.closure_deliveries j SET state='FAILED_DOCUMENT', error_code='DOCUMENT_FAILED'
                    FROM notify.closure_documents d WHERE j.production_id=d.production_id
                    AND j.state='WAITING_DOCUMENT' AND d.state='FAILED'
                    """);
            jdbc.update("""
                    UPDATE notify.closure_deliveries SET state='FAILED_DOCUMENT', error_code='DOCUMENT_DEADLINE'
                    WHERE state='WAITING_DOCUMENT' AND document_deadline <= CURRENT_TIMESTAMP
                    """);
        });
    }

    public Optional<Delivery> claimDelivery() {
        if (!settings.dispatchEnabled() || settings.authorizedProductions().isEmpty()) {
            return Optional.empty();
        }
        return tx.execute(status -> {
            Long[] cohort = settings.authorizedProductions().toArray(Long[]::new);
            var candidates = jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        SELECT * FROM notify.closure_deliveries
                        WHERE state IN ('READY','RETRY_WAIT')
                          AND production_id = ANY(?)
                          AND next_attempt_at <= CURRENT_TIMESTAMP AND attempts < ?
                        ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """);
                ps.setArray(1, con.createArrayOf("bigint", cohort));
                ps.setInt(2, settings.maxAttempts());
                return ps;
            }, DELIVERY);
            if (candidates.isEmpty()) return Optional.empty();
            UUID id = candidates.getFirst().id(), token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE notify.closure_deliveries SET state='SENDING', attempts=attempts+1,
                    lease_token=?, lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') WHERE id=?
                    """, token, settings.lease().toMillis(), id);
            var delivery = jdbc.query("SELECT * FROM notify.closure_deliveries WHERE id=?", DELIVERY, id).getFirst();
            int attemptInserted = jdbc.update("""
                    INSERT INTO notify.closure_smtp_attempts
                    (id, delivery_id, attempt_number, lease_token, state, document_sha256)
                    SELECT ?, ?, ?, ?, 'SENDING', sha256 FROM notify.closure_documents WHERE id=? AND state='READY'
                    """, token, id, delivery.attempts(), token, delivery.documentId());
            if (attemptInserted != 1) throw new IllegalStateException("DOCUMENT_NOT_READY");
            return Optional.of(delivery);
        });
    }

    /** Acceptance and historical audit commit atomically, after SMTP; rollback must never cause a resend. */
    public void finish(Delivery delivery, ClosureState result, String code) {
        tx.executeWithoutResult(status -> {
            boolean accepted = result == ClosureState.SMTP_ACCEPTED;
            String states = accepted ? "('SENDING','UNKNOWN')" : "('SENDING')";
            int updated = jdbc.update("UPDATE notify.closure_deliveries SET state=?, error_code=?, "
                    + "accepted_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE accepted_at END, "
                    + "next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') "
                    + "WHERE id=? AND lease_token=? AND state IN " + states,
                    result.name(), code, accepted, retryDelay(delivery.attempts()).toMillis(), delivery.id(), delivery.token());
            if (updated == 0) return;
            jdbc.update("""
                    UPDATE notify.closure_smtp_attempts SET state=?, error_code=?, finished_at=CURRENT_TIMESTAMP
                    WHERE delivery_id=? AND lease_token=?
                    """, result.name(), code, delivery.id(), delivery.token());
            if (accepted) {
                var p = email(delivery.envelope()).payload();
                jdbc.update("""
                        INSERT INTO notify.notification_delivery_audit
                        (production_id,event_id,notification_type,recipient,status,error_detail,created_at)
                        VALUES (?,?,'EMAIL',?,'ACCEPTED_BY_SMTP','DURABLE_CLOSURE',CURRENT_TIMESTAMP)
                        """, p.productionId(), email(delivery.envelope()).eventId(), p.recipientEmail());
            }
        });
    }

    public boolean ownsDelivery(Delivery delivery) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notify.closure_deliveries WHERE id=? AND lease_token=?
                AND state='SENDING' AND lease_until > CURRENT_TIMESTAMP)
                """, Boolean.class, delivery.id(), delivery.token()));
    }
    public Optional<Document> document(long productionId) {
        return jdbc.query("SELECT * FROM notify.closure_documents WHERE production_id=?", DOCUMENT, productionId).stream().findFirst();
    }
    public Optional<Document> document(UUID id) {
        return jdbc.query("SELECT * FROM notify.closure_documents WHERE id=?", DOCUMENT, id).stream().findFirst();
    }
    public MessageEnvelope<EmailProductionStatusPayload> email(String source) {
        return json.readValue(source, new TypeReference<MessageEnvelope<EmailProductionStatusPayload>>() {});
    }
    public MessageEnvelope<QuoteCommandPayload> quote(String source) {
        return json.readValue(source, new TypeReference<MessageEnvelope<QuoteCommandPayload>>() {});
    }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static void validate(MessageEnvelope<?> envelope, Long id, String organizer, String state) {
        if (id == null || id < 1 || organizer == null || organizer.isBlank() || organizer.length() > 255
                || !"CERRADO".equalsIgnoreCase(state) || !safeIdentifier(envelope.eventId())
                || !safeIdentifier(envelope.traceId()) || !safeIdentifier(envelope.correlationId())) {
            throw new IllegalArgumentException("INVALID_CLOSURE_COMMAND");
        }
    }
    private static boolean safeIdentifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 64
                && value.chars().noneMatch(Character::isISOControl);
    }
    public Duration retryDelay(int attempt) {
        long[] millis = {1000, 5000, 30000, 120000};
        long base = millis[Math.min(Math.max(attempt - 1, 0), millis.length - 1)];
        return Duration.ofMillis(java.util.concurrent.ThreadLocalRandom.current().nextLong(base * 8 / 10, base * 12 / 10 + 1));
    }
}
