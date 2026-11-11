package br.pucrs.pulsepass.common;

/** Formato W3C traceparent: versão, trace-id (32 hex), span-id (16 hex) e flags. */
public final class TraceParent {

  private TraceParent() {}

  public static String formatar(String traceId, String spanId, boolean amostrado) {
    return "00-" + traceId + "-" + spanId + "-" + (amostrado ? "01" : "00");
  }
}
