package br.pucrs.pulsepass.realtime.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.Set;

/**
 * Decide, para cada evento do Kafka, em que canal ele vai e o que o cliente recebe (US14, US27). Só
 * passa o mínimo necessário: nenhum dado pessoal (cliente, e-mail) sai daqui (RNF15).
 */
public final class Roteador {

  public static final String TOPICO_ASSENTO = "inventario.assento.v1";
  public static final String TOPICO_PEDIDO = "checkout.pedido.v1";
  public static final String TOPICO_INGRESSO = "emissao.ingresso.v1";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> TIPOS_ASSENTO =
      Set.of("AssentoReservado", "AssentoLiberado", "AssentoVendido");
  private static final Set<String> TIPOS_PEDIDO =
      Set.of(
          "PedidoReservado",
          "PedidoAguardandoPagamento",
          "PedidoPago",
          "PagamentoRecusado",
          "PedidoExpirado");

  private Roteador() {}

  /** Canal (ex.: {@code eventos/<id>}) e o texto JSON a entregar. */
  public record Entrega(String canal, String mensagem) {}

  public static Optional<Entrega> rotear(String topico, String envelopeJson) {
    if (envelopeJson == null) {
      return Optional.empty();
    }
    JsonNode envelope;
    try {
      envelope = JSON.readTree(envelopeJson);
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
    String tipo = texto(envelope, "eventType");
    JsonNode payload = envelope.get("payload");
    if (tipo == null || payload == null) {
      return Optional.empty();
    }
    return switch (topico) {
      case TOPICO_ASSENTO -> assento(tipo, payload);
      case TOPICO_PEDIDO -> pedido(tipo, payload);
      case TOPICO_INGRESSO -> ingresso(tipo, payload);
      default -> Optional.empty();
    };
  }

  private static Optional<Entrega> assento(String tipo, JsonNode p) {
    String evento = texto(p, "eventoId");
    if (!TIPOS_ASSENTO.contains(tipo) || evento == null) {
      return Optional.empty();
    }
    var m = base(tipo);
    m.put("assentoId", texto(p, "assentoId"));
    m.put("status", texto(p, "status"));
    return Optional.of(new Entrega("eventos/" + evento, m.toString()));
  }

  private static Optional<Entrega> pedido(String tipo, JsonNode p) {
    String pedido = texto(p, "pedidoId");
    if (!TIPOS_PEDIDO.contains(tipo) || pedido == null) {
      return Optional.empty();
    }
    var m = base(tipo);
    m.put("pedidoId", pedido);
    String motivo = texto(p, "motivo");
    if (motivo != null) {
      m.put("motivo", motivo);
    }
    return Optional.of(new Entrega("pedidos/" + pedido, m.toString()));
  }

  private static Optional<Entrega> ingresso(String tipo, JsonNode p) {
    String pedido = texto(p, "pedidoId");
    if (!"IngressoEmitido".equals(tipo) || pedido == null) {
      return Optional.empty();
    }
    var m = base(tipo);
    m.put("pedidoId", pedido);
    m.put("ingressoId", texto(p, "ingressoId"));
    return Optional.of(new Entrega("pedidos/" + pedido, m.toString()));
  }

  private static ObjectNode base(String tipo) {
    return JSON.createObjectNode().put("tipo", tipo);
  }

  private static String texto(JsonNode no, String campo) {
    JsonNode v = no == null ? null : no.get(campo);
    return v == null || v.isNull() ? null : v.asText();
  }
}
