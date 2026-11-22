package br.pucrs.pulsepass.realtime.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RoteadorTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String envelope(String tipo, String payload) {
    return "{\"eventId\":\"e1\",\"eventType\":\"" + tipo + "\",\"payload\":" + payload + "}";
  }

  @Test
  void mudancaDeAssentoVaiParaOCanalDoEvento() throws Exception {
    var e =
        Roteador.rotear(
                Roteador.TOPICO_ASSENTO,
                envelope(
                    "AssentoReservado",
                    "{\"eventoId\":\"ev-1\",\"assentoId\":\"as-9\",\"status\":\"RESERVADO\"}"))
            .orElseThrow();
    assertThat(e.canal()).isEqualTo("eventos/ev-1");
    var json = JSON.readTree(e.mensagem());
    assertThat(json.get("tipo").asText()).isEqualTo("AssentoReservado");
    assertThat(json.get("assentoId").asText()).isEqualTo("as-9");
    assertThat(json.get("status").asText()).isEqualTo("RESERVADO");
  }

  @Test
  void todosOsTiposDeAssentoSaoEncaminhados() {
    for (String tipo : new String[] {"AssentoReservado", "AssentoLiberado", "AssentoVendido"}) {
      assertThat(
              Roteador.rotear(
                  Roteador.TOPICO_ASSENTO,
                  envelope(tipo, "{\"eventoId\":\"ev\",\"assentoId\":\"a\",\"status\":\"X\"}")))
          .as(tipo)
          .isPresent();
    }
    assertThat(
            Roteador.rotear(
                Roteador.TOPICO_ASSENTO,
                envelope("AssentoQualquer", "{\"eventoId\":\"ev\",\"assentoId\":\"a\"}")))
        .isEmpty();
  }

  @Test
  void statusDoPedidoVaiParaOCanalDoPedidoComMotivoQuandoHa() throws Exception {
    var recusado =
        Roteador.rotear(
                Roteador.TOPICO_PEDIDO,
                envelope(
                    "PagamentoRecusado",
                    "{\"pedidoId\":\"p-1\",\"reservaId\":\"r\",\"motivo\":\"SALDO\"}"))
            .orElseThrow();
    assertThat(recusado.canal()).isEqualTo("pedidos/p-1");
    var json = JSON.readTree(recusado.mensagem());
    assertThat(json.get("tipo").asText()).isEqualTo("PagamentoRecusado");
    assertThat(json.get("motivo").asText()).isEqualTo("SALDO");

    var aguardando =
        Roteador.rotear(
                Roteador.TOPICO_PEDIDO,
                envelope("PedidoAguardandoPagamento", "{\"pedidoId\":\"p-2\"}"))
            .orElseThrow();
    assertThat(JSON.readTree(aguardando.mensagem()).has("motivo")).isFalse();
    for (String tipo : new String[] {"PedidoReservado", "PedidoPago", "PedidoExpirado"}) {
      assertThat(Roteador.rotear(Roteador.TOPICO_PEDIDO, envelope(tipo, "{\"pedidoId\":\"p\"}")))
          .as(tipo)
          .isPresent();
    }
  }

  @Test
  void ingressoEmitidoVaiParaOCanalDoPedido() throws Exception {
    var e =
        Roteador.rotear(
                Roteador.TOPICO_INGRESSO,
                envelope("IngressoEmitido", "{\"pedidoId\":\"p-3\",\"ingressoId\":\"i-1\"}"))
            .orElseThrow();
    assertThat(e.canal()).isEqualTo("pedidos/p-3");
    assertThat(JSON.readTree(e.mensagem()).get("ingressoId").asText()).isEqualTo("i-1");
    // IngressoUtilizado não tem pedido: não vai a lugar nenhum
    assertThat(
            Roteador.rotear(
                Roteador.TOPICO_INGRESSO,
                envelope("IngressoUtilizado", "{\"ingressoId\":\"i-1\"}")))
        .isEmpty();
  }

  @Test
  void nenhumDadoPessoalSai() {
    String corpo =
        "{\"pedidoId\":\"p\",\"clienteId\":\"sub-secreto\",\"clienteEmail\":\"a@b.c\",\"total\":10}";
    for (String topico : new String[] {Roteador.TOPICO_PEDIDO, Roteador.TOPICO_INGRESSO}) {
      Roteador.rotear(topico, envelope("PedidoPago", corpo))
          .ifPresent(
              e ->
                  assertThat(e.mensagem())
                      .doesNotContain("sub-secreto", "a@b.c", "clienteId", "clienteEmail"));
    }
    var pago = Roteador.rotear(Roteador.TOPICO_PEDIDO, envelope("PedidoPago", corpo)).orElseThrow();
    assertThat(pago.mensagem()).doesNotContain("sub-secreto").doesNotContain("a@b.c");
  }

  @Test
  void entradasInvalidasSaoIgnoradas() {
    assertThat(Roteador.rotear(Roteador.TOPICO_PEDIDO, "isto nao e json")).isEmpty();
    assertThat(Roteador.rotear(Roteador.TOPICO_PEDIDO, null)).isEmpty();
    assertThat(Roteador.rotear(Roteador.TOPICO_PEDIDO, "{}")).isEmpty();
    assertThat(Roteador.rotear(Roteador.TOPICO_PEDIDO, "{\"eventType\":\"PedidoPago\"}")).isEmpty();
    assertThat(Roteador.rotear(Roteador.TOPICO_PEDIDO, envelope("PedidoPago", "{}"))).isEmpty();
    assertThat(
            Roteador.rotear(Roteador.TOPICO_PEDIDO, envelope("PedidoPago", "{\"pedidoId\":null}")))
        .isEmpty();
    assertThat(Roteador.rotear("outro.topico", envelope("PedidoPago", "{\"pedidoId\":\"p\"}")))
        .isEmpty();
    assertThat(
            Roteador.rotear(
                Roteador.TOPICO_ASSENTO,
                envelope("AssentoReservado", "{\"assentoId\":\"a\",\"status\":\"X\"}")))
        .isEmpty();
  }
}
