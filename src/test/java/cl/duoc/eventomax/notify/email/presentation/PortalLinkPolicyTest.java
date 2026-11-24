package cl.duoc.eventomax.notify.email.presentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PortalLinkPolicyTest {
    final PortalLinkPolicy policy=new PortalLinkPolicy();
    @Test void requiresSeparateE2eApprovalEvenForAllowlistedHttps() {
        assertThat(policy.link("https://portal.example.invalid/productions","portal.example.invalid",false,145L)).isEmpty();
        assertThat(policy.link("https://portal.example.invalid/productions","portal.example.invalid",true,145L))
                .contains("https://portal.example.invalid/productions?productionId=145");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={
            "http://portal.example.invalid/productions","https://localhost/productions",
            "https://foo.localhost/productions","https://127.0.0.1/productions","https://2130706433/productions",
            "https://[::1]/productions","https://0x7f000001/productions","https://portal.example.invalid.evil/productions",
            "https://user:pass@portal.example.invalid/productions","https://portal.example.invalid:444/productions",
            "https://portal.example.invalid/productions?token=secret","https://portal.example.invalid/productions#token",
            "https://portal.example.invalid/%70roductions","https://portal.example.invalid/x/../productions",
            "https://portal.example.invalid./productions","https://portal.example.invalid\\@evil/productions",
            "https://portal.example.invalid/productions\r\nInjected:yes","javascript:alert(1)","//portal.example.invalid/productions"})
    void rejectsManipulatedUrls(String url) {
        assertThat(policy.link(url,"portal.example.invalid,localhost,foo.localhost,127.0.0.1,2130706433,0x7f000001",true,145L)).isEmpty();
    }
    @Test void requiresExactHostAndPositiveFolio() {
        assertThat(policy.link("https://portal.example.invalid/productions","",true,145L)).isEmpty();
        assertThat(policy.link("https://portal.example.invalid/productions","example.invalid",true,145L)).isEmpty();
        for(Long id:new Long[]{null,0L,-1L})
            assertThat(policy.link("https://portal.example.invalid/productions","portal.example.invalid",true,id)).isEmpty();
    }
}
