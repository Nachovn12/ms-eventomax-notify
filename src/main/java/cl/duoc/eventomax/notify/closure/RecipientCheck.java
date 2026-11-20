package cl.duoc.eventomax.notify.closure;

import jakarta.mail.internet.InternetAddress;

/** Only the trusted command's authorized contact is accepted. No fallback or organizerId conversion. */
public final class RecipientCheck {
    private RecipientCheck() {}
    public static boolean valid(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.contains("\r") || value.contains("\n")) return false;
        try {
            var addresses = InternetAddress.parse(value.trim(), true);
            if (addresses.length != 1 || addresses[0].isGroup()) return false;
            addresses[0].validate();
            return addresses[0].getAddress().contains("@");
        } catch (Exception ex) { return false; }
    }
}
