package cl.duoc.eventomax.notify.email.presentation;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Configuration is trusted; payload data cannot select a host or authorize activation. */
public final class PortalLinkPolicy {
    public Optional<String> link(String base, String allowedHosts, boolean e2eApproved, Long productionId) {
        if (!e2eApproved || productionId == null || productionId < 1 || base == null) return Optional.empty();
        try {
            URI uri = URI.create(base);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || !host.matches("[A-Za-z0-9.-]+")
                    || host.endsWith(".") || host.contains("..") || !host.contains(".")
                    || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                    || host.matches("[0-9.]+") || host.matches("(?i)0x[0-9a-f]+")
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"/productions".equals(uri.getRawPath())) return Optional.empty();
            boolean allowed = Arrays.stream((allowedHosts == null ? "" : allowedHosts).split(","))
                    .map(String::trim).anyMatch(host::equalsIgnoreCase);
            return allowed ? Optional.of(uri.toASCIIString() + "?productionId=" + productionId) : Optional.empty();
        } catch (IllegalArgumentException ex) { return Optional.empty(); }
    }
}
