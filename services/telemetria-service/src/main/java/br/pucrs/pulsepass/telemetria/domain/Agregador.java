package br.pucrs.pulsepass.telemetria.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Classifica cada evento recebido em um nome de métrica e define o minuto em que ele conta. */
public final class Agregador {

  public static final String TOPICO_RESERVA = "inventario.reserva.v1";
  public static final String TOPICO_PEDIDO = "checkout.pedido.v1";
  public static final String TOPICO_INGRESSO = "emissao.ingresso.v1";
  public static final String TOPICO_IAM = "iam.usuario.v1";
  public static final String TOPICO_NAVEGACAO = "telemetria.navegacao.v1";

  public static final List<String> TOPICOS =
      List.of(TOPICO_RESERVA, TOPICO_PEDIDO, TOPICO_INGRESSO, TOPICO_IAM, TOPICO_NAVEGACAO);

  private static final Map<String, String> METRICAS =
      Map.of(
          TOPICO_RESERVA + ":ReservaCriada", "reservas_criadas",
          TOPICO_RESERVA + ":ReservaExpirada", "reservas_expiradas",
          TOPICO_RESERVA + ":ReservaCancelada", "reservas_canceladas",
          TOPICO_PEDIDO + ":PedidoPago", "pedidos_pagos",
          TOPICO_PEDIDO + ":PagamentoRecusado", "pagamentos_recusados",
          TOPICO_PEDIDO + ":PedidoExpirado", "pedidos_expirados",
          TOPICO_INGRESSO + ":IngressoEmitido", "ingressos_emitidos",
          TOPICO_INGRESSO + ":IngressoUtilizado", "ingressos_utilizados",
          TOPICO_IAM + ":OrganizadorAprovado", "organizadores_aprovados",
          TOPICO_NAVEGACAO + ":PaginaVista", "paginas_vistas");

  /** Lista fechada: a navegação vem de fora e não pode criar métricas sem limite. */
  private static final Set<String> PAGINAS =
      Set.of(
          "catalogo",
          "evento",
          "assentos",
          "checkout",
          "ingressos",
          "organizador",
          "portaria",
          "admin",
          "painel",
          "fila");

  private Agregador() {}

  /** Métrica de um evento; vazio para tipos que a telemetria não conta. */
  public static Optional<String> metrica(String topico, String tipo) {
    return Optional.ofNullable(METRICAS.get(topico + ":" + tipo));
  }

  /** Página conhecida ou "outra"; o resultado é seguro como parte do nome de uma métrica. */
  public static String pagina(String bruta) {
    if (bruta == null) {
      return "outra";
    }
    String p = bruta.trim().toLowerCase(Locale.ROOT);
    return PAGINAS.contains(p) ? p : "outra";
  }

  /** O minuto cheio em que o evento aconteceu (hora do evento, não a do processamento). */
  public static Instant minuto(Instant quando) {
    return quando.truncatedTo(ChronoUnit.MINUTES);
  }
}
