package br.pucrs.pulsepass.inventario.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucrs.pulsepass.common.TransicaoInvalida;
import org.junit.jupiter.api.Test;

class DominioTest {

  @Test
  void reservaAtivaPodeSerEncerradaDeTresFormas() {
    assertThat(ReservaStatus.ATIVA.para(ReservaStatus.CONFIRMADA))
        .isEqualTo(ReservaStatus.CONFIRMADA);
    assertThat(ReservaStatus.ATIVA.para(ReservaStatus.EXPIRADA)).isEqualTo(ReservaStatus.EXPIRADA);
    assertThat(ReservaStatus.ATIVA.para(ReservaStatus.CANCELADA))
        .isEqualTo(ReservaStatus.CANCELADA);
  }

  @Test
  void reservaEncerradaNaoMudaMais() {
    for (ReservaStatus final_ :
        new ReservaStatus[] {
          ReservaStatus.CONFIRMADA, ReservaStatus.EXPIRADA, ReservaStatus.CANCELADA
        }) {
      for (ReservaStatus destino : ReservaStatus.values()) {
        assertThatThrownBy(() -> final_.para(destino)).isInstanceOf(TransicaoInvalida.class);
      }
    }
  }

  @Test
  void reservaAtivaNaoVoltaParaAtiva() {
    assertThatThrownBy(() -> ReservaStatus.ATIVA.para(ReservaStatus.ATIVA))
        .isInstanceOf(TransicaoInvalida.class)
        .hasMessageContaining("ATIVA -> ATIVA");
  }

  @Test
  void assentoSegueLivreReservadoVendido() {
    assertThat(AssentoStatus.LIVRE.para(AssentoStatus.RESERVADO))
        .isEqualTo(AssentoStatus.RESERVADO);
    assertThat(AssentoStatus.RESERVADO.para(AssentoStatus.VENDIDO))
        .isEqualTo(AssentoStatus.VENDIDO);
    assertThat(AssentoStatus.RESERVADO.para(AssentoStatus.LIVRE)).isEqualTo(AssentoStatus.LIVRE);
  }

  @Test
  void assentoNaoPulaEtapasNemSaiDeVendido() {
    assertThatThrownBy(() -> AssentoStatus.LIVRE.para(AssentoStatus.VENDIDO))
        .isInstanceOf(TransicaoInvalida.class);
    assertThatThrownBy(() -> AssentoStatus.LIVRE.para(AssentoStatus.LIVRE))
        .isInstanceOf(TransicaoInvalida.class);
    for (AssentoStatus destino : AssentoStatus.values()) {
      assertThatThrownBy(() -> AssentoStatus.VENDIDO.para(destino))
          .isInstanceOf(TransicaoInvalida.class);
    }
  }

  @Test
  void loteCalculaDisponibilidade() {
    var lote = new Lote(100, 30, 20);
    assertThat(lote.disponivel()).isEqualTo(50);
    assertThat(lote.comporta(50)).isTrue();
    assertThat(lote.comporta(51)).isFalse();
    assertThat(lote.comporta(0)).isFalse();
  }
}
