package cl.duoc.eventomax.notify.email.presentation;

/** Pure renderer: no I/O, SMTP, clocks, database or business-state transitions. */
public final class EmailTemplateRenderer {
    public record RenderedEmail(String subject, String plain, String html) { }
    public RenderedEmail render(EmailPresentationModel m) {
        var s = m.status();
        String document = m.verifiedPdf() ? "Acta de cierre empresarial · PDF adjunto" : "";
        String message = s.message() + (m.verifiedPdf()
                ? " Encontrarás el acta de cierre empresarial adjunta a este correo." : "");
        String cta = m.verifiedPdf() ? "Ver producción y acta" : "Consultar producción";
        String access = m.portalLink().map(url -> cta + ": " + url)
                .orElse("Puedes consultar el estado desde tu acceso habitual a EventoMax.");
        String reference = "Producción #" + m.folio();
        String plain = "EventoMax · " + reference + "\nEstado: " + s.label() + "\n" + s.title()
                + "\n\n" + message + "\n\nEvento: " + m.name() + "\nFecha y hora: " + m.scheduledAt()
                + "\nRecinto: " + m.location() + "\n" + document + "\n\n" + access
                + "\n\nEventoMax · Gestión y producción de eventos\nMensaje automático · " + reference;
        String logo = m.logoAvailable()
                ? "<img src=\"cid:eventomax-logo\" alt=\"EventoMax\" width=\"156\" height=\"52\" style=\"display:block;border:0;background-color:#FFFFFF;\">" : "";
        String button = m.portalLink().map(url -> "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td bgcolor=\"#1D4ED8\" style=\"background-color:#1D4ED8;padding:14px 20px;\">"
                + "<a href=\"" + escape(url) + "\" style=\"color:#FFFFFF;font-size:16px;line-height:24px;font-weight:bold;text-decoration:none;\">"
                + cta + "</a></td></tr></table>").orElse("<p style=\"margin:0;\">" + escape(access) + "</p>");
        String html = """
                <!doctype html><html lang="es-CL"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background-color:#F1F5F9;color:#0F172A;font-family:Arial,'Segoe UI',sans-serif;font-size:16px;line-height:1.5;">
                <div style="display:none!important;visibility:hidden;mso-hide:all;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;">%s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" bgcolor="#F1F5F9"><tr><td align="center" style="padding:24px 12px;">
                <!--[if mso]><table role="presentation" width="600" cellpadding="0" cellspacing="0"><tr><td><![endif]-->
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" bgcolor="#FFFFFF" style="width:100%%;max-width:600px;table-layout:fixed;border:1px solid #E2E8F0;">
                <tr><td bgcolor="#10223B" style="padding:24px;background-color:#10223B;color:#FFFFFF;">
                %s<p style="margin:12px 0 4px;font-size:20px;font-weight:bold;">EventoMax</p>
                <p style="margin:0;font-size:14px;">%s</p></td></tr>
                <tr><td style="padding:24px;background-color:%s;">
                <p style="margin:0 0 12px;color:%s;font-size:14px;font-weight:bold;">Estado: %s</p>
                <h1 style="margin:0 0 16px;font-size:24px;line-height:1.3;color:#0F172A;">%s</h1>
                <p style="margin:0;font-size:16px;line-height:1.5;">%s</p></td></tr>
                <tr><td style="padding:24px;word-wrap:break-word;overflow-wrap:anywhere;word-break:break-word;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="width:100%%;table-layout:fixed;">%s</table>
                %s</td></tr><tr><td style="padding:0 24px 24px;">%s</td></tr>
                <tr><td style="padding:24px;border-top:1px solid #E2E8F0;color:#64748B;font-size:14px;line-height:1.5;">
                <p style="margin:0 0 8px;">EventoMax · Gestión y producción de eventos</p>
                <p style="margin:0;">Mensaje automático · %s</p></td></tr></table>
                <!--[if mso]></td></tr></table><![endif]-->
                </td></tr></table></body></html>
                """.formatted(escape(s.title() + (m.verifiedPdf() ? ". Acta PDF adjunta." : ".")), logo,
                reference, s.background(), s.accent(), s.label(), s.title(), escape(message),
                row("Folio", reference) + row("Evento", m.name()) + row("Fecha y hora", m.scheduledAt())
                        + row("Recinto", m.location()) + row("Estado de producción", s.label()),
                m.verifiedPdf() ? "<p style=\"padding:16px;background-color:#ECFDF5;color:#047857;font-weight:bold;\">" + document + "</p>" : "", button, reference);
        return new RenderedEmail("EventoMax · " + s.subject().formatted(m.folio()), plain, html);
    }
    private String row(String label, String value) {
        return "<tr><td style=\"padding:0 0 16px;\"><p style=\"margin:0 0 4px;color:#64748B;font-size:14px;\">"
                + label + "</p><p style=\"margin:0;font-size:16px;line-height:1.5;word-wrap:break-word;word-break:break-word;\">"
                + escape(value) + "</p></td></tr>";
    }
    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
