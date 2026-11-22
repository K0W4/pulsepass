package br.pucrs.pulsepass.telemetria.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class DominioTest {

  @Test
  void cadaEventoContadoTemUmaMetrica() {
    assertThat(Agregador.metrica(Agregador.TOPICO_RESERVA, "ReservaCriada"))
        .contains("reservas_criadas");
    assertThat(Agregador.metrica(Agregador.TOPICO_RESERVA, "ReservaExpirada"))
        .contains("reservas_expiradas");
    assertThat(Agregador.metrica(Agregador.TOPICO_RESERVA, "ReservaCancelada"))
        .contains("reservas_canceladas");
    assertThat(Agregador.metrica(Agregador.TOPICO_PEDIDO, "PedidoPago")).contains("pedidos_pagos");
    assertThat(Agregador.metrica(Agregador.TOPICO_PEDIDO, "PagamentoRecusado"))
        .contains("pagamentos_recusados");
    assertThat(Agregador.metrica(Agregador.TOPICO_PEDIDO, "PedidoExpirado"))
        .contains("pedidos_expirados");
    assertThat(Agregador.metrica(Agregador.TOPICO_INGRESSO, "IngressoEmitido"))
        .contains("ingressos_emitidos");
    assertThat(Agregador.metrica(Agregador.TOPICO_INGRESSO, "IngressoUtilizado"))
        .contains("ingressos_utilizados");
    assertThat(Agregador.metrica(Agregador.TOPICO_IAM, "OrganizadorAprovado"))
        .contains("organizadores_aprovados");
    assertThat(Agregador.metrica(Agregador.TOPICO_NAVEGACAO, "PaginaVista"))
        .contains("paginas_vistas");
  }

  @Test
  void tipoOuTopicoDesconhecidoNaoViraMetrica() {
    assertThat(Agregador.metrica(Agregador.TOPICO_PEDIDO, "PedidoReservado")).isEmpty();
    // o mesmo tipo em outro tópico não conta
    assertThat(Agregador.metrica(Agregador.TOPICO_INGRESSO, "PedidoPago")).isEmpty();
    assertThat(Agregador.metrica("outro.topico", "PedidoPago")).isEmpty();
  }

  @Test
  void aTelemetriaAssinaExatamenteOsTopicosQueConta() {
    assertThat(Agregador.TOPICOS)
        .containsExactlyInAnyOrder(
            "inventario.reserva.v1",
            "checkout.pedido.v1",
            "emissao.ingresso.v1",
            "iam.usuario.v1",
            "telemetria.navegacao.v1");
  }

  @Test
  void paginaDesconhecidaOuMaliciosaViraOutra() {
    assertThat(Agregador.pagina("catalogo")).isEqualTo("catalogo");
    assertThat(Agregador.pagina("  CHECKOUT ")).isEqualTo("checkout");
    for (String p :
        new String[] {
          "catalogo",
          "evento",
          "assentos",
          "checkout",
          "ingressos",
          "organizador",
          "portaria",
          "admin",
          "painel",
          "fila"
        }) {
      assertThat(Agregador.pagina(p)).isEqualTo(p);
    }
    assertThat(Agregador.pagina("../../etc/passwd")).isEqualTo("outra");
    assertThat(Agregador.pagina("")).isEqualTo("outra");
    assertThat(Agregador.pagina(null)).isEqualTo("outra");
  }

  @Test
  void minutoTruncaParaOMinutoCheio() {
    assertThat(Agregador.minuto(Instant.parse("2027-01-01T10:15:42.987Z")))
        .isEqualTo(Instant.parse("2027-01-01T10:15:00Z"));
    assertThat(Agregador.minuto(Instant.parse("2027-01-01T10:15:00Z")))
        .isEqualTo(Instant.parse("2027-01-01T10:15:00Z"));
  }

  @Test
  void percentilInterpolaComoOSql() {
    double[] v = {40, 10, 30, 20}; // fora de ordem de propósito
    assertThat(Percentil.de(v, 0).getAsDouble()).isEqualTo(10);
    assertThat(Percentil.de(v, 1).getAsDouble()).isEqualTo(40);
    assertThat(Percentil.de(v, 0.5).getAsDouble()).isEqualTo(25);
    assertThat(Percentil.de(v, 0.95).getAsDouble()).isCloseTo(38.5, within(1e-9));
    assertThat(Percentil.de(new double[] {7}, 0.95).getAsDouble()).isEqualTo(7);
    assertThat(Percentil.de(new double[] {1, 2, 3, 4, 5}, 0.25).getAsDouble()).isEqualTo(2);
  }

  @Test
  void percentilNaoAlteraAEntradaEValidaOIntervalo() {
    double[] v = {3, 1, 2};
    Percentil.de(v, 0.5);
    assertThat(v).containsExactly(3, 1, 2);
    assertThat(Percentil.de(new double[0], 0.5)).isEmpty();
    assertThatThrownBy(() -> Percentil.de(v, -0.01)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Percentil.de(v, 1.01)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void metaDe30SegundosEInclusivaESemAmostraNaoAtende() {
    assertThat(Percentil.atendeMeta(OptionalDouble.of(29.99))).isTrue();
    assertThat(Percentil.atendeMeta(OptionalDouble.of(30.0))).isTrue();
    assertThat(Percentil.atendeMeta(OptionalDouble.of(30.01))).isFalse();
    assertThat(Percentil.atendeMeta(OptionalDouble.empty())).isFalse();
  }
}
