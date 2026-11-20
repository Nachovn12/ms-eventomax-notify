package cl.duoc.eventomax.notify.closure;

import cl.duoc.eventomax.notify.service.QuoteNotificationService;
import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import cl.duoc.eventomax.notify.storage.DocumentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eventomax.notify.closure.enabled", havingValue = "true", matchIfMissing = true)
public class ClosureWorker {
    private static final Logger log = LoggerFactory.getLogger(ClosureWorker.class);
    public static final String CATEGORY = "closures";
    private final ClosureStore store;
    private final QuoteNotificationService generator;
    private final DocumentStorageService storage;
    private final ObjectProvider<ClosureEmailTransport> transport;
    private final ClosureSettings settings;

    public ClosureWorker(ClosureStore store, QuoteNotificationService generator, DocumentStorageService storage,
                         ObjectProvider<ClosureEmailTransport> transport, ClosureSettings settings) {
        this.store = store;
        this.generator = generator;
        this.storage = storage;
        this.transport = transport;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${eventomax.notify.closure.poll-ms:1000}")
    public void tick() {
        try {
            store.reconcile();
            if (settings.generationEnabled()) generateOne();
            store.reconcile();
            if (settings.dispatchEnabled()) sendOne();
        } catch (RuntimeException ex) {
            // Never dump envelope, SMTP exception text, addresses or configuration.
            log.error("CLOSURE_WORKER_CYCLE_FAILED type={}", ex.getClass().getSimpleName());
        }
    }

    public void generateOne() {
        if (!settings.generationEnabled()) return;
        var claimed = store.claimDocument();
        if (claimed.isEmpty()) return;
        var doc = claimed.get();
        try {
            byte[] bytes = null;
            if (doc.key() != null && doc.sha() != null && doc.size() != null) {
                try {
                    bytes = storage.retrieveDocumentExact(CATEGORY, doc.key());
                    ClosurePdf.verify(bytes, doc.productionId(), doc.id(), doc.sha(), doc.size(), settings.maxPdfBytes());
                } catch (DocumentNotFoundException ex) { /* No published object: prepare another unique key. */ }
            }
            if (bytes == null) {
                bytes = generator.generateDurableClosure(store.quote(doc.envelope()), doc.id());
                String hash = ClosurePdf.sha256(bytes);
                ClosurePdf.verify(bytes, doc.productionId(), doc.id(), hash, bytes.length, settings.maxPdfBytes());
                String key = "closure-" + doc.id() + "-" + doc.token() + ".pdf";
                if (!store.prepared(doc, key, hash, bytes.length)) return; // Fenced stale generator.
                storage.storeDocumentIfAbsent(CATEGORY, key, bytes, "application/pdf");
                byte[] stored = storage.retrieveDocumentExact(CATEGORY, key);
                ClosurePdf.verify(stored, doc.productionId(), doc.id(), hash, bytes.length, settings.maxPdfBytes());
            }
            if (store.documentReady(doc)) log.info("CLOSURE_DOCUMENT_READY productionId={}, documentId={}", doc.productionId(), doc.id());
        } catch (IllegalArgumentException | ClosurePreflightException ex) {
            store.documentFailed(doc, "DOCUMENT_INVALID", true);
        } catch (RuntimeException ex) {
            store.documentFailed(doc, "DOCUMENT_STORAGE_OR_GENERATION", false);
        }
    }

    public void sendOne() {
        if (!settings.dispatchEnabled()) return;
        // Missing/disabled SMTP provider never consumes a claim and never reports a simulated send as accepted.
        var sender = transport.getIfAvailable();
        if (sender == null) return;
        var claimed = store.claimDelivery();
        if (claimed.isEmpty()) return;
        var delivery = claimed.get();
        boolean smtpInvoked = false;
        boolean smtpReturned = false;
        try {
            var doc = store.document(delivery.documentId()).orElseThrow(() -> new IllegalArgumentException("DOCUMENT_MISSING"));
            if (!"READY".equals(doc.state()) || doc.productionId() != delivery.productionId()) {
                throw new IllegalArgumentException("DOCUMENT_IDENTITY");
            }
            byte[] bytes = storage.retrieveDocumentExact(CATEGORY, doc.key());
            ClosurePdf.verify(bytes, delivery.productionId(), doc.id(), doc.sha(), doc.size(), settings.maxPdfBytes());
            var envelope = store.email(delivery.envelope());
            if (!RecipientCheck.valid(envelope.payload().recipientEmail())) {
                store.finish(delivery, ClosureState.FAILED_PERMANENT, "NO_AUTHORIZED_CONTACT");
                return;
            }
            if (!store.ownsDelivery(delivery)) return;
            smtpInvoked = true;
            sender.sendClosure(envelope, delivery.id(), doc.id(), bytes, doc.sha());
            smtpReturned = true;
            // If this commit fails, the catch path records UNKNOWN independently; lease recovery covers DB outage.
            store.finish(delivery, ClosureState.SMTP_ACCEPTED, null);
        } catch (ClosurePreflightException ex) {
            finishSafely(delivery, ClosureState.FAILED_PERMANENT, "MIME_OR_CONTACT_INVALID");
        } catch (RuntimeException ex) {
            var outcome = smtpReturned ? ClosureState.UNKNOWN
                    : smtpInvoked ? SmtpOutcome.classify(ex) : ClosureState.FAILED_DOCUMENT;
            if (outcome == ClosureState.RETRY_WAIT && delivery.attempts() >= settings.maxAttempts()) outcome = ClosureState.FAILED_PERMANENT;
            finishSafely(delivery, outcome, smtpInvoked ? "SMTP_OR_ACCEPTANCE_AUDIT_FAILED" : "DOCUMENT_PREFLIGHT_FAILED");
        }
    }

    private void finishSafely(ClosureStore.Delivery delivery, ClosureState state, String code) {
        try { store.finish(delivery, state, code); }
        catch (RuntimeException ex) {
            // Keep SENDING if storage is down; reconciliation will make it UNKNOWN, not READY.
            log.error("CLOSURE_RESULT_PERSISTENCE_FAILED deliveryId={}", delivery.id());
        }
    }
}
