-- Additive, Notify schema only. A production has one terminal CERRADO lifecycle.
CREATE TABLE closure_inbox (
    consumer VARCHAR(16) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    production_id BIGINT NOT NULL CHECK (production_id > 0),
    envelope_json TEXT NOT NULL,
    envelope_sha256 VARCHAR(64) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE closure_documents (
    id UUID PRIMARY KEY,
    production_id BIGINT NOT NULL UNIQUE CHECK (production_id > 0),
    organizer_id VARCHAR(255) NOT NULL,
    command_event_id VARCHAR(64) NOT NULL,
    envelope_json TEXT NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING','GENERATING','READY','RETRY_WAIT','FAILED')),
    storage_key VARCHAR(200),
    sha256 VARCHAR(64),
    size_bytes BIGINT CHECK (size_bytes > 0),
    mime VARCHAR(40) NOT NULL DEFAULT 'application/pdf' CHECK (mime = 'application/pdf'),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMP WITH TIME ZONE,
    CHECK (state <> 'READY' OR (storage_key IS NOT NULL AND sha256 IS NOT NULL AND size_bytes IS NOT NULL))
);

CREATE TABLE closure_deliveries (
    id UUID PRIMARY KEY,
    production_id BIGINT NOT NULL CHECK (production_id > 0),
    notification_kind VARCHAR(24) NOT NULL DEFAULT 'CLOSURE_EMAIL' CHECK (notification_kind = 'CLOSURE_EMAIL'),
    organizer_id VARCHAR(255) NOT NULL,
    command_event_id VARCHAR(64) NOT NULL,
    envelope_json TEXT NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('WAITING_DOCUMENT','READY','SENDING','RETRY_WAIT','SMTP_ACCEPTED','FAILED_DOCUMENT','FAILED_PERMANENT','UNKNOWN')),
    document_id UUID REFERENCES closure_documents(id),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    document_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (production_id, notification_kind),
    CHECK (state NOT IN ('READY','SENDING','SMTP_ACCEPTED') OR document_id IS NOT NULL)
);

CREATE TABLE closure_smtp_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES closure_deliveries(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    lease_token UUID NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('SENDING','SMTP_ACCEPTED','RETRY_WAIT','FAILED_DOCUMENT','FAILED_PERMANENT','UNKNOWN')),
    document_sha256 VARCHAR(64) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    UNIQUE (delivery_id, attempt_number)
);

CREATE INDEX idx_closure_documents_work ON closure_documents(state, next_attempt_at);
CREATE INDEX idx_closure_deliveries_work ON closure_deliveries(state, next_attempt_at);
