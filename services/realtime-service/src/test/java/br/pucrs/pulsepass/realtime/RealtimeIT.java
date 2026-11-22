package br.pucrs.pulsepass.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import br.pucrs.pulsepass.realtime.ws.Canais;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.kafka.KafkaContainer;

/** US14, US27 e US32: eventos do Kafka chegam ao navegador, só ao canal certo e com autorização. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RealtimeIT {

  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

  static {
    KAFKA.start();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  /** Tokens de mentira, para não depender do Keycloak: "cliente" e "operador". */
  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    JwtDecoder decodificadorDeTeste() {
      return token -> {
        if (!token.equals("token-cliente") && !token.equals("token-operador")) {
          throw new BadJwtException("token inválido");
        }
        String papel = token.equals("token-cliente") ? "cliente" : "operador";
        return Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject("sub")
            .claim("realm_access", Map.of("roles", List.of(papel)))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
      };
    }

    @Bean
    NewTopic t1() {
      return new NewTopic("inventario.assento.v1", 1, (short) 1);
    }

    @Bean
    NewTopic t2() {
      return new NewTopic("checkout.pedido.v1", 1, (short) 1);
    }

    @Bean
    NewTopic t3() {
      return new NewTopic("emissao.ingresso.v1", 1, (short) 1);
    }
  }

  @LocalServerPort int porta;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired Canais canais;
  final ObjectMapper json = new ObjectMapper();

  /** Cliente de teste: guarda o que chega. */
  static final class Cliente extends TextWebSocketHandler {
    final BlockingQueue<String> recebidas = new LinkedBlockingQueue<>();
    volatile WebSocketSession sessao;

    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
      sessao = s;
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage m) {
      recebidas.add(m.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus st) {
      sessao = null;
    }
  }

  private Cliente conectar(String caminho) throws Exception {
    var cliente = new Cliente();
    new StandardWebSocketClient()
        .execute(cliente, "ws://localhost:" + porta + caminho)
        .get(10, TimeUnit.SECONDS);
    return cliente;
  }

  private String envelope(String tipo, String payload) {
    return "{\"eventId\":\""
        + UUID.randomUUID()
        + "\",\"eventType\":\""
        + tipo
        + "\",\"payload\":"
        + payload
        + "}";
  }

  /** Reenvia até chegar: o consumidor começa no fim do tópico e pode demorar a ser atribuído. */
  private JsonNode esperar(Cliente c, String topico, String chave, String mensagem)
      throws Exception {
    String[] recebida = new String[1];
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              kafka.send(topico, chave, mensagem);
              recebida[0] = c.recebidas.poll(500, TimeUnit.MILLISECONDS);
              return recebida[0] != null;
            });
    return json.readTree(recebida[0]);
  }

  @Test
  void mudancaDeAssentoChegaSoAoCanalDoEvento() throws Exception {
    String a = UUID.randomUUID().toString();
    String b = UUID.randomUUID().toString();
    var clienteA = conectar("/realtime/eventos/" + a);
    var clienteB = conectar("/realtime/eventos/" + b);
    String assento = UUID.randomUUID().toString();

    var recebida =
        esperar(
            clienteA,
            "inventario.assento.v1",
            a,
            envelope(
                "AssentoReservado",
                "{\"eventoId\":\""
                    + a
                    + "\",\"assentoId\":\""
                    + assento
                    + "\",\"status\":\"RESERVADO\"}"));

    assertThat(recebida.get("tipo").asText()).isEqualTo("AssentoReservado");
    assertThat(recebida.get("assentoId").asText()).isEqualTo(assento);
    assertThat(recebida.get("status").asText()).isEqualTo("RESERVADO");
    assertThat(clienteB.recebidas.poll(1, TimeUnit.SECONDS)).as("outro evento não recebe").isNull();
  }

  @Test
  void canalDoPedidoExigeTokenDeCliente() throws Exception {
    String pedido = UUID.randomUUID().toString();
    String caminho = "/realtime/pedidos/" + pedido;

    assertThatThrownBy(() -> conectar(caminho)).as("sem token").isNotNull();
    assertThatThrownBy(() -> conectar(caminho + "?token=lixo")).as("token inválido").isNotNull();
    assertThatThrownBy(() -> conectar(caminho + "?token=token-operador"))
        .as("papel errado")
        .isNotNull();

    var cliente = conectar(caminho + "?token=token-cliente");
    var pago =
        esperar(
            cliente,
            "checkout.pedido.v1",
            pedido,
            envelope(
                "PedidoPago",
                "{\"pedidoId\":\"" + pedido + "\",\"clienteId\":\"sub-privado\",\"total\":10}"));
    assertThat(pago.get("tipo").asText()).isEqualTo("PedidoPago");
    assertThat(pago.toString()).doesNotContain("sub-privado");

    String ingresso = UUID.randomUUID().toString();
    var emitido =
        esperar(
            cliente,
            "emissao.ingresso.v1",
            pedido,
            envelope(
                "IngressoEmitido",
                "{\"pedidoId\":\"" + pedido + "\",\"ingressoId\":\"" + ingresso + "\"}"));
    assertThat(emitido.get("tipo").asText()).isEqualTo("IngressoEmitido");
    assertThat(emitido.get("ingressoId").asText()).isEqualTo(ingresso);
  }

  @Test
  void caminhoInvalidoEhRecusado() {
    assertThatThrownBy(() -> conectar("/realtime/eventos/nao-e-uuid")).isNotNull();
    assertThatThrownBy(() -> conectar("/realtime/outro/" + UUID.randomUUID())).isNotNull();
  }

  @Test
  void sessaoFechadaSaiDoCanal() throws Exception {
    int antes = canais.conectados();
    var cliente = conectar("/realtime/eventos/" + UUID.randomUUID());
    await().atMost(Duration.ofSeconds(5)).until(() -> canais.conectados() == antes + 1);

    cliente.sessao.close();

    await().atMost(Duration.ofSeconds(5)).until(() -> canais.conectados() == antes);
  }

  @Test
  void mensagemInvalidaNaoDerrubaOConsumidor() throws Exception {
    String a = UUID.randomUUID().toString();
    var cliente = conectar("/realtime/eventos/" + a);
    kafka.send("inventario.assento.v1", a, "isto não é json");
    var recebida =
        esperar(
            cliente,
            "inventario.assento.v1",
            a,
            envelope(
                "AssentoLiberado",
                "{\"eventoId\":\"" + a + "\",\"assentoId\":\"x\",\"status\":\"LIVRE\"}"));
    assertThat(recebida.get("tipo").asText()).isEqualTo("AssentoLiberado");
  }
}
