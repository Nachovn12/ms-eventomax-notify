package cl.duoc.eventomax.notify.controller;

import cl.duoc.eventomax.notify.storage.DocumentStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class NotifyQuoteControllerTest {

    private MockMvc mockMvc;

    @Mock
    private DocumentStorageService documentStorageService;

    @BeforeEach
    void setUp() {
        NotifyQuoteController controller = new NotifyQuoteController(documentStorageService, "./data/quotes");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Retorna 200 OK con Content-Type application/pdf y descarga del archivo")
    void getQuotePdf_foundInStorage() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 mock content".getBytes(StandardCharsets.ISO_8859_1);
        String filename = "quote-production-1-latest.pdf";

        when(documentStorageService.documentExists("quotes", filename)).thenReturn(true);
        when(documentStorageService.retrieveDocument("quotes", filename)).thenReturn(pdfBytes);

        mockMvc.perform(get("/api/notify/quotes/1/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quote-production-1.pdf\""))
                .andExpect(content().bytes(pdfBytes));
    }

    @Test
    @DisplayName("Retorna 404 Not Found si no existe el acta PDF")
    void getQuotePdf_notFound() throws Exception {
        String filename = "quote-production-999-latest.pdf";
        when(documentStorageService.documentExists("quotes", filename)).thenReturn(false);

        mockMvc.perform(get("/api/notify/quotes/999/pdf"))
                .andExpect(status().isNotFound());
    }
}

