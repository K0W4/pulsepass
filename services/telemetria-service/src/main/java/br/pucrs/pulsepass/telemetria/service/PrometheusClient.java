package br.pucrs.pulsepass.telemetria.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Métricas operacionais para a tela de administração (US33), lidas do Prometheus. */
@Component
public class PrometheusClient {

  private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

  static final String LATENCIA =
      "histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket[1m])))";
  static final String ERROS =
      "sum by (application) (rate(http_server_requests_seconds_count{status=~\"5..\"}[1m]))";
  static final String LAG = "max by (application) (kafka_consumer_fetch_manager_records_lag_max)";

  private final RestClient http;

  public PrometheusClient(@Value("${telemetria.prometheus-url}") String url) {
    var fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(1_000);
    fabrica.setReadTimeout(2_000);
    this.http = RestClient.builder().baseUrl(url).requestFactory(fabrica).build();
  }

  /** Se o Prometheus não responder, devolve {@code disponivel=false} em vez de erro. */
  public Map<String, Object> operacional() {
    try {
      return Map.of(
          "disponivel", true,
          "latenciaP95Segundos", porAplicacao(LATENCIA),
          "errosPorSegundo", porAplicacao(ERROS),
          "lagKafka", porAplicacao(LAG));
    } catch (RuntimeException e) {
      log.warn("Prometheus indisponível: {}", e.toString());
      return Map.of("disponivel", false);
    }
  }

  private Map<String, Double> porAplicacao(String promql) {
    JsonNode resposta =
        http.get()
            .uri(b -> b.path("/api/v1/query").queryParam("query", "{q}").build(promql))
            .retrieve()
            .body(JsonNode.class);
    Map<String, Double> saida = new TreeMap<>();
    if (resposta == null) {
      return saida;
    }
    for (JsonNode serie : resposta.path("data").path("result")) {
      String app = serie.path("metric").path("application").asText(null);
      String valor = serie.path("value").path(1).asText(null);
      if (app == null || valor == null) {
        continue;
      }
      double v = Double.parseDouble(valor);
      if (!Double.isNaN(v) && !Double.isInfinite(v)) {
        saida.put(app, v);
      }
    }
    return saida;
  }
}
