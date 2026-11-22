package br.pucrs.pulsepass.inventario.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucrs.pulsepass.inventario.service.EventoService;
import br.pucrs.pulsepass.inventario.service.FilaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** US18 e RNF23: fila virtual por ordem de chegada, admissão por taxa e bloqueio da reserva. */
@SpringBootTest
@AutoConfigureMockMvc
class FilaVirtualIT extends ContainersBase {

  @Autowired MockMvc mvc;
  @Autowired EventoService eventos;
  @Autowired FilaService fila;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private static RequestPostProcessor cliente(String id) {
    return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));
  }

  private static RequestPostProcessor organizador(String id) {
    return jwt()
        .jwt(j -> j.subject(id))
        .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZADOR"));
  }

  private void ligar(UUID evento, boolean ativa) throws Exception {
    mvc.perform(
            put("/eventos/" + evento + "/fila-ativa")
                .with(organizador(Fixtures.ORGANIZADOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ativa\":" + ativa + "}"))
        .andExpect(status().isNoContent());
  }

  private String reserva(UUID evento, UUID lote) {
    return "{\"eventoId\":\""
        + evento
        + "\",\"itens\":[{\"loteId\":\""
        + lote
        + "\",\"quantidade\":1}]}";
  }

  private String situacaoDe(UUID evento, String cliente) throws Exception {
    return mapper
        .readTree(
            mvc.perform(get("/eventos/" + evento + "/fila").with(cliente(cliente)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("status")
        .asText();
  }

  @Test
  void semFilaLigadaTudoContinuaComoAntes() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "F1");
    UUID pista = Fixtures.lotePista(eventos, f.eventoId(), 5);

    mvc.perform(
            post("/reservas")
                .with(cliente("c-livre"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserva(f.eventoId(), pista)))
        .andExpect(status().isCreated());
    mvc.perform(post("/eventos/" + f.eventoId() + "/fila").with(cliente("c-livre")))
        .andExpect(status().isConflict()); // não há fila para entrar
  }

  @Test
  void comFilaLigadaSoReservaQuemFoiAdmitidoEAdmissaoVale1Reserva() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "F2");
    UUID pista = Fixtures.lotePista(eventos, f.eventoId(), 5);
    ligar(f.eventoId(), true);

    // sem admissão: 403 FILA_VIRTUAL e nada é reservado
    mvc.perform(
            post("/reservas")
                .with(cliente("c-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserva(f.eventoId(), pista)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.motivo").value("FILA_VIRTUAL"));
    assertThat(
            jdbc.queryForObject(
                "select qtd_reservada from lote where id = ?", Integer.class, pista))
        .isZero();

    mvc.perform(post("/eventos/" + f.eventoId() + "/fila").with(cliente("c-a")))
        .andExpect(status().isOk());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> situacaoDe(f.eventoId(), "c-a").equals("ADMITIDO"));

    mvc.perform(
            post("/reservas")
                .with(cliente("c-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserva(f.eventoId(), pista)))
        .andExpect(status().isCreated());
    // a admissão foi consumida: uma segunda reserva volta para a fila
    mvc.perform(
            post("/reservas")
                .with(cliente("c-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserva(f.eventoId(), pista)))
        .andExpect(status().isForbidden());
    assertThat(situacaoDe(f.eventoId(), "c-a")).isEqualTo("FORA");
  }

  @Test
  void admiteEmOrdemDeChegadaEPorTaxa() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "F3");
    ligar(f.eventoId(), true);
    // a taxa de teste é de 2 admissões por segundo (ContainersBase)
    List<String> ordem = List.of("c1", "c2", "c3", "c4", "c5", "c6");
    for (String c : ordem) {
      mvc.perform(post("/eventos/" + f.eventoId() + "/fila").with(cliente(c)))
          .andExpect(status().isOk());
    }
    mvc.perform(get("/eventos/" + f.eventoId() + "/fila").with(cliente("c6")))
        .andExpect(jsonPath("$.status").value("AGUARDANDO"))
        .andExpect(jsonPath("$.posicao").value(6));

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> situacaoDe(f.eventoId(), "c1").equals("ADMITIDO"));
    // a rodada admitiu só os dois primeiros: quem chegou depois ainda espera, com a posição
    // reduzida
    assertThat(situacaoDe(f.eventoId(), "c2")).isEqualTo("ADMITIDO");
    mvc.perform(get("/eventos/" + f.eventoId() + "/fila").with(cliente("c6")))
        .andExpect(jsonPath("$.status").value("AGUARDANDO"))
        .andExpect(jsonPath("$.posicao").value(org.hamcrest.Matchers.lessThanOrEqualTo(4)));
    // com o tempo todos entram, sem ninguém furar a fila
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> situacaoDe(f.eventoId(), "c6").equals("ADMITIDO"));
  }

  @Test
  void entrarDeNovoMantemAPosicao() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "F4");
    ligar(f.eventoId(), true);
    // o worker admite 2 por rodada (p1 e p2); a pontuação do p4 prova que insistir não muda a vez
    for (String c : List.of("p1", "p2", "p3", "p4")) {
      fila.entrar(f.eventoId(), c);
    }
    String chave = "fila:" + f.eventoId();
    Double antes = redis.opsForZSet().score(chave, "p4");
    for (int i = 0; i < 3; i++) {
      fila.entrar(f.eventoId(), "p4");
    }
    assertThat(antes).isNotNull();
    assertThat(redis.opsForZSet().score(chave, "p4")).isEqualTo(antes);
    assertThat(fila.situacao(f.eventoId(), "nunca-entrou").get("status")).isEqualTo("FORA");
  }

  @Test
  void quemPodeLigarEQuemPodeEntrar() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "F5");
    String corpo = "{\"ativa\":true}";

    mvc.perform(
            put("/eventos/" + f.eventoId() + "/fila-ativa")
                .with(cliente("c"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/eventos/" + f.eventoId() + "/fila-ativa")
                .with(organizador("outro"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isNotFound());
    mvc.perform(post("/eventos/" + f.eventoId() + "/fila").with(organizador("org")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/eventos/" + f.eventoId() + "/fila")).andExpect(status().isUnauthorized());
  }

  @Test
  void desligarAFilaLiberaAReservaDeNovo() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "F6");
    UUID pista = Fixtures.lotePista(eventos, f.eventoId(), 5);
    ligar(f.eventoId(), true);
    mvc.perform(
            post("/reservas")
                .with(cliente("c-x"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserva(f.eventoId(), pista)))
        .andExpect(status().isForbidden());
    ligar(f.eventoId(), false);
    mvc.perform(
            post("/reservas")
                .with(cliente("c-x"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserva(f.eventoId(), pista)))
        .andExpect(status().isCreated());
  }
}
