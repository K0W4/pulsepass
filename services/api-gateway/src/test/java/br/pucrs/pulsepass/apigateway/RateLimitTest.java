package br.pucrs.pulsepass.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** Rate limit do gateway: balde de fichas e filtro (429, Retry-After e isenções). */
class RateLimitTest {

  private static final long SEG = 1_000_000_000L;

  @Test
  void baldeConsomeARajadaEDepoisPedeEspera() {
    var b = new TokenBucket(10, 3, 0);
    assertThat(b.tentar(0)).isZero();
    assertThat(b.tentar(0)).isZero();
    assertThat(b.tentar(0)).isZero();
    assertThat(b.tentar(0)).isEqualTo(100); // 10 fichas/s: 100 ms até a próxima
  }

  @Test
  void baldeReabasteceComOTempoMasNaoPassaDaRajada() {
    var b = new TokenBucket(10, 3, 0);
    for (int i = 0; i < 3; i++) {
      b.tentar(0);
    }
    assertThat(b.tentar(SEG / 10)).isZero(); // 100 ms = 1 ficha
    assertThat(b.tentar(SEG / 10)).isPositive();
    long muitoDepois = 100 * SEG;
    for (int i = 0; i < 3; i++) {
      assertThat(b.tentar(muitoDepois)).isZero(); // volta a 3, não a 1000
    }
    assertThat(b.tentar(muitoDepois)).isPositive();
  }

  @Test
  void baldeOciosoSoDepoisDeUmMinuto() {
    var b = new TokenBucket(10, 3, 0);
    assertThat(b.ocioso(60 * SEG)).isFalse();
    assertThat(b.ocioso(60 * SEG + 1)).isTrue();
  }

  private MockServerWebExchange chamada(String caminho, String xff) {
    var req = MockServerHttpRequest.get(caminho);
    if (xff != null) {
      req.header("X-Forwarded-For", xff);
    }
    return MockServerWebExchange.from(req);
  }

  @Test
  void filtroResponde429ComRetryAfterAcimaDoLimite() {
    var agora = new AtomicLong();
    var filtro = new RateLimitFilter(1, 2, agora::get);
    var passou = new AtomicInteger();
    org.springframework.cloud.gateway.filter.GatewayFilterChain cadeia =
        ex -> {
          passou.incrementAndGet();
          return Mono.empty();
        };
    for (int i = 0; i < 2; i++) {
      filtro.filter(chamada("/api/eventos", "1.1.1.1"), cadeia).block();
    }
    var barrada = chamada("/api/eventos", "1.1.1.1");
    filtro.filter(barrada, cadeia).block();
    assertThat(passou).hasValue(2);
    assertThat(barrada.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(barrada.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
  }

  @Test
  void cadaClienteTemOSeuBalde() {
    var filtro = new RateLimitFilter(1, 1, () -> 0L);
    var passou = new AtomicInteger();
    org.springframework.cloud.gateway.filter.GatewayFilterChain cadeia =
        ex -> {
          passou.incrementAndGet();
          return Mono.empty();
        };
    filtro.filter(chamada("/api/eventos", "1.1.1.1"), cadeia).block();
    filtro.filter(chamada("/api/eventos", "2.2.2.2"), cadeia).block();
    assertThat(passou).hasValue(2);
  }

  @Test
  void actuatorERealtimeNaoSaoLimitados() {
    var filtro = new RateLimitFilter(1, 1, () -> 0L);
    var passou = new AtomicInteger();
    org.springframework.cloud.gateway.filter.GatewayFilterChain cadeia =
        ex -> {
          passou.incrementAndGet();
          return Mono.empty();
        };
    for (int i = 0; i < 5; i++) {
      filtro.filter(chamada("/actuator/prometheus", "1.1.1.1"), cadeia).block();
      filtro.filter(chamada("/api/realtime/eventos/x", "1.1.1.1"), cadeia).block();
    }
    assertThat(passou).hasValue(10);
  }
}
