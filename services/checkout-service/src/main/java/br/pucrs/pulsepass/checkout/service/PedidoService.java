package br.pucrs.pulsepass.checkout.service;

import static br.pucrs.pulsepass.checkout.domain.PedidoStatus.CRIADO;
import static br.pucrs.pulsepass.checkout.domain.PedidoStatus.EXPIRADO;
import static br.pucrs.pulsepass.checkout.domain.PedidoStatus.RESERVADO;

import br.pucrs.pulsepass.checkout.domain.PedidoStatus;
import br.pucrs.pulsepass.checkout.domain.Total;
import br.pucrs.pulsepass.checkout.service.Eventos.Item;
import br.pucrs.pulsepass.checkout.service.Eventos.PedidoExpirado;
import br.pucrs.pulsepass.checkout.service.Eventos.PedidoMudou;
import br.pucrs.pulsepass.checkout.service.Eventos.ReservaCriada;
import br.pucrs.pulsepass.common.Conflito;
import br.pucrs.pulsepass.common.NaoEncontrado;
import br.pucrs.pulsepass.common.Outbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Abertura do pedido e reação aos eventos de reserva do Inventário. */
@Service
public class PedidoService {

  private final JdbcTemplate jdbc;
  private final Outbox outbox;
  private final ObjectMapper mapper;

  public PedidoService(JdbcTemplate jdbc, Outbox outbox, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.mapper = mapper;
  }

  /** POST /pedidos: idempotente por reserva. Fica CRIADO se ReservaCriada ainda não chegou. */
  @Transactional
  public Map<String, Object> abrir(String clienteId, UUID reservaId) {
    var existente = buscarPorReserva(reservaId, clienteId);
    if (existente != null) {
      return existente;
    }
    var ref = jdbc.queryForList("select * from reserva_ref where reserva_id = ?", reservaId);
    if (!ref.isEmpty()) {
      var r = ref.get(0);
      if (!clienteId.equals(r.get("cliente_id"))) {
        throw new NaoEncontrado("reserva não encontrada");
      }
      if (!"ATIVA".equals(r.get("status"))) {
        throw new Conflito("reserva não está ativa");
      }
    }
    UUID pedidoId = UUID.randomUUID();
    try {
      jdbc.update(
          "insert into pedido(id, cliente_id, reserva_id, status) values (?, ?, ?, ?)",
          pedidoId,
          clienteId,
          reservaId,
          CRIADO.name());
    } catch (DuplicateKeyException corrida) {
      return buscarPorReserva(reservaId, clienteId);
    }
    if (!ref.isEmpty()) {
      promover(pedidoId, reservaId, ref.get(0));
    }
    return detalhar(pedidoId, clienteId);
  }

  public Map<String, Object> detalhar(UUID pedidoId, String clienteId) {
    var pedido =
        jdbc.queryForList(
            "select id, reserva_id, status, total, expira_em, motivo_expiracao from pedido where id = ? and cliente_id = ?",
            pedidoId,
            clienteId);
    if (pedido.isEmpty()) {
      throw new NaoEncontrado("pedido não encontrado");
    }
    var itens =
        jdbc.queryForList(
            "select assento_id, lote_id, descricao, quantidade, preco_unitario from pedido_item where pedido_id = ?",
            pedidoId);
    var resultado = new java.util.LinkedHashMap<>(pedido.get(0));
    resultado.put("itens", itens);
    return resultado;
  }

  /** ReservaCriada: grava a projeção e promove o pedido que já estava esperando. */
  @Transactional
  public void aplicarReservaCriada(ReservaCriada e) {
    String itens;
    try {
      itens = mapper.writeValueAsString(e.itens());
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
    jdbc.update(
        """
        insert into reserva_ref(reserva_id, cliente_id, evento_id, evento_nome, itens, expira_em)
        values (?, ?, ?, ?, ?::jsonb, ?) on conflict do nothing
        """,
        e.reservaId(),
        e.clienteId(),
        e.eventoId(),
        e.eventoNome(),
        itens,
        Timestamp.from(e.expiraEm()));
    var pedidos =
        jdbc.queryForList(
            "select id, cliente_id from pedido where reserva_id = ? and status = 'CRIADO' for update",
            e.reservaId());
    for (var p : pedidos) {
      UUID pedidoId = (UUID) p.get("id");
      if (!e.clienteId().equals(p.get("cliente_id"))) {
        expirar(pedidoId, e.reservaId(), "CLIENTE_DIVERGENTE");
      } else {
        promover(
            pedidoId,
            e.reservaId(),
            jdbc.queryForMap("select * from reserva_ref where reserva_id = ?", e.reservaId()));
      }
    }
  }

  /** ReservaExpirada ou ReservaCancelada: o pedido ainda aberto vira EXPIRADO. */
  @Transactional
  public void aplicarReservaEncerrada(UUID reservaId, String motivo, String statusReserva) {
    jdbc.update("update reserva_ref set status = ? where reserva_id = ?", statusReserva, reservaId);
    var pedidos =
        jdbc.queryForList(
            "select id, status from pedido where reserva_id = ? for update", reservaId);
    for (var p : pedidos) {
      if (PedidoStatus.valueOf((String) p.get("status")).encerravel()) {
        expirar((UUID) p.get("id"), reservaId, motivo);
      }
    }
  }

  private void promover(UUID pedidoId, UUID reservaId, Map<String, Object> ref) {
    List<Item> itens = lerItens(ref.get("itens"));
    BigDecimal total =
        Total.de(
            itens.stream().map(i -> new Total.Linha(i.quantidade(), i.precoUnitario())).toList());
    for (Item i : itens) {
      jdbc.update(
          """
          insert into pedido_item(id, pedido_id, assento_id, lote_id, descricao, quantidade, preco_unitario)
          values (?, ?, ?, ?, ?, ?, ?)
          """,
          UUID.randomUUID(),
          pedidoId,
          i.assentoId(),
          i.loteId(),
          i.descricao(),
          i.quantidade(),
          i.precoUnitario());
    }
    Instant expira = ((Timestamp) ref.get("expira_em")).toInstant();
    jdbc.update(
        "update pedido set status = ?, total = ?, expira_em = ? where id = ?",
        CRIADO.para(RESERVADO).name(),
        total,
        Timestamp.from(expira),
        pedidoId);
    outbox.add(
        Eventos.TOPICO_PEDIDO,
        pedidoId.toString(),
        "PedidoReservado",
        new PedidoMudou(pedidoId, reservaId, total, expira));
  }

  private void expirar(UUID pedidoId, UUID reservaId, String motivo) {
    String atual =
        jdbc.queryForObject("select status from pedido where id = ?", String.class, pedidoId);
    jdbc.update(
        "update pedido set status = ?, motivo_expiracao = ? where id = ?",
        PedidoStatus.valueOf(atual).para(EXPIRADO).name(),
        motivo,
        pedidoId);
    outbox.add(
        Eventos.TOPICO_PEDIDO,
        pedidoId.toString(),
        "PedidoExpirado",
        new PedidoExpirado(pedidoId, reservaId, motivo, Instant.now()));
  }

  private Map<String, Object> buscarPorReserva(UUID reservaId, String clienteId) {
    var achado =
        jdbc.queryForList("select id, cliente_id from pedido where reserva_id = ?", reservaId);
    if (achado.isEmpty()) {
      return null;
    }
    if (!clienteId.equals(achado.get(0).get("cliente_id"))) {
      throw new NaoEncontrado("reserva não encontrada");
    }
    return detalhar((UUID) achado.get(0).get("id"), clienteId);
  }

  private List<Item> lerItens(Object json) {
    try {
      return mapper.readValue(json.toString(), new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("itens da reserva inválidos", e);
    }
  }
}
