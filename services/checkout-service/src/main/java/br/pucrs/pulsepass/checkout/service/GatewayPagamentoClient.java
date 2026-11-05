package br.pucrs.pulsepass.checkout.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Cliente do gateway de pagamento: timeout, retry com backoff e circuit breaker (RNF10). */
@Component
public class GatewayPagamentoClient {

  public record Resultado(boolean aprovado, String referencia, String motivo) {}

  public static class GatewayIndisponivel extends RuntimeException {
    public GatewayIndisponivel(String mensagem, Throwable causa) {
      super(mensagem, causa);
    }
  }

  private final RestClient http;
  private final Retry retry;
  private final CircuitBreaker breaker;

  public GatewayPagamentoClient(
      @Value("${pulsepass.gateway.url}") String url,
      @Value("${pulsepass.gateway.timeout:PT20S}") Duration timeout,
      @Value("${pulsepass.gateway.retry-backoff-ms:200}") long backoffMs,
      @Value("${pulsepass.gateway.breaker-open:PT10S}") Duration abertoPor) {
    var fabrica =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
    fabrica.setReadTimeout(timeout);
    this.http = RestClient.builder().baseUrl(url).requestFactory(fabrica).build();
    this.retry =
        Retry.of(
            "gateway-pagamento",
            RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(backoffMs, 2))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
    this.breaker =
        CircuitBreaker.of(
            "gateway-pagamento",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(abertoPor)
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
  }

  /** O id do pagamento serve de chave de idempotência também no gateway. */
  @SuppressWarnings("unchecked")
  public Resultado cobrar(UUID pagamentoId, BigDecimal valor) {
    Supplier<Map<String, Object>> chamada =
        () ->
            http.post()
                .uri("/cobrancas")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", pagamentoId.toString())
                .body(Map.of("pagamentoId", pagamentoId.toString(), "valor", valor))
                .retrieve()
                .body(Map.class);
    try {
      Map<String, Object> r =
          Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, chamada)).get();
      return new Resultado(
          "APROVADO".equals(r.get("status")),
          (String) r.get("referencia"),
          (String) r.get("motivo"));
    } catch (CallNotPermittedException e) {
      throw new GatewayIndisponivel("circuit breaker aberto", e);
    } catch (ResourceAccessException | HttpServerErrorException e) {
      throw new GatewayIndisponivel("gateway indisponível", e);
    }
  }
}
