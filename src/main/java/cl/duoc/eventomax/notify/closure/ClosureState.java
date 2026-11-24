package cl.duoc.eventomax.notify.closure;

public enum ClosureState {
    WAITING_DOCUMENT, READY, SENDING, RETRY_WAIT, SMTP_ACCEPTED,
    FAILED_DOCUMENT, FAILED_PERMANENT, UNKNOWN
}
