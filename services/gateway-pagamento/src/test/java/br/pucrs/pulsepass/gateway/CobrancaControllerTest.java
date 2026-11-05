package br.pucrs.pulsepass.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CobrancaControllerTest {

  @Autowired MockMvc mvc;

  private void configurar(String json) throws Exception {
    mvc.perform(put("/admin/config").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isOk());
  }

  private org.springframework.test.web.servlet.ResultActions cobrar(String id) throws Exception {
    return mvc.perform(
        post("/cobrancas")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pagamentoId\":\"" + id + "\",\"valor\":10.00}"));
  }

  @Test
  void aprovaPorPadraoEDeduplicaPorPagamentoId() throws Exception {
    configurar("{\"modo\":\"APROVAR\",\"atrasoMs\":0,\"falhar\":false}");
    cobrar("p1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROVADO"));
    cobrar("p1").andExpect(status().isOk()).andExpect(jsonPath("$.referencia").value("ref-p1"));
    mvc.perform(get("/admin/chamadas"))
        .andExpect(jsonPath("$.unicos").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
  }

  @Test
  void recusaQuandoConfigurado() throws Exception {
    configurar("{\"modo\":\"RECUSAR\",\"atrasoMs\":0,\"falhar\":false}");
    cobrar("p2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECUSADO"))
        .andExpect(jsonPath("$.motivo").value("SALDO_INSUFICIENTE"));
  }

  @Test
  void falhaComErro500ParaTestarResiliencia() throws Exception {
    configurar("{\"modo\":\"APROVAR\",\"atrasoMs\":0,\"falhar\":true}");
    cobrar("p3").andExpect(status().isInternalServerError());
    configurar("{\"modo\":\"APROVAR\",\"atrasoMs\":0,\"falhar\":false}");
  }
}
