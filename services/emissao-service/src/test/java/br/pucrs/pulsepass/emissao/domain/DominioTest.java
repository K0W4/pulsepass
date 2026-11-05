package br.pucrs.pulsepass.emissao.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucrs.pulsepass.common.TransicaoInvalida;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DominioTest {

  private static final String SEGREDO = "segredo-de-teste-com-mais-de-32-caracteres";

  @Test
  void qrGeradoEVerificadoDevolveOMesmoId() {
    var qr = new QrAssinador(SEGREDO);
    UUID id = UUID.randomUUID();
    assertThat(qr.verificar(qr.gerar(id))).contains(id);
  }

  @Test
  void qrAlteradoOuMalFormadoEhRejeitado() {
    var qr = new QrAssinador(SEGREDO);
    UUID id = UUID.randomUUID();
    String codigo = qr.gerar(id);

    assertThat(qr.verificar(codigo.substring(0, codigo.length() - 2) + "xx")).isEmpty();
    assertThat(qr.verificar(codigo.replace(id.toString(), UUID.randomUUID().toString()))).isEmpty();
    assertThat(qr.verificar("PP1." + id)).isEmpty();
    assertThat(qr.verificar("XX." + id + ".abc")).isEmpty();
    assertThat(qr.verificar("PP1.nao-e-uuid.abc")).isEmpty();
    assertThat(qr.verificar(null)).isEmpty();
  }

  @Test
  void qrAssinadoPorOutroSegredoNaoValida() {
    UUID id = UUID.randomUUID();
    String forjado = new QrAssinador("outro-segredo-com-mais-de-32-caracteres-xx").gerar(id);
    assertThat(new QrAssinador(SEGREDO).verificar(forjado)).isEmpty();
  }

  @Test
  void segredoCurtoNaoEhAceito() {
    assertThatThrownBy(() -> new QrAssinador("curto")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QrAssinador(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void segredoComExatamente32CaracteresEhOLimiteAceito() {
    new QrAssinador("a".repeat(32));
    assertThatThrownBy(() -> new QrAssinador("a".repeat(31)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ingressoEmitidoPodeSerUtilizadoOuCancelado() {
    assertThat(IngressoStatus.EMITIDO.para(IngressoStatus.UTILIZADO))
        .isEqualTo(IngressoStatus.UTILIZADO);
    assertThat(IngressoStatus.EMITIDO.para(IngressoStatus.CANCELADO))
        .isEqualTo(IngressoStatus.CANCELADO);
  }

  @Test
  void utilizadoECanceladoSaoFinaisEEmitidoNaoRepete() {
    for (IngressoStatus final_ :
        new IngressoStatus[] {IngressoStatus.UTILIZADO, IngressoStatus.CANCELADO}) {
      for (IngressoStatus destino : IngressoStatus.values()) {
        assertThatThrownBy(() -> final_.para(destino)).isInstanceOf(TransicaoInvalida.class);
      }
    }
    assertThatThrownBy(() -> IngressoStatus.EMITIDO.para(IngressoStatus.EMITIDO))
        .isInstanceOf(TransicaoInvalida.class);
  }
}
