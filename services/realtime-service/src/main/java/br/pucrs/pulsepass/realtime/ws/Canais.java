package br.pucrs.pulsepass.realtime.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Sessões conectadas por canal. Sem estado persistente: cada réplica cuida das suas sessões. */
@Component
public class Canais {

  private static final Logger log = LoggerFactory.getLogger(Canais.class);

  private final Map<String, Map<String, WebSocketSession>> porCanal = new ConcurrentHashMap<>();
  private final AtomicInteger conexoes = new AtomicInteger();
  private final io.micrometer.core.instrument.Counter entregues;

  public Canais(MeterRegistry metricas) {
    metricas.gauge("pulsepass.realtime.conexoes", conexoes);
    entregues = metricas.counter("pulsepass.realtime.mensagens.entregues");
  }

  public void adicionar(String canal, WebSocketSession sessao) {
    // o decorador serializa os envios: WebSocketSession não aceita escritas concorrentes
    var segura = new ConcurrentWebSocketSessionDecorator(sessao, 5_000, 64 * 1024);
    if (porCanal.computeIfAbsent(canal, c -> new ConcurrentHashMap<>()).put(sessao.getId(), segura)
        == null) {
      conexoes.incrementAndGet();
    }
  }

  public void remover(String canal, WebSocketSession sessao) {
    var sessoes = porCanal.get(canal);
    if (sessoes != null && sessoes.remove(sessao.getId()) != null) {
      conexoes.decrementAndGet();
      porCanal.computeIfPresent(canal, (c, m) -> m.isEmpty() ? null : m);
    }
  }

  public int conectados() {
    return conexoes.get();
  }

  public void entregar(String canal, String mensagem) {
    var sessoes = porCanal.get(canal);
    if (sessoes == null) {
      return;
    }
    var texto = new TextMessage(mensagem);
    sessoes
        .values()
        .forEach(
            s -> {
              try {
                s.sendMessage(texto);
                entregues.increment();
              } catch (IOException | RuntimeException e) {
                log.debug("Sessão {} descartada: {}", s.getId(), e.toString());
                fechar(canal, s);
              }
            });
  }

  private void fechar(String canal, WebSocketSession s) {
    try {
      s.close();
    } catch (IOException | RuntimeException ignorada) {
      // a sessão já estava quebrada
    }
    remover(canal, s);
  }

  /** Mantém as conexões vivas atrás do Nginx e do gateway (que fecham conexões ociosas). */
  @Scheduled(fixedDelayString = "${realtime.ping-ms:25000}")
  public void ping() {
    porCanal.forEach(
        (canal, sessoes) ->
            sessoes
                .values()
                .forEach(
                    s -> {
                      try {
                        s.sendMessage(new PingMessage());
                      } catch (IOException | RuntimeException e) {
                        fechar(canal, s);
                      }
                    }));
  }
}
