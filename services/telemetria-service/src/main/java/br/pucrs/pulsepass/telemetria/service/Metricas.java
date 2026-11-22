package br.pucrs.pulsepass.telemetria.service;

import br.pucrs.pulsepass.telemetria.domain.Agregador;
import br.pucrs.pulsepass.telemetria.domain.Percentil;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Agregação por minuto (US32) e tempo de emissão (RNF08). Tudo que entra aqui é anônimo. */
@Service
public class Metricas {

  public record Serie(Instant minuto, Map<String, Long> valores) {}

  public record Resumo(int minutos, Map<String, Long> totais, List<Serie> serie) {}

  public record Emissao(
      int amostras, Double p50, Double p95, double metaSegundos, boolean atendeMeta) {}

  private final JdbcTemplate jdbc;
  private final MeterRegistry registro;

  public Metricas(JdbcTemplate jdbc, MeterRegistry registro) {
    this.jdbc = jdbc;
    this.registro = registro;
    // o Grafana lê o p95 de emissão da janela de 15 min a cada raspagem
    Gauge.builder("pulsepass.emissao.p95.segundos", this, m -> m.p95Seguro(15))
        .description("p95 do tempo entre PedidoPago e IngressoEmitido (RNF08)")
        .register(registro);
  }

  /** Soma 1 ao minuto em que o evento aconteceu (idempotência é de quem chama: eventId). */
  public void contar(String nome, Instant quando) {
    jdbc.update(
        """
        insert into metrica_minuto(bucket, nome, qtd) values (?, ?, 1)
        on conflict (bucket, nome) do update set qtd = metrica_minuto.qtd + 1
        """,
        Timestamp.from(Agregador.minuto(quando)),
        nome);
    registro.counter("pulsepass.telemetria.eventos", "tipo", nome).increment();
  }

  public void registrarPago(UUID pedidoId, Instant quando) {
    jdbc.update(
        """
        insert into emissao_tempo(pedido_id, pago_em) values (?, ?)
        on conflict (pedido_id) do update set pago_em = excluded.pago_em
        """,
        pedidoId,
        Timestamp.from(quando));
  }

  /** Um pedido pode ter vários ingressos: vale o último, quando todos já estavam disponíveis. */
  public void registrarEmitido(UUID pedidoId, Instant quando) {
    jdbc.update(
        """
        insert into emissao_tempo(pedido_id, emitido_em) values (?, ?)
        on conflict (pedido_id) do update
          set emitido_em = greatest(coalesce(emissao_tempo.emitido_em, excluded.emitido_em),
                                    excluded.emitido_em)
        """,
        pedidoId,
        Timestamp.from(quando));
  }

  public Resumo resumo(int minutos) {
    var desde = Timestamp.from(Instant.now().minus(minutos, ChronoUnit.MINUTES));
    Map<String, Long> totais = new TreeMap<>();
    Map<Instant, Map<String, Long>> porMinuto = new TreeMap<>();
    jdbc.query(
        "select bucket, nome, qtd from metrica_minuto where bucket >= ? order by bucket",
        rs -> {
          Instant minuto = rs.getTimestamp("bucket").toInstant();
          String nome = rs.getString("nome");
          long qtd = rs.getLong("qtd");
          totais.merge(nome, qtd, Long::sum);
          porMinuto.computeIfAbsent(minuto, m -> new LinkedHashMap<>()).put(nome, qtd);
        },
        desde);
    var serie =
        porMinuto.entrySet().stream().map(e -> new Serie(e.getKey(), e.getValue())).toList();
    return new Resumo(minutos, totais, serie);
  }

  public Emissao emissao(int minutos) {
    double[] segundos = tempos(minutos);
    OptionalDouble p95 = Percentil.de(segundos, 0.95);
    OptionalDouble p50 = Percentil.de(segundos, 0.50);
    return new Emissao(
        segundos.length,
        p50.isPresent() ? p50.getAsDouble() : null,
        p95.isPresent() ? p95.getAsDouble() : null,
        Percentil.META_EMISSAO_SEGUNDOS,
        Percentil.atendeMeta(p95));
  }

  private double[] tempos(int minutos) {
    var desde = Timestamp.from(Instant.now().minus(minutos, ChronoUnit.MINUTES));
    return jdbc
        .queryForList(
            """
            select extract(epoch from (emitido_em - pago_em))::float8
            from emissao_tempo
            where pago_em is not null and emitido_em is not null and emitido_em >= ?
            """,
            Double.class,
            desde)
        .stream()
        .mapToDouble(Double::doubleValue)
        .toArray();
  }

  private double p95Seguro(int minutos) {
    try {
      return Percentil.de(tempos(minutos), 0.95).orElse(Double.NaN);
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}
