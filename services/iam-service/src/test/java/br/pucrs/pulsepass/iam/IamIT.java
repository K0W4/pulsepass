package br.pucrs.pulsepass.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucrs.pulsepass.iam.service.Diretorio;
import br.pucrs.pulsepass.iam.service.DiretorioIndisponivel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** US05 (organizador com aprovação), US06 (auditoria), RNF13 (RBAC) e atomicidade da aprovação. */
@SpringBootTest
@AutoConfigureMockMvc
class IamIT {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

  static {
    Startables.deepStart(PG, KAFKA).join();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("pulsepass.outbox.poll-ms", () -> "200");
    r.add("iam.auditoria.poll-ms", () -> "200");
  }

  /** Keycloak de mentira: guarda os papéis atribuídos, pode falhar e devolve eventos de acesso. */
  static class DiretorioFalso implements Diretorio {
    final List<String> papeis = new CopyOnWriteArrayList<>();
    volatile boolean falhar;
    volatile List<EventoAuditavel> eventos = List.of();

    @Override
    public void atribuirPapel(String usuarioId, String papel) {
      if (falhar) {
        throw new DiretorioIndisponivel("Keycloak fora do ar", null);
      }
      papeis.add(usuarioId + ":" + papel);
    }

    @Override
    public List<EventoAuditavel> eventos(Instant desde) {
      return eventos;
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    DiretorioFalso diretorioFalso() {
      return new DiretorioFalso();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired DiretorioFalso falso;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private static RequestPostProcessor como(String papel, String usuario) {
    return jwt()
        .jwt(j -> j.subject(usuario))
        .authorities(new SimpleGrantedAuthority("ROLE_" + papel));
  }

  private static final String CORPO_VALIDO =
      "{\"razaoSocial\":\"Produtora Exemplo Ltda\",\"cnpj\":\"11.222.333/0001-81\",\"contato\":\"contato@produtora.test\"}";

  private UUID solicitar(String usuario) throws Exception {
    String corpo =
        mvc.perform(
                post("/iam/organizadores/solicitacoes")
                    .with(como("CLIENTE", usuario))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CORPO_VALIDO))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(mapper.readTree(corpo).get("id").asText());
  }

  private JsonNode json(String caminho, RequestPostProcessor quem) throws Exception {
    return mapper.readTree(
        mvc.perform(get(caminho).with(quem))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static String statusDe(JsonNode minha) {
    return minha.get("status").asText();
  }

  private List<ConsumerRecord<String, String>> consumir(String topico, Duration prazo) {
    var props =
        Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "teste-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    List<ConsumerRecord<String, String>> lidos = new ArrayList<>();
    try (var c = new KafkaConsumer<String, String>(props)) {
      c.subscribe(List.of(topico));
      long fim = System.nanoTime() + prazo.toNanos();
      while (System.nanoTime() < fim) {
        c.poll(Duration.ofMillis(300)).forEach(lidos::add);
      }
    }
    return lidos;
  }

  @Test
  void fluxoCompletoDaSolicitacaoAteOPapelEOEventoNoKafka() throws Exception {
    String usuario = "user-" + UUID.randomUUID();
    UUID id = solicitar(usuario);

    var pendente = json("/iam/minha-solicitacao", como("CLIENTE", usuario));
    assertThat(statusDe(pendente)).isEqualTo("PENDENTE");
    assertThat(pendente.get("cnpj").asText()).isEqualTo("11222333000181"); // guardado sem pontuação
    boolean listada = false;
    for (JsonNode s :
        json("/iam/organizadores/solicitacoes?status=PENDENTE", como("ADMIN", "admin-1"))) {
      listada |= s.get("id").asText().equals(id.toString());
    }
    assertThat(listada).isTrue();

    mvc.perform(
            post("/iam/organizadores/solicitacoes/" + id + "/aprovar")
                .with(como("ADMIN", "admin-1")))
        .andExpect(status().isNoContent());

    assertThat(falso.papeis).contains(usuario + ":organizador");
    assertThat(statusDe(json("/iam/minha-solicitacao", como("CLIENTE", usuario))))
        .isEqualTo("APROVADA");
    var trilha = json("/iam/auditoria?usuario=" + usuario, como("ADMIN", "admin-1"));
    assertThat(trilha.findValuesAsText("tipo"))
        .contains("ORGANIZADOR_SOLICITADO", "ORGANIZADOR_APROVADO");
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(consumir("iam.usuario.v1", Duration.ofSeconds(2)))
                    .anyMatch(
                        r -> usuario.equals(r.key()) && r.value().contains("OrganizadorAprovado")));
  }

  @Test
  void rejeitarNaoDaPapelNemPublicaEventoEPermiteNovoPedido() throws Exception {
    String usuario = "user-" + UUID.randomUUID();
    UUID id = solicitar(usuario);

    mvc.perform(
            post("/iam/organizadores/solicitacoes/" + id + "/rejeitar")
                .with(como("ADMIN", "admin-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"documentação incompleta\"}"))
        .andExpect(status().isNoContent());

    assertThat(falso.papeis).noneMatch(p -> p.startsWith(usuario));
    var minha = json("/iam/minha-solicitacao", como("CLIENTE", usuario));
    assertThat(statusDe(minha)).isEqualTo("REJEITADA");
    assertThat(minha.get("motivo").asText()).isEqualTo("documentação incompleta");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where msg_key = ?", Integer.class, usuario))
        .isZero();
    solicitar(usuario); // depois de decidida, pode pedir de novo
  }

  @Test
  void validacoesDaSolicitacaoEDuplicidadeDePendente() throws Exception {
    String usuario = "user-" + UUID.randomUUID();
    for (String invalido :
        new String[] {
          "{\"razaoSocial\":\"X\",\"cnpj\":\"11.222.333/0001-82\",\"contato\":\"c\"}", // dígito
          // errado
          "{\"razaoSocial\":\"\",\"cnpj\":\"11.222.333/0001-81\",\"contato\":\"c\"}",
          "{\"razaoSocial\":\"X\",\"cnpj\":\"11.222.333/0001-81\",\"contato\":\"\"}",
          "{\"cnpj\":\"11.222.333/0001-81\"}"
        }) {
      mvc.perform(
              post("/iam/organizadores/solicitacoes")
                  .with(como("CLIENTE", usuario))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalido))
          .andExpect(status().isBadRequest());
    }
    solicitar(usuario);
    mvc.perform(
            post("/iam/organizadores/solicitacoes")
                .with(como("CLIENTE", usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CORPO_VALIDO))
        .andExpect(status().isConflict()); // já há uma pendente
  }

  @Test
  void decidirDuasVezesDa409ESolicitacaoInexistenteDa404() throws Exception {
    UUID id = solicitar("user-" + UUID.randomUUID());
    mvc.perform(post("/iam/organizadores/solicitacoes/" + id + "/aprovar").with(como("ADMIN", "a")))
        .andExpect(status().isNoContent());
    mvc.perform(post("/iam/organizadores/solicitacoes/" + id + "/aprovar").with(como("ADMIN", "a")))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/iam/organizadores/solicitacoes/" + id + "/rejeitar").with(como("ADMIN", "a")))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/iam/organizadores/solicitacoes/" + UUID.randomUUID() + "/aprovar")
                .with(como("ADMIN", "a")))
        .andExpect(status().isNotFound());
  }

  @Test
  void keycloakForaDesfazTudoEDepoisAAprovacaoFunciona() throws Exception {
    String usuario = "user-" + UUID.randomUUID();
    UUID id = solicitar(usuario);
    falso.falhar = true;
    try {
      mvc.perform(
              post("/iam/organizadores/solicitacoes/" + id + "/aprovar").with(como("ADMIN", "a")))
          .andExpect(status().isServiceUnavailable());
    } finally {
      falso.falhar = false;
    }
    // nada ficou pela metade: continua PENDENTE, sem evento e sem decisão na auditoria
    assertThat(statusDe(json("/iam/minha-solicitacao", como("CLIENTE", usuario))))
        .isEqualTo("PENDENTE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where msg_key = ?", Integer.class, usuario))
        .isZero();
    assertThat(
            json("/iam/auditoria?usuario=" + usuario, como("ADMIN", "a")).findValuesAsText("tipo"))
        .doesNotContain("ORGANIZADOR_APROVADO");

    mvc.perform(post("/iam/organizadores/solicitacoes/" + id + "/aprovar").with(como("ADMIN", "a")))
        .andExpect(status().isNoContent());
    assertThat(falso.papeis).contains(usuario + ":organizador");
  }

  @Test
  void auditoriaCopiaEventosDoKeycloakSemDuplicar() throws Exception {
    String usuario = "kc-" + UUID.randomUUID();
    Instant agora = Instant.now();
    falso.eventos =
        List.of(
            new Diretorio.EventoAuditavel(
                "k1-" + usuario,
                "LOGIN",
                usuario,
                "10.0.0.1",
                "client=pulsepass-api",
                agora.minusSeconds(30)),
            new Diretorio.EventoAuditavel(
                "k2-" + usuario,
                "LOGIN_ERROR",
                usuario,
                "10.0.0.9",
                "client=pulsepass-api erro=invalid_user_credentials",
                agora.minusSeconds(20)),
            new Diretorio.EventoAuditavel(
                "k3-" + usuario,
                "UPDATE_PASSWORD",
                usuario,
                "10.0.0.1",
                "client=pulsepass-api",
                agora.minusSeconds(10)));

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(json("/iam/auditoria?usuario=" + usuario, como("ADMIN", "a")))
                    .hasSize(3));
    Thread.sleep(1000); // o worker lê os mesmos eventos de novo várias vezes
    assertThat(json("/iam/auditoria?usuario=" + usuario, como("ADMIN", "a"))).hasSize(3);

    var erros = json("/iam/auditoria?usuario=" + usuario + "&tipo=LOGIN_ERROR", como("ADMIN", "a"));
    assertThat(erros).hasSize(1);
    assertThat(erros.get(0).get("origem").asText()).isEqualTo("KEYCLOAK");
    assertThat(erros.get(0).get("ip").asText()).isEqualTo("10.0.0.9");
    // do mais recente para o mais antigo
    assertThat(
            json("/iam/auditoria?usuario=" + usuario, como("ADMIN", "a"))
                .get(0)
                .get("tipo")
                .asText())
        .isEqualTo("UPDATE_PASSWORD");
    assertThat(json("/iam/auditoria?usuario=" + usuario + "&limite=2", como("ADMIN", "a")))
        .hasSize(2);
    mvc.perform(get("/iam/auditoria?limite=0").with(como("ADMIN", "a")))
        .andExpect(status().isBadRequest());
    falso.eventos = List.of();
  }

  @Test
  void rbacDasRotas() throws Exception {
    mvc.perform(
            post("/iam/organizadores/solicitacoes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CORPO_VALIDO))
        .andExpect(status().isUnauthorized());
    // só cliente pede
    for (String papel : new String[] {"ADMIN", "ORGANIZADOR", "OPERADOR"}) {
      mvc.perform(
              post("/iam/organizadores/solicitacoes")
                  .with(como(papel, "x"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(CORPO_VALIDO))
          .andExpect(status().isForbidden());
    }
    // só admin decide, lista e audita
    for (String papel : new String[] {"CLIENTE", "ORGANIZADOR", "OPERADOR"}) {
      mvc.perform(get("/iam/organizadores/solicitacoes").with(como(papel, "x")))
          .andExpect(status().isForbidden());
      mvc.perform(get("/iam/auditoria").with(como(papel, "x"))).andExpect(status().isForbidden());
      mvc.perform(
              post("/iam/organizadores/solicitacoes/" + UUID.randomUUID() + "/aprovar")
                  .with(como(papel, "x")))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get("/iam/organizadores/solicitacoes?status=INVALIDO").with(como("ADMIN", "a")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/iam/minha-solicitacao").with(como("CLIENTE", "sem-pedido-" + UUID.randomUUID())))
        .andExpect(status().isNotFound());
  }
}
