package cl.duoc.eventomax.notify.email.presentation;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class EmailTemplateRendererTest {
    EmailProductionStatusPayload payload(String status, String date) {
        return new EmailProductionStatusPayload(145L,"internal-organizer","contact@example.invalid",
                "Jornada <Corporativa> & Cultura",status,date,"Salón \"Sur\"");
    }
    @ParameterizedTest
    @CsvSource({"CONFIRMADO,Tu producción está confirmada,Producción #145 confirmada,#EFF6FF",
            "EN_MONTAJE,El montaje está en curso,Montaje iniciado · Producción #145,#FFFBEB",
            "CERRADO,Tu producción ha sido cerrada,Producción #145 cerrada,#ECFDF5"})
    void variantsShareFactsWithoutTechnicalIdentities(String state,String title,String subject,String color) {
        var m=EmailPresentationModel.from(payload(state,"2027-06-01T12:00:00"),state.equals("CERRADO"),true,Optional.empty());
        var result=new EmailTemplateRenderer().render(m);
        assertThat(result.subject()).isEqualTo("EventoMax · "+subject);
        assertThat(result.html()).contains(title,color,"role=\"presentation\"","<!--[if mso]>","max-width:600px",
                "Jornada &lt;Corporativa&gt; &amp; Cultura","Salón &quot;Sur&quot;","cid:eventomax-logo");
        assertThat(result.plain()).contains(title,m.name(),m.location(),m.scheduledAt(),"acceso habitual");
        assertThat(result.html()+result.plain()).doesNotContain("internal-organizer","contact@example.invalid","href=","http://","Ticket","firmable","Trace:");
        assertThat(result.html().contains("PDF adjunto")).isEqualTo(state.equals("CERRADO"));
        assertThat(result.plain().contains("PDF adjunto")).isEqualTo(state.equals("CERRADO"));
    }
    @ParameterizedTest @ValueSource(strings={"CANCELADO","EN_EJECUCION","DESCONOCIDO",""})
    void otherStatesUseNeutralFallback(String state) {
        var result=new EmailTemplateRenderer().render(EmailPresentationModel.from(payload(state,null),false,false,Optional.empty()));
        assertThat(result.html()).contains("Actualizado","actualización").doesNotContain("cid:","PDF","cargos","reservas");
        assertThat(result.plain()).contains("Fecha no disponible");
    }
    @Test void closureCannotClaimAnUnverifiedAttachment() {
        assertThatThrownBy(()->EmailPresentationModel.from(payload("CERRADO",null),false,true,Optional.empty()))
                .hasMessage("VERIFIED_CLOSURE_REQUIRED");
        assertThatThrownBy(()->EmailPresentationModel.from(payload("CONFIRMADO",null),true,true,Optional.empty()))
                .hasMessage("VERIFIED_CLOSURE_REQUIRED");
    }
    @ParameterizedTest @CsvSource({
            "2027-01-15T02:00:00Z,14 ene 2027 · 23:00,UTC-03:00",
            "2027-07-15T02:00:00Z,14 jul 2027 · 22:00,UTC-04:00",
            "2027-07-15T04:00:00+02:00,14 jul 2027 · 22:00,UTC-04:00"})
    void convertsActualInstantToSantiago(String input,String local,String offset) {
        assertThat(EmailPresentationModel.date(input)).contains(local,offset,"America/Santiago");
    }
    @Test void localDateKeepsItsWallClockTime() {
        assertThat(EmailPresentationModel.date("2027-07-15T02:00:00")).contains("15 jul 2027 · 02:00","zona no informada")
                .doesNotContain("UTC","America/Santiago");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"broken"," ","2027-02-30T25:00"})
    void absentOrInvalidDatesAreExplicit(String value) {
        assertThat(EmailPresentationModel.date(value)).isEqualTo("Fecha no disponible");
    }
    @Test void handlesMissingAndVeryLongDataWithoutInjectingMarkup() {
        String longName="<img src=x onerror=alert(1)>"+"ñ".repeat(2000);
        var p=new EmailProductionStatusPayload(145L,"hidden","contact@example.invalid",longName,"CONFIRMADO",null,null);
        var result=new EmailTemplateRenderer().render(EmailPresentationModel.from(p,false,false,Optional.empty()));
        assertThat(result.html()).contains("&lt;img","ñ".repeat(2000),"Recinto no disponible","word-wrap:break-word")
                .doesNotContain("<img","\uFFFD");
        assertThat(result.plain()).contains(longName,"Recinto no disponible");
    }
    @Test void approvedLinkIsIdenticalInBothAlternatives() {
        String url=new PortalLinkPolicy().link("https://portal.example.invalid/productions","portal.example.invalid",true,145L).orElseThrow();
        var result=new EmailTemplateRenderer().render(EmailPresentationModel.from(payload("CERRADO",null),true,false,Optional.of(url)));
        assertThat(result.html()).contains("href=\""+url+"\"","Ver producción y acta");
        assertThat(result.plain()).contains("Ver producción y acta: "+url);
    }
}
