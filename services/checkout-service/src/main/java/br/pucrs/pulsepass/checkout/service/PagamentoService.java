package br.pucrs.pulsepass.checkout.service;

import static br.pucrs.pulsepass.checkout.domain.PedidoStatus.AGUARDANDO;
import static br.pucrs.pulsepass.checkout.domain.PedidoStatus.PAGO;
import static br.pucrs.pulsepass.checkout.domain.PedidoStatus.RESERVADO;

import br.pucrs.pulsepass.checkout.domain.PagamentoStatus;
import br.pucrs.pulsepass.checkout.domain.PedidoStatus;
import br.pucrs.pulsepass.checkout.service.Eventos.Item;
import br.pucrs.pulsepass.checkout.service.Eventos.PagamentoRecusado;
import br.pucrs.pulsepass.checkout.service.Eventos.PedidoMudou;
import br.pucrs.pulsepass.checkout.service.Eventos.PedidoPago;
import br.pucrs.pulsepass.checkout.service.GatewayPagamentoClient.GatewayIndisponivel;
import br.pucrs.pulsepass.common.Conflito;
import br.pucrs.pulsepass.common.NaoEncontrado;
import br.pucrs.pulsepass.common.Outbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pagamento idempotente (RNF03): chave única, gateway fora de transação, resposta reaproveitada.
 */
@Service
public class PagamentoService {

  public record Resposta(int status, String corpo) {}

  private static final Logger log = LoggerFactory.getLogger(PagamentoService.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Outbox outbox;
  private final GatewayPagamentoClient gateway;
  private final ObjectMapper mapper;

  public PagamentoService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      Outbox outbox,
      GatewayPagamentoClient gateway,
      ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.outbox = outbox;
    this.gateway = gateway;
    this.mapper = mapper;
  }

  private record Iniciado(Resposta repetida, UUID pagamentoId, BigDecimal valor) {}

  public Resposta pagar(String clienteId, String idempotencyKey, UUID pedidoId) {
    String chave = clienteId + ":" + idempotencyKey;
    Iniciado inicio = tx.execute(s -> iniciar(clienteId, chave, pedidoId));
    if (inicio.repetida() != null) {
      return inicio.repetida();
    }
    GatewayPagamentoClient.Resultado resultado;
    try {
      resultado = gateway.cobrar(inicio.pagamentoId(), inicio.valor());
    } catch (GatewayIndisponivel e) {
      tx.executeWithoutResult(
          s -> falharInfra(chave, pedidoId, inicio.pagamentoId(), e.getMessage()));
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "gateway de pagamento indisponível");
    }
    return tx.execute(s -> concluir(chave, pedidoId, inicio.pagamentoId(), resultado));
  }

  /** TX1: registra a chave, move o pedido para AGUARDANDO e cria o pagamento PENDENTE. */
  private Iniciado iniciar(String clienteId, String chave, UUID pedidoId) {
    String hash = sha256(pedidoId.toString());
    int novo =
        jdbc.update(
            "insert into chave_idempotencia(chave, hash_requisicao) values (?, ?) on conflict do nothing",
            chave,
            hash);
    if (novo == 0) {
      var c =
          jdbc.queryForMap(
              "select hash_requisicao, pronto, resposta_status, resposta_corpo from chave_idempotencia where chave = ?",
              chave);
      if (!hash.equals(c.get("hash_requisicao"))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key já usada com outro pedido");
      }
      if (!Boolean.TRUE.equals(c.get("pronto"))) {
        throw new Conflito("pagamento em processamento para esta Idempotency-Key");
      }
      return new Iniciado(
          new Resposta((Integer) c.get("resposta_status"), (String) c.get("resposta_corpo")),
          null,
          null);
    }
    var pedido =
        jdbc.queryForList(
            "select status, total, expira_em from pedido where id = ? and cliente_id = ? for update",
            pedidoId,
            clienteId);
    if (pedido.isEmpty()) {
      throw new NaoEncontrado("pedido não encontrado");
    }
    var p = pedido.get(0);
    PedidoStatus atual = PedidoStatus.valueOf((String) p.get("status"));
    Timestamp expira = (Timestamp) p.get("expira_em");
    if (atual != RESERVADO || expira == null || !expira.toInstant().isAfter(Instant.now())) {
      throw new Conflito("pedido não está pronto para pagamento (status " + atual + ")");
    }
    UUID pagamentoId = UUID.randomUUID();
    BigDecimal valor = (BigDecimal) p.get("total");
    jdbc.update(
        "insert into pagamento(id, pedido_id, chave, status, valor) values (?, ?, ?, ?, ?)",
        pagamentoId,
        pedidoId,
        chave,
        PagamentoStatus.PENDENTE.name(),
        valor);
    jdbc.update(
        "update pedido set status = ? where id = ?", atual.para(AGUARDANDO).name(), pedidoId);
    UUID reservaId =
        jdbc.queryForObject("select reserva_id from pedido where id = ?", UUID.class, pedidoId);
    outbox.add(
        Eventos.TOPICO_PEDIDO,
        pedidoId.toString(),
        "PedidoAguardandoPagamento",
        new PedidoMudou(pedidoId, reservaId, valor, expira.toInstant()));
    return new Iniciado(null, pagamentoId, valor);
  }

  /** TX2: aprova (PAGO) ou recusa (volta a RESERVADO) e guarda a resposta na chave. */
  private Resposta concluir(
      String chave, UUID pedidoId, UUID pagamentoId, GatewayPagamentoClient.Resultado r) {
    var pedido =
        jdbc.queryForMap(
            "select status, reserva_id, cliente_id from pedido where id = ? for update", pedidoId);
    PedidoStatus atual = PedidoStatus.valueOf((String) pedido.get("status"));
    UUID reservaId = (UUID) pedido.get("reserva_id");
    PagamentoStatus novo = r.aprovado() ? PagamentoStatus.APROVADO : PagamentoStatus.RECUSADO;
    jdbc.update(
        "update pagamento set status = ?, gateway_ref = ?, motivo = ? where id = ?",
        PagamentoStatus.PENDENTE.para(novo).name(),
        r.referencia(),
        r.motivo(),
        pagamentoId);
    Resposta resposta;
    if (r.aprovado() && atual == AGUARDANDO) {
      jdbc.update("update pedido set status = ? where id = ?", atual.para(PAGO).name(), pedidoId);
      outbox.add(
          Eventos.TOPICO_PEDIDO,
          pedidoId.toString(),
          "PedidoPago",
          pedidoPago(pedidoId, reservaId, pedido));
      resposta =
          resposta(200, Map.of("pedidoId", pedidoId, "status", "PAGO", "pagamentoId", pagamentoId));
    } else if (r.aprovado()) {
      // Aprovação tardia, com o pedido já expirado: limitação 1.4 (estorno simulado).
      log.warn(
          "Pagamento {} aprovado com pedido {} em {}: estorno simulado",
          pagamentoId,
          pedidoId,
          atual);
      resposta =
          resposta(
              409,
              Map.of("pedidoId", pedidoId, "status", atual.name(), "motivo", "PEDIDO_EXPIRADO"));
    } else {
      if (atual == AGUARDANDO) {
        jdbc.update(
            "update pedido set status = ? where id = ?", atual.para(RESERVADO).name(), pedidoId);
      }
      outbox.add(
          Eventos.TOPICO_PEDIDO,
          pedidoId.toString(),
          "PagamentoRecusado",
          new PagamentoRecusado(pedidoId, reservaId, r.motivo(), Instant.now()));
      resposta =
          resposta(
              402,
              Map.of(
                  "pedidoId",
                  pedidoId,
                  "status",
                  "RECUSADO",
                  "motivo",
                  String.valueOf(r.motivo())));
    }
    jdbc.update(
        "update chave_idempotencia set pronto = true, resposta_status = ?, resposta_corpo = ? where chave = ?",
        resposta.status(),
        resposta.corpo(),
        chave);
    return resposta;
  }

  /** Falha de infraestrutura: o pedido volta a RESERVADO e a mesma chave pode ser reutilizada. */
  private void falharInfra(String chave, UUID pedidoId, UUID pagamentoId, String motivo) {
    // a chave volta a ficar livre: o pagamento falho guarda uma chave própria, não reutilizável
    jdbc.update(
        "update pagamento set status = ?, motivo = ?, chave = chave || '#falha:' || id where id = ?",
        PagamentoStatus.PENDENTE.para(PagamentoStatus.RECUSADO).name(),
        motivo,
        pagamentoId);
    var pedido =
        jdbc.queryForMap("select status, reserva_id from pedido where id = ? for update", pedidoId);
    if (PedidoStatus.valueOf((String) pedido.get("status")) == AGUARDANDO) {
      jdbc.update(
          "update pedido set status = ? where id = ?", AGUARDANDO.para(RESERVADO).name(), pedidoId);
    }
    outbox.add(
        Eventos.TOPICO_PEDIDO,
        pedidoId.toString(),
        "PagamentoRecusado",
        new PagamentoRecusado(
            pedidoId, (UUID) pedido.get("reserva_id"), "GATEWAY_INDISPONIVEL", Instant.now()));
    jdbc.update("delete from chave_idempotencia where chave = ?", chave);
  }

  private PedidoPago pedidoPago(UUID pedidoId, UUID reservaId, Map<String, Object> pedido) {
    var ref =
        jdbc.queryForMap(
            "select evento_id, evento_nome from reserva_ref where reserva_id = ?", reservaId);
    List<Item> itens =
        jdbc.query(
            "select assento_id, lote_id, quantidade, preco_unitario, descricao from pedido_item where pedido_id = ?",
            (rs, i) ->
                new Item(
                    rs.getObject("assento_id", UUID.class),
                    rs.getObject("lote_id", UUID.class),
                    rs.getInt("quantidade"),
                    rs.getBigDecimal("preco_unitario"),
                    rs.getString("descricao")),
            pedidoId);
    BigDecimal total =
        jdbc.queryForObject("select total from pedido where id = ?", BigDecimal.class, pedidoId);
    return new PedidoPago(
        pedidoId,
        reservaId,
        (String) pedido.get("cliente_id"),
        (UUID) ref.get("evento_id"),
        (String) ref.get("evento_nome"),
        itens,
        total,
        Instant.now());
  }

  private Resposta resposta(int status, Map<String, Object> corpo) {
    try {
      return new Resposta(status, mapper.writeValueAsString(corpo));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(String texto) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
