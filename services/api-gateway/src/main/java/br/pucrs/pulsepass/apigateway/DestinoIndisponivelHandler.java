package br.pucrs.pulsepass.apigateway;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Serviço de destino fora do ar (conexão recusada, nome não resolvido ou tempo esgotado) vira 503,
 * e não 500: o gateway não falhou, o serviço atrás dele é que está indisponível (RNF11).
 */
@Component
@Order(-2)
public class DestinoIndisponivelHandler implements ErrorWebExceptionHandler {

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable erro) {
    if (!destinoIndisponivel(erro) || exchange.getResponse().isCommitted()) {
      return Mono.error(erro);
    }
    var resposta = exchange.getResponse();
    resposta.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    resposta.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] corpo = "{\"erro\":\"servico_indisponivel\"}".getBytes(StandardCharsets.UTF_8);
    return resposta.writeWith(Mono.just(resposta.bufferFactory().wrap(corpo)));
  }

  static boolean destinoIndisponivel(Throwable erro) {
    for (Throwable t = erro; t != null; t = t.getCause()) {
      if (t instanceof ConnectException
          || t instanceof UnknownHostException
          || t instanceof TimeoutException
          || t.getClass().getSimpleName().contains("ConnectTimeout")) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
