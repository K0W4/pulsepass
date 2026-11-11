package br.pucrs.pulsepass.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceParentTest {

  @Test
  void formataNoPadraoW3C() {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String spanId = "00f067aa0ba902b7";
    assertThat(TraceParent.formatar(traceId, spanId, true))
        .isEqualTo("00-" + traceId + "-" + spanId + "-01");
    assertThat(TraceParent.formatar(traceId, spanId, false))
        .isEqualTo("00-" + traceId + "-" + spanId + "-00");
  }
}
