package br.pucrs.pulsepass.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CobrancaController {

  public record Cobranca(String pagamentoId, Object valor) {}

  public record Config(String modo, long atrasoMs, boolean falhar) {}

  private final Map<String, Map<String, String>> resultados = new ConcurrentHashMap<>();
  private final AtomicInteger chamadas = new AtomicInteger();
  private volatile Config config;

  public CobrancaController(
      @Value("${gateway.modo:APROVAR}") String modo,
      @Value("${gateway.atraso-ms:0}") long atrasoMs,
      @Value("${gateway.falhar:false}") boolean falhar) {
    this.config = new Config(modo, atrasoMs, falhar);
  }

  /** Idempotente por pagamentoId: repetir a chamada devolve o mesmo resultado. */
  @PostMapping("/cobrancas")
  public ResponseEntity<Map<String, String>> cobrar(@RequestBody Cobranca c)
      throws InterruptedException {
    chamadas.incrementAndGet();
    Config atual = config;
    if (atual.atrasoMs() > 0) {
      Thread.sleep(atual.atrasoMs());
    }
    if (atual.falhar()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
    var resultado =
        resultados.computeIfAbsent(
            c.pagamentoId(),
            id ->
                "RECUSAR".equalsIgnoreCase(atual.modo())
                    ? Map.of(
                        "status",
                        "RECUSADO",
                        "referencia",
                        "ref-" + id,
                        "motivo",
                        "SALDO_INSUFICIENTE")
                    : Map.of("status", "APROVADO", "referencia", "ref-" + id));
    return ResponseEntity.ok(resultado);
  }

  @PutMapping("/admin/config")
  public Config configurar(@RequestBody Config nova) {
    this.config = nova;
    return nova;
  }

  @GetMapping("/admin/chamadas")
  public Map<String, Integer> chamadas() {
    return Map.of("total", chamadas.get(), "unicos", resultados.size());
  }
}
