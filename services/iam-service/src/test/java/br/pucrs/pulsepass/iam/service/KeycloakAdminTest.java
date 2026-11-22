package br.pucrs.pulsepass.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O adaptador real do Keycloak, contra um servidor HTTP que imita a Admin API. */
class KeycloakAdminTest {

  HttpServer servidor;
  final AtomicInteger chamadasDeToken = new AtomicInteger();
  final List<String> pedidos = new ArrayList<>();
  final List<String> corpos = new ArrayList<>();
  volatile int statusDosPapeis = 200;
  Instant agora = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @BeforeEach
  void subir() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/", this::tratar);
    servidor.start();
  }

  @AfterEach
  void parar() {
    servidor.stop(0);
  }

  private KeycloakAdmin cliente() {
    return new KeycloakAdmin(
        "http://127.0.0.1:" + servidor.getAddress().getPort(),
        "pulsepass",
        "pulsepass-iam",
        "segredo");
  }

  private void tratar(HttpExchange t) throws IOException {
    String caminho = t.getRequestURI().getPath();
    String corpo = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    synchronized (pedidos) {
      pedidos.add(
          t.getRequestMethod()
              + " "
              + t.getRequestURI().getRawPath()
              + (t.getRequestURI().getRawQuery() == null
                  ? ""
                  : "?" + t.getRequestURI().getRawQuery())
              + " auth="
              + t.getRequestHeaders().getFirst("Authorization"));
      corpos.add(corpo);
    }
    String resposta;
    int status = 200;
    if (caminho.equals("/realms/pulsepass/protocol/openid-connect/token")) {
      chamadasDeToken.incrementAndGet();
      resposta = "{\"access_token\":\"tok-1\",\"expires_in\":300}";
    } else if (caminho.equals("/admin/realms/pulsepass/roles/organizador")) {
      status = statusDosPapeis;
      resposta = "{\"id\":\"papel-id-1\",\"name\":\"organizador\"}";
    } else if (caminho.equals("/admin/realms/pulsepass/users/user-1/role-mappings/realm")) {
      status = 204;
      resposta = "";
    } else if (caminho.equals("/admin/realms/pulsepass/events")) {
      resposta =
          "["
              + "{\"time\":"
              + agora.toEpochMilli()
              + ",\"type\":\"LOGIN\",\"userId\":\"user-1\",\"ipAddress\":\"10.0.0.1\",\"clientId\":\"pulsepass-api\",\"sessionId\":\"s1\",\"details\":{\"username\":\"segredo@x.com\",\"email\":\"segredo@x.com\"}},"
              + "{\"time\":"
              + agora.plusSeconds(5).toEpochMilli()
              + ",\"type\":\"LOGIN_ERROR\",\"ipAddress\":\"10.0.0.2\",\"clientId\":\"pulsepass-api\",\"error\":\"invalid_user_credentials\",\"details\":{\"username\":\"segredo@x.com\"}},"
              + "{\"time\":"
              + agora.minusSeconds(3600).toEpochMilli()
              + ",\"type\":\"LOGIN\",\"userId\":\"antigo\",\"ipAddress\":\"10.0.0.3\",\"clientId\":\"pulsepass-api\"}"
              + "]";
    } else {
      status = 404;
      resposta = "{}";
    }
    byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
    t.getResponseHeaders().add("Content-Type", "application/json");
    t.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) {
      t.getResponseBody().write(bytes);
    }
    t.close();
  }

  @Test
  void atribuiOPapelBuscandoOIdDoPapelEReaproveitandoOToken() {
    var kc = cliente();

    kc.atribuirPapel("user-1", "organizador");
    kc.atribuirPapel("user-1", "organizador");

    assertThat(chamadasDeToken).hasValue(1); // o token foi reaproveitado
    synchronized (pedidos) {
      assertThat(pedidos)
          .anyMatch(
              p ->
                  p.startsWith("GET /admin/realms/pulsepass/roles/organizador")
                      && p.endsWith("auth=Bearer tok-1"));
      assertThat(pedidos)
          .anyMatch(
              p ->
                  p.startsWith("POST /admin/realms/pulsepass/users/user-1/role-mappings/realm")
                      && p.endsWith("auth=Bearer tok-1"));
      // compara como JSON: a ordem das chaves de um Map.of muda de uma JVM para outra
      var esperado =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .createArrayNode()
              .add(
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .createObjectNode()
                      .put("id", "papel-id-1")
                      .put("name", "organizador"));
      assertThat(corpos)
          .anyMatch(
              c -> {
                try {
                  return new com.fasterxml.jackson.databind.ObjectMapper()
                      .readTree(c)
                      .equals(esperado);
                } catch (Exception e) {
                  return false;
                }
              });
      // o pedido de token usa a conta de serviço
      assertThat(corpos.get(0))
          .contains(
              "grant_type=client_credentials", "client_id=pulsepass-iam", "client_secret=segredo");
    }
  }

  @Test
  void leEventosDeAcessoSemDadoPessoalEDescartaOsAnteriores() {
    var kc = cliente();
    Instant desde = agora.minusSeconds(60);

    var eventos = kc.eventos(desde);

    assertThat(eventos).hasSize(2); // o de uma hora atrás ficou de fora
    var login = eventos.get(0);
    assertThat(login.tipo()).isEqualTo("LOGIN");
    assertThat(login.usuarioId()).isEqualTo("user-1");
    assertThat(login.ip()).isEqualTo("10.0.0.1");
    assertThat(login.quando()).isEqualTo(agora);
    assertThat(login.detalhes()).isEqualTo("client=pulsepass-api");
    var erro = eventos.get(1);
    assertThat(erro.tipo()).isEqualTo("LOGIN_ERROR");
    assertThat(erro.usuarioId()).isNull();
    assertThat(erro.detalhes()).isEqualTo("client=pulsepass-api erro=invalid_user_credentials");
    assertThat(eventos.toString()).doesNotContain("segredo@x.com");
    assertThat(login.chave()).isNotEqualTo(erro.chave()).hasSize(64);
    // a mesma leitura repetida gera as mesmas chaves (é o que impede duplicar a auditoria)
    assertThat(kc.eventos(desde).get(0).chave()).isEqualTo(login.chave());

    synchronized (pedidos) {
      String consulta =
          pedidos.stream().filter(p -> p.contains("/events")).findFirst().orElseThrow();
      assertThat(consulta)
          .contains(
              "dateFrom=" + desde.toString().substring(0, 10),
              "max=500",
              "type=LOGIN",
              "type=LOGIN_ERROR",
              "type=UPDATE_PASSWORD");
    }
  }

  @Test
  void respostaDeErroDoKeycloakViraDiretorioIndisponivel() {
    statusDosPapeis = 500;
    assertThatThrownBy(() -> cliente().atribuirPapel("user-1", "organizador"))
        .isInstanceOf(DiretorioIndisponivel.class);
  }

  @Test
  void keycloakForaDoArViraDiretorioIndisponivel() {
    var fora = new KeycloakAdmin("http://127.0.0.1:1", "pulsepass", "pulsepass-iam", "x");
    assertThatThrownBy(() -> fora.atribuirPapel("user-1", "organizador"))
        .isInstanceOf(DiretorioIndisponivel.class);
    assertThatThrownBy(() -> fora.eventos(Instant.now())).isInstanceOf(DiretorioIndisponivel.class);
  }
}
