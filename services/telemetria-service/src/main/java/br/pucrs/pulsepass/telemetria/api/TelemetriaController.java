package br.pucrs.pulsepass.telemetria.api;

import br.pucrs.pulsepass.common.Invalido;
import br.pucrs.pulsepass.common.Outbox;
import br.pucrs.pulsepass.telemetria.domain.Agregador;
import br.pucrs.pulsepass.telemetria.service.Metricas;
import br.pucrs.pulsepass.telemetria.service.PrometheusClient;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TelemetriaController {

  public record Navegacao(String pagina) {}

  private static final int MAX_MINUTOS = 1440;

  private final Metricas metricas;
  private final PrometheusClient prometheus;
  private final Outbox outbox;

  public TelemetriaController(Metricas metricas, PrometheusClient prometheus, Outbox outbox) {
    this.metricas = metricas;
    this.prometheus = prometheus;
    this.outbox = outbox;
  }

  /** Ingestão de navegação: anônima, sem identificar a pessoa; publica pelo Outbox. */
  @PostMapping("/telemetria/navegacao")
  @Transactional
  public ResponseEntity<Void> navegacao(@RequestBody Navegacao n) {
    String pagina = Agregador.pagina(n.pagina());
    outbox.add(Agregador.TOPICO_NAVEGACAO, pagina, "PaginaVista", Map.of("pagina", pagina));
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  @GetMapping("/telemetria/resumo")
  public Metricas.Resumo resumo(@RequestParam(defaultValue = "15") int minutos) {
    return metricas.resumo(janela(minutos));
  }

  @GetMapping("/telemetria/emissao")
  public Metricas.Emissao emissao(@RequestParam(defaultValue = "60") int minutos) {
    return metricas.emissao(janela(minutos));
  }

  @GetMapping("/telemetria/operacional")
  public Map<String, Object> operacional() {
    return prometheus.operacional();
  }

  private static int janela(int minutos) {
    if (minutos < 1 || minutos > MAX_MINUTOS) {
      throw new Invalido("minutos deve estar entre 1 e " + MAX_MINUTOS);
    }
    return minutos;
  }
}
