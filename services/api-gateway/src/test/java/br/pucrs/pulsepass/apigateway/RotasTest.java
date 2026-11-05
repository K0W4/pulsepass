package br.pucrs.pulsepass.apigateway;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Roteamento por prefixo e 401 sem token, com um serviço de destino falso. */
@SpringBootTest
class RotasTest {

  static final HttpServer DESTINO;

  static {
    try {
      DESTINO = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    DESTINO.createContext(
        "/",
        troca -> {
          byte[] corpo =
              ("{\"caminho\":\"" + troca.getRequestURI().getPath() + "\"}")
                  .getBytes(StandardCharsets.UTF_8);
          troca.getResponseHeaders().add("Content-Type", "application/json");
          troca.sendResponseHeaders(200, corpo.length);
          troca.getResponseBody().write(corpo);
          troca.close();
        });
    DESTINO.start();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    String url = "http://127.0.0.1:" + DESTINO.getAddress().getPort();
    r.add("INVENTARIO_URL", () -> url);
    r.add("CHECKOUT_URL", () -> url);
    r.add("EMISSAO_URL", () -> url);
  }

  @Autowired ApplicationContext contexto;

  private WebTestClient cliente() {
    return WebTestClient.bindToApplicationContext(contexto)
        .apply(SecurityMockServerConfigurers.springSecurity())
        .configureClient()
        .build();
  }

  @Test
  void catalogoEPublicoERemoveOPrefixoApi() {
    cliente()
        .get()
        .uri("/api/eventos")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.caminho")
        .isEqualTo("/eventos");
  }

  @Test
  void rotasProtegidasExigemToken() {
    cliente().post().uri("/api/reservas").exchange().expectStatus().isUnauthorized();
    cliente().get().uri("/api/ingressos").exchange().expectStatus().isUnauthorized();
    cliente().post().uri("/api/pagamentos").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void comTokenAsRotasChegamAoServicoCerto() {
    var c = cliente().mutateWith(mockJwt());
    c.post()
        .uri("/api/reservas")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.caminho")
        .isEqualTo("/reservas");
    c.post()
        .uri("/api/pagamentos")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.caminho")
        .isEqualTo("/pagamentos");
    c.get()
        .uri("/api/ingressos")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.caminho")
        .isEqualTo("/ingressos");
  }

  @Test
  void rotaDesconhecidaDa404() {
    cliente()
        .mutateWith(mockJwt())
        .get()
        .uri("/api/inexistente")
        .exchange()
        .expectStatus()
        .isNotFound();
  }
}
