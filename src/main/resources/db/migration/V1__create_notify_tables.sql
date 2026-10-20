-- V1__create_notify_tables.sql
-- Persistencia duradera de idempotencia, tickets de cuadrilla y auditoría de entregas en ms-eventomax-notify

CREATE TABLE processed_events (
    event_id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    trace_id VARCHAR(64),
    correlation_id VARCHAR(64)
);

CREATE TABLE crew_tickets (
    id BIGSERIAL PRIMARY KEY,
    production_id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    production_name VARCHAR(150) NOT NULL,
    scheduled_at TIMESTAMP NOT NULL,
    location VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    instructions TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_crew_tickets_production_id ON crew_tickets(production_id);

CREATE TABLE notification_delivery_audit (
    id BIGSERIAL PRIMARY KEY,
    production_id BIGINT,
    event_id VARCHAR(64) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    recipient VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    error_detail TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_notify_audit_event_id ON notification_delivery_audit(event_id);
CREATE INDEX idx_notify_audit_production_id ON notification_delivery_audit(production_id);

