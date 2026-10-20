package cl.duoc.eventomax.notify.controller;

import cl.duoc.eventomax.notify.model.CrewTicket;
import cl.duoc.eventomax.notify.repository.CrewTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class NotifyTicketControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CrewTicketRepository crewTicketRepository;

    @BeforeEach
    void setUp() {
        NotifyTicketController controller = new NotifyTicketController(crewTicketRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Retorna 200 OK con el ticket de cuadrilla para una producción existente")
    void getTicketByProductionId_found() throws Exception {
        CrewTicket ticket = new CrewTicket(
                1L,
                "evt-123",
                "EP2-DEMO Festival",
                LocalDateTime.of(2026, 11, 20, 18, 0),
                "Santiago",
                "EN_MONTAJE",
                "Instrucciones operativas"
        );

        when(crewTicketRepository.findTopByProductionIdOrderByIdDesc(1L))
                .thenReturn(Optional.of(ticket));

        mockMvc.perform(get("/api/notify/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productionId").value(1))
                .andExpect(jsonPath("$.productionName").value("EP2-DEMO Festival"))
                .andExpect(jsonPath("$.status").value("EN_MONTAJE"))
                .andExpect(jsonPath("$.location").value("Santiago"));
    }

    @Test
    @DisplayName("Retorna 404 Not Found si no existe ticket para la producción")
    void getTicketByProductionId_notFound() throws Exception {
        when(crewTicketRepository.findTopByProductionIdOrderByIdDesc(999L))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/notify/tickets/999"))
                .andExpect(status().isNotFound());
    }
}

