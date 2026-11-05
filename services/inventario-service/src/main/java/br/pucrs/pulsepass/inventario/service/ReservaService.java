package br.pucrs.pulsepass.inventario.service;

import static br.pucrs.pulsepass.inventario.domain.AssentoStatus.LIVRE;
import static br.pucrs.pulsepass.inventario.domain.AssentoStatus.RESERVADO;
import static br.pucrs.pulsepass.inventario.domain.AssentoStatus.VENDIDO;

import br.pucrs.pulsepass.common.Conflito;
import br.pucrs.pulsepass.common.Invalido;
import br.pucrs.pulsepass.common.NaoEncontrado;
import br.pucrs.pulsepass.common.Outbox;
import br.pucrs.pulsepass.inventario.api.Dtos.ItemLote;
import br.pucrs.pulsepass.inventario.api.Dtos.ReservaResponse;
import br.pucrs.pulsepass.inventario.domain.AssentoStatus;
import br.pucrs.pulsepass.inventario.domain.ReservaStatus;
import br.pucrs.pulsepass.inventario.service.Eventos.AssentoMudou;
import br.pucrs.pulsepass.inventario.service.Eventos.Item;
import br.pucrs.pulsepass.inventario.service.Eventos.ReservaCriada;
import br.pucrs.pulsepass.inventario.service.Eventos.ReservaEncerrada;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservaService {

  private static final Logger log = LoggerFactory.getLogger(ReservaService.class);

  private final JdbcTemplate jdbc;
  private final Outbox outbox;
  private final SeatLock lock;
  private final TransactionTemplate tx;
  private final Duration ttl;

  public ReservaService(
      JdbcTemplate jdbc,
      Outbox outbox,
      SeatLock lock,
      TransactionTemplate tx,
      @Value("${pulsepass.reserva.ttl:PT10M}") Duration ttl) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.lock = lock;
    this.tx = tx;
    this.ttl = ttl;
  }

  public ReservaResponse reservar(
      String clienteId, UUID eventoId, List<UUID> assentoIds, List<ItemLote> itensLote) {
    List<UUID> assentos =
        assentoIds == null ? List.of() : assentoIds.stream().distinct().sorted().toList();
    List<ItemLote> lotes = itensLote == null ? List.of() : itensLote;
    if (eventoId == null || (assentos.isEmpty() && lotes.isEmpty())) {
      throw new Invalido("informe eventoId e ao menos um assento ou item de lote");
    }
    if (lotes.stream().anyMatch(i -> i.loteId() == null || i.quantidade() <= 0)) {
      throw new Invalido("itens de lote exigem loteId e quantidade positiva");
    }
    List<String> chaves = lock.adquirir(assentos);
    try {
      return tx.execute(status -> gravar(clienteId, eventoId, assentos, lotes));
    } finally {
      lock.liberar(chaves);
    }
  }

  private ReservaResponse gravar(
      String clienteId, UUID eventoId, List<UUID> assentos, List<ItemLote> lotes) {
    String eventoNome =
        jdbc
            .query(
                "select nome from evento where id = ? and status = 'PUBLICADO'",
                (rs, i) -> rs.getString(1),
                eventoId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new NaoEncontrado("evento não encontrado"));
    UUID reservaId = UUID.randomUUID();
    Instant agora = Instant.now();
    Instant expira = agora.plus(ttl);
    jdbc.update(
        "insert into reserva(id, cliente_id, evento_id, status, criada_em, expira_em) values (?, ?, ?, ?, ?, ?)",
        reservaId,
        clienteId,
        eventoId,
        ReservaStatus.ATIVA.name(),
        Timestamp.from(agora),
        Timestamp.from(expira));

    List<Item> itens = new ArrayList<>();
    for (UUID assentoId : assentos) {
      if (!mover(assentoId, eventoId, LIVRE, RESERVADO)) {
        throw new Conflito("assento indisponível");
      }
      var a =
          jdbc.queryForMap(
              """
              select a.codigo, s.nome as setor, l.id as lote_id, l.preco
              from assento a join setor s on s.id = a.setor_id join lote l on l.id = a.lote_id
              where a.id = ?
              """,
              assentoId);
      jdbc.update(
          "insert into reserva_item(id, reserva_id, assento_id, lote_id, quantidade) values (?, ?, ?, ?, 1)",
          UUID.randomUUID(),
          reservaId,
          assentoId,
          a.get("lote_id"));
      itens.add(
          new Item(
              assentoId,
              (UUID) a.get("lote_id"),
              1,
              (java.math.BigDecimal) a.get("preco"),
              a.get("setor") + ", assento " + a.get("codigo")));
    }
    for (ItemLote il : lotes) {
      int n =
          jdbc.update(
              """
              update lote set qtd_reservada = qtd_reservada + ?
              where id = ? and evento_id = ? and qtd_reservada + qtd_vendida + ? <= quantidade
              """,
              il.quantidade(),
              il.loteId(),
              eventoId,
              il.quantidade());
      if (n != 1) {
        throw new Conflito("lote sem unidades disponíveis");
      }
      var l = jdbc.queryForMap("select nome, preco from lote where id = ?", il.loteId());
      jdbc.update(
          "insert into reserva_item(id, reserva_id, assento_id, lote_id, quantidade) values (?, ?, null, ?, ?)",
          UUID.randomUUID(),
          reservaId,
          il.loteId(),
          il.quantidade());
      itens.add(
          new Item(
              null,
              il.loteId(),
              il.quantidade(),
              (java.math.BigDecimal) l.get("preco"),
              "Lote " + l.get("nome")));
    }

    outbox.add(
        Eventos.TOPICO_RESERVA,
        reservaId.toString(),
        "ReservaCriada",
        new ReservaCriada(reservaId, clienteId, eventoId, eventoNome, itens, agora, expira));
    for (UUID assentoId : assentos) {
      outbox.add(
          Eventos.TOPICO_ASSENTO,
          eventoId.toString(),
          "AssentoReservado",
          new AssentoMudou(eventoId, assentoId, RESERVADO.name()));
    }
    return new ReservaResponse(reservaId, expira);
  }

  @Transactional
  public boolean expirar(UUID reservaId) {
    return encerrar(reservaId, ReservaStatus.EXPIRADA, "ReservaExpirada", "TEMPO_ESGOTADO");
  }

  @Transactional
  public void cancelar(UUID reservaId, String clienteId) {
    String dono =
        jdbc
            .query(
                "select cliente_id from reserva where id = ?",
                (rs, i) -> rs.getString(1),
                reservaId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new NaoEncontrado("reserva não encontrada"));
    if (!dono.equals(clienteId)) {
      throw new NaoEncontrado("reserva não encontrada");
    }
    if (!encerrar(
        reservaId, ReservaStatus.CANCELADA, "ReservaCancelada", "CANCELADO_PELO_CLIENTE")) {
      throw new Conflito("reserva não está ativa");
    }
  }

  @Transactional
  public void confirmarVenda(UUID reservaId) {
    if (!encerrar(reservaId, ReservaStatus.CONFIRMADA, null, null)) {
      // Pagamento tardio: a reserva já não está ativa (limitação 1.4: estorno simulado).
      log.warn("PedidoPago para reserva {} que já não está ativa", reservaId);
    }
  }

  /** ADR-008: enquanto o pagamento está em curso, a expiração espera até 30 s. */
  @Transactional
  public void suspender(UUID reservaId) {
    jdbc.update(
        "update reserva set suspensa_ate = now() + interval '30 seconds' where id = ? and status = 'ATIVA'",
        reservaId);
  }

  private boolean encerrar(UUID reservaId, ReservaStatus novo, String tipoEvento, String motivo) {
    ReservaStatus.ATIVA.para(novo);
    List<UUID> achada =
        jdbc.query(
            "select evento_id from reserva where id = ? and status = 'ATIVA' for update",
            (rs, i) -> rs.getObject(1, UUID.class),
            reservaId);
    if (achada.isEmpty()) {
      return false;
    }
    UUID eventoId = achada.get(0);
    jdbc.update("update reserva set status = ? where id = ?", novo.name(), reservaId);
    boolean venda = novo == ReservaStatus.CONFIRMADA;
    AssentoStatus destino = venda ? VENDIDO : LIVRE;
    var itens =
        jdbc.queryForList(
            "select assento_id, lote_id, quantidade from reserva_item where reserva_id = ?",
            reservaId);
    for (var item : itens) {
      if (item.get("assento_id") != null) {
        UUID assentoId = (UUID) item.get("assento_id");
        mover(assentoId, eventoId, RESERVADO, destino);
        outbox.add(
            Eventos.TOPICO_ASSENTO,
            eventoId.toString(),
            venda ? "AssentoVendido" : "AssentoLiberado",
            new AssentoMudou(eventoId, assentoId, destino.name()));
      } else {
        int unidades = (Integer) item.get("quantidade");
        jdbc.update(
            "update lote set qtd_reservada = qtd_reservada - ?, qtd_vendida = qtd_vendida + ? where id = ?",
            unidades,
            venda ? unidades : 0,
            item.get("lote_id"));
      }
    }
    if (!venda) {
      outbox.add(
          Eventos.TOPICO_RESERVA,
          reservaId.toString(),
          tipoEvento,
          new ReservaEncerrada(reservaId, motivo, Instant.now()));
    }
    return true;
  }

  private boolean mover(UUID assentoId, UUID eventoId, AssentoStatus de, AssentoStatus para) {
    de.para(para);
    return jdbc.update(
            """
            update assento set status = ?, versao = versao + 1
            where id = ? and status = ? and setor_id in (select id from setor where evento_id = ?)
            """,
            para.name(),
            assentoId,
            de.name(),
            eventoId)
        == 1;
  }
}
