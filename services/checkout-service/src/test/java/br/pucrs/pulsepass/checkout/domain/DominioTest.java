package br.pucrs.pulsepass.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucrs.pulsepass.common.TransicaoInvalida;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DominioTest {

  @Test
  void pedidoSegueOCaminhoFeliz() {
    var s = PedidoStatus.CRIADO.para(PedidoStatus.RESERVADO);
    s = s.para(PedidoStatus.AGUARDANDO);
    assertThat(s.para(PedidoStatus.PAGO)).isEqualTo(PedidoStatus.PAGO);
  }

  @Test
  void pagamentoRecusadoVoltaARESERVADO() {
    assertThat(PedidoStatus.AGUARDANDO.para(PedidoStatus.RESERVADO))
        .isEqualTo(PedidoStatus.RESERVADO);
  }

  @Test
  void qualquerEstadoAbertoPodeExpirar() {
    for (PedidoStatus aberto :
        new PedidoStatus[] {PedidoStatus.CRIADO, PedidoStatus.RESERVADO, PedidoStatus.AGUARDANDO}) {
      assertThat(aberto.para(PedidoStatus.EXPIRADO)).isEqualTo(PedidoStatus.EXPIRADO);
      assertThat(aberto.encerravel()).isTrue();
    }
  }

  @Test
  void pagoEExpiradoSaoFinais() {
    for (PedidoStatus final_ : new PedidoStatus[] {PedidoStatus.PAGO, PedidoStatus.EXPIRADO}) {
      assertThat(final_.encerravel()).isFalse();
      for (PedidoStatus destino : PedidoStatus.values()) {
        assertThatThrownBy(() -> final_.para(destino)).isInstanceOf(TransicaoInvalida.class);
      }
    }
  }

  @Test
  void pedidoNaoPulaEtapas() {
    assertThatThrownBy(() -> PedidoStatus.CRIADO.para(PedidoStatus.PAGO))
        .isInstanceOf(TransicaoInvalida.class);
    assertThatThrownBy(() -> PedidoStatus.CRIADO.para(PedidoStatus.AGUARDANDO))
        .isInstanceOf(TransicaoInvalida.class);
    assertThatThrownBy(() -> PedidoStatus.RESERVADO.para(PedidoStatus.PAGO))
        .isInstanceOf(TransicaoInvalida.class);
  }

  @Test
  void pagamentoDecideUmaUnicaVez() {
    assertThat(PagamentoStatus.PENDENTE.para(PagamentoStatus.APROVADO))
        .isEqualTo(PagamentoStatus.APROVADO);
    assertThat(PagamentoStatus.PENDENTE.para(PagamentoStatus.RECUSADO))
        .isEqualTo(PagamentoStatus.RECUSADO);
    assertThatThrownBy(() -> PagamentoStatus.APROVADO.para(PagamentoStatus.RECUSADO))
        .isInstanceOf(TransicaoInvalida.class);
    assertThatThrownBy(() -> PagamentoStatus.PENDENTE.para(PagamentoStatus.PENDENTE))
        .isInstanceOf(TransicaoInvalida.class);
  }

  @Test
  void totalSomaQuantidadeVezesPreco() {
    var total =
        Total.de(
            List.of(
                new Total.Linha(1, new BigDecimal("250.00")),
                new Total.Linha(3, new BigDecimal("33.33"))));
    assertThat(total).isEqualByComparingTo("349.99");
    assertThat(Total.de(List.of())).isEqualByComparingTo("0.00");
  }
}
