package br.pucrs.pulsepass.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucrs.pulsepass.common.TransicaoInvalida;
import org.junit.jupiter.api.Test;

class DominioTest {

  @Test
  void cnpjNumericoValidoComEPontuacaoOuSem() {
    assertThat(Cnpj.valido("11.222.333/0001-81")).isTrue();
    assertThat(Cnpj.valido("11222333000181")).isTrue();
    assertThat(Cnpj.valido("11.444.777/0001-61")).isTrue();
    assertThat(Cnpj.valido("12.345.678/0001-95")).isTrue();
    assertThat(Cnpj.valido(" 11 222 333 0001 81 ")).isTrue();
  }

  @Test
  void cnpjAlfanumericoDaReceita() {
    assertThat(Cnpj.valido("12.ABC.345/01DE-35")).isTrue(); // exemplo oficial
    assertThat(Cnpj.valido("12abc34501de35")).isTrue(); // minúsculas são aceitas
    assertThat(Cnpj.valido("12.ABC.345/01DE-36")).isFalse();
  }

  @Test
  void cnpjComDigitoErradoOuFormatoErradoEInvalido() {
    assertThat(Cnpj.valido("11.222.333/0001-82")).isFalse(); // 2º dígito
    assertThat(Cnpj.valido("11.222.333/0001-91")).isFalse(); // 1º dígito
    assertThat(Cnpj.valido("11.222.333/0001")).isFalse(); // curto
    assertThat(Cnpj.valido("11.222.333/0001-811")).isFalse(); // longo
    assertThat(Cnpj.valido("11.222.333/00A1-81"))
        .isFalse(); // dígitos verificadores precisam ser números
    assertThat(Cnpj.valido("11.222.333/0001-8A")).isFalse();
    assertThat(Cnpj.valido("")).isFalse();
    assertThat(Cnpj.valido(null)).isFalse();
  }

  @Test
  void cnpjComTodosOsCaracteresIguaisEInvalidoMesmoComDigitosCertos() {
    assertThat(Cnpj.valido("00000000000000")).isFalse(); // o cálculo fecha (00), mas não existe
    assertThat(Cnpj.valido("11111111111111")).isFalse();
  }

  @Test
  void normalizarTiraSeparadoresEPassaParaMaiusculas() {
    assertThat(Cnpj.normalizar("12.abc.345/01de-35")).isEqualTo("12ABC34501DE35");
    assertThat(Cnpj.normalizar("11 222 333")).isEqualTo("11222333");
    assertThat(Cnpj.normalizar(null)).isNull();
  }

  @Test
  void solicitacaoPendenteSoVaiParaAprovadaOuRejeitada() {
    assertThat(SolicitacaoStatus.PENDENTE.para(SolicitacaoStatus.APROVADA))
        .isEqualTo(SolicitacaoStatus.APROVADA);
    assertThat(SolicitacaoStatus.PENDENTE.para(SolicitacaoStatus.REJEITADA))
        .isEqualTo(SolicitacaoStatus.REJEITADA);
    assertThatThrownBy(() -> SolicitacaoStatus.PENDENTE.para(SolicitacaoStatus.PENDENTE))
        .isInstanceOf(TransicaoInvalida.class);
  }

  @Test
  void estadosFinaisNaoMudam() {
    for (var final_ :
        new SolicitacaoStatus[] {SolicitacaoStatus.APROVADA, SolicitacaoStatus.REJEITADA}) {
      for (var destino : SolicitacaoStatus.values()) {
        assertThatThrownBy(() -> final_.para(destino)).isInstanceOf(TransicaoInvalida.class);
      }
    }
  }
}
