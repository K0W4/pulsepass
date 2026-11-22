package br.pucrs.pulsepass.apigateway;

import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate limit por cliente (sub do JWT; IP se anônimo): 429 com Retry-After acima do limite.
 *
 * <p>ponytail: baldes em memória, uma contagem por réplica do gateway; para um limite global troque
 * o mapa por um balde no Redis.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

  private static final int MAX_CHAVES = 50_000;

  private final double porSegundo;
  private final double rajada;
  private final LongSupplier relogioNs;
  private final ConcurrentHashMap<String, TokenBucket> baldes = new ConcurrentHashMap<>();

  @Autowired
  public RateLimitFilter(
      @Value("${pulsepass.ratelimit.rps:100}") double porSegundo,
      @Value("${pulsepass.ratelimit.burst:200}") double rajada) {
    this(porSegundo, rajada, System::nanoTime);
  }

  RateLimitFilter(double porSegundo, double rajada, LongSupplier relogioNs) {
    this.porSegundo = porSegundo;
    this.rajada = rajada;
    this.relogioNs = relogioNs;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String caminho = exchange.getRequest().getPath().value();
    if (!caminho.startsWith("/api/") || caminho.startsWith("/api/realtime")) {
      return chain.filter(exchange);
    }
    return exchange
        .getPrincipal()
        .map(Principal::getName)
        .map(sub -> "sub:" + sub)
        .defaultIfEmpty("ip:" + ipDe(exchange))
        .flatMap(
            chave -> {
              long espera = tentar(chave);
              if (espera == 0) {
                return chain.filter(exchange);
              }
              var resposta = exchange.getResponse();
              resposta.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
              resposta.getHeaders().set("Retry-After", String.valueOf((espera + 999) / 1000));
              return resposta.setComplete();
            });
  }

  long tentar(String chave) {
    long agora = relogioNs.getAsLong();
    if (baldes.size() > MAX_CHAVES) {
      baldes.values().removeIf(b -> b.ocioso(agora));
    }
    return baldes
        .computeIfAbsent(chave, k -> new TokenBucket(porSegundo, rajada, agora))
        .tentar(agora);
  }

  /** O Nginx da SPA acrescenta o IP real em X-Forwarded-For (o primeiro valor é o cliente). */
  private static String ipDe(ServerWebExchange exchange) {
    String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    var endereco = exchange.getRequest().getRemoteAddress();
    return endereco == null ? "desconhecido" : endereco.getAddress().getHostAddress();
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }
}
