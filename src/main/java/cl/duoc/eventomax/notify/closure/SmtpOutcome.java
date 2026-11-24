package cl.duoc.eventomax.notify.closure;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import jakarta.mail.SendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import java.net.ConnectException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Conservative: only an explicit negative SMTP response or refused connection is unambiguous. */
public final class SmtpOutcome {
    private SmtpOutcome() {}
    public static ClosureState classify(Throwable error) {
        if (error instanceof MailAuthenticationException) return ClosureState.FAILED_PERMANENT;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return classify(error, seen);
    }
    private static ClosureState classify(Throwable error, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) return ClosureState.UNKNOWN;
        if (error instanceof SendFailedException s && s.getValidSentAddresses() != null
                && s.getValidSentAddresses().length > 0) return ClosureState.UNKNOWN;
        if (error instanceof MailSendException m && m.getMessageExceptions().length > 1) return ClosureState.UNKNOWN;
        int code = error instanceof SMTPSendFailedException s ? s.getReturnCode()
                : error instanceof SMTPAddressFailedException a ? a.getReturnCode() : 0;
        if (code >= 500 && code < 600) return ClosureState.FAILED_PERMANENT;
        if (code >= 400 && code < 500) return ClosureState.RETRY_WAIT;
        if (error instanceof ConnectException) return ClosureState.RETRY_WAIT;
        if (error instanceof MailSendException m && m.getMessageExceptions().length == 1) {
            return classify(m.getMessageExceptions()[0], seen);
        }
        if (error instanceof jakarta.mail.MessagingException m && m.getNextException() != null) {
            var nested = classify(m.getNextException(), seen);
            if (nested != ClosureState.UNKNOWN) return nested;
        }
        return classify(error.getCause(), seen);
    }
}
