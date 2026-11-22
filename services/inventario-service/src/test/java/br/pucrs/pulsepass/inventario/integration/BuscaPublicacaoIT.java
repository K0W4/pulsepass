package br.pucrs.pulsepass.inventario.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucrs.pulsepass.inventario.api.Dtos.EventoRequest;
import br.pucrs.pulsepass.inventario.service.EventoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** US13 (busca por nome, local e data) e US10 (publicar e despublicar). */
@SpringBootTest
@AutoConfigureMockMvc
class BuscaPublicacaoIT extends ContainersBase {

  static final String DONO = "org-busca";

  @Autowired MockMvc mvc;
  @Autowired EventoService eventos;
  @Autowired ObjectMapper mapper;

  private static RequestPostProcessor organizador(String id) {
    return jwt()
        .jwt(j -> j.subject(id))
        .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZADOR"));
  }

  private JsonNode json(String caminho) throws Exception {
    return mapper.readTree(
        mvc.perform(get(caminho))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private UUID criar(String nome, String local, Instant quando, Boolean publicar) {
    return eventos.criar(DONO, new EventoRequest(nome, local, quando, null, publicar));
  }

  @Test
  void buscaPorNomeLocalEData() throws Exception {
    String marca = "Marca" + UUID.randomUUID().toString().substring(0, 8);
    Instant quando = Instant.now().plus(45, ChronoUnit.DAYS);
    criar("Show " + marca, "Estádio Beira-Rio, Porto Alegre", quando, null);
    criar(
        "Outro " + marca + " Rock",
        "Arena do Grêmio, Porto Alegre",
        quando.plus(3, ChronoUnit.DAYS),
        null);

    assertThat(json("/eventos?q=" + marca.toLowerCase())).hasSize(2);
    assertThat(json("/eventos?q=" + marca + "&local=beira")).hasSize(1);
    assertThat(json("/eventos?q=" + marca + "&local=gremio")).hasSize(1); // sem acento
    assertThat(json("/eventos?q=" + marca + "&local=curitiba")).isEmpty();

    LocalDate dia = quando.atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate();
    assertThat(json("/eventos?q=" + marca + "&de=" + dia + "&ate=" + dia)).hasSize(1);
    assertThat(json("/eventos?q=" + marca + "&de=" + dia)).hasSize(2);
    assertThat(json("/eventos?q=" + marca + "&ate=" + dia.minusDays(1))).isEmpty();
  }

  @Test
  void dataInvalidaDa400() throws Exception {
    mvc.perform(get("/eventos?de=amanha")).andExpect(status().isBadRequest());
  }

  @Test
  void rascunhoNaoApareceNoCatalogoMasApareceEmMeus() throws Exception {
    String marca = "Rascunho" + UUID.randomUUID().toString().substring(0, 8);
    UUID id = criar(marca, "Local", Instant.now().plus(10, ChronoUnit.DAYS), false);

    assertThat(json("/eventos?q=" + marca)).isEmpty();
    mvc.perform(get("/eventos/" + id)).andExpect(status().isNotFound());

    var meus =
        mapper.readTree(
            mvc.perform(get("/eventos/meus").with(organizador(DONO)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    boolean achou = false;
    for (JsonNode e : meus) {
      if (e.get("id").asText().equals(id.toString())) {
        achou = true;
        assertThat(e.get("status").asText()).isEqualTo("RASCUNHO");
      }
    }
    assertThat(achou).isTrue();
  }

  @Test
  void publicarETirarDoArComInvalidacaoDoCache() throws Exception {
    String marca = "Pub" + UUID.randomUUID().toString().substring(0, 8);
    UUID id = criar(marca, "Local", Instant.now().plus(10, ChronoUnit.DAYS), false);
    assertThat(json("/eventos?q=" + marca)).isEmpty(); // e já deixa o catálogo em cache

    mvc.perform(post("/eventos/" + id + "/publicar").with(organizador(DONO)))
        .andExpect(status().isNoContent());
    assertThat(json("/eventos?q=" + marca)).hasSize(1);

    mvc.perform(post("/eventos/" + id + "/despublicar").with(organizador(DONO)))
        .andExpect(status().isNoContent());
    assertThat(json("/eventos?q=" + marca)).isEmpty();
    mvc.perform(get("/eventos/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void naoPublicaEventoDeOutroNemSemSerOrganizador() throws Exception {
    UUID id = criar("Alheio", "Local", Instant.now().plus(10, ChronoUnit.DAYS), false);

    mvc.perform(post("/eventos/" + id + "/publicar").with(organizador("outro-organizador")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/eventos/" + id + "/publicar")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"))))
        .andExpect(status().isForbidden());
    mvc.perform(post("/eventos/" + id + "/publicar")).andExpect(status().isUnauthorized());
    mvc.perform(get("/eventos/meus")).andExpect(status().isUnauthorized());
  }

  @Test
  void naoReservaEventoDespublicado() throws Exception {
    UUID id = criar("Fechado", "Local", Instant.now().plus(10, ChronoUnit.DAYS), false);
    String corpo =
        "{\"eventoId\":\""
            + id
            + "\",\"itens\":[{\"loteId\":\""
            + UUID.randomUUID()
            + "\",\"quantidade\":1}]}";
    mvc.perform(
            post("/reservas")
                .with(
                    jwt()
                        .jwt(j -> j.subject("c"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CLIENTE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isNotFound());
  }
}
