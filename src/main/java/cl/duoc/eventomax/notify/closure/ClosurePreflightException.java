package cl.duoc.eventomax.notify.closure;

/** A failure proven to occur before invoking SMTP. Contains a sanitized code only. */
public class ClosurePreflightException extends RuntimeException {
    public ClosurePreflightException(String code) { super(code); }
}
