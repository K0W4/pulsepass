package br.pucrs.pulsepass.inventario.service;

import br.pucrs.pulsepass.common.Invalido;
import br.pucrs.pulsepass.common.NaoEncontrado;
import br.pucrs.pulsepass.inventario.api.Dtos.EventoRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.LoteRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.SetorRequest;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Cadastro do organizador: eventos, lotes e setores com assentos (US07, US08, US09). */
@Service
public class EventoService {

  private final JdbcTemplate jdbc;
  private final CatalogoService catalogo;

  public EventoService(JdbcTemplate jdbc, CatalogoService catalogo) {
    this.jdbc = jdbc;
    this.catalogo = catalogo;
  }

  @Transactional
  public UUID criar(String organizadorId, EventoRequest r) {
    if (r.nome() == null || r.nome().isBlank() || r.local() == null || r.dataHora() == null) {
      throw new Invalido("nome, local e dataHora são obrigatórios");
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into evento(id, organizador_id, nome, local, data_hora, imagem_url) values (?, ?, ?, ?, ?, ?)",
        id,
        organizadorId,
        r.nome(),
        r.local(),
        Timestamp.from(r.dataHora()),
        r.imagemUrl());
    catalogo.invalidar();
    return id;
  }

  @Transactional
  public UUID criarLote(UUID eventoId, String organizadorId, LoteRequest r) {
    exigirDono(eventoId, organizadorId);
    if (r.nome() == null
        || r.preco() == null
        || r.preco().signum() < 0
        || r.quantidade() <= 0
        || r.inicioVenda() == null
        || r.fimVenda() == null) {
      throw new Invalido("lote exige nome, preço, quantidade positiva e período de venda");
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into lote(id, evento_id, nome, preco, quantidade, inicio_venda, fim_venda) values (?, ?, ?, ?, ?, ?, ?)",
        id,
        eventoId,
        r.nome(),
        r.preco(),
        r.quantidade(),
        Timestamp.from(r.inicioVenda()),
        Timestamp.from(r.fimVenda()));
    catalogo.invalidar();
    return id;
  }

  @Transactional
  public UUID criarSetor(UUID eventoId, String organizadorId, SetorRequest r) {
    exigirDono(eventoId, organizadorId);
    if (r.nome() == null || r.nome().isBlank()) {
      throw new Invalido("nome do setor é obrigatório");
    }
    if (r.marcado() && (r.loteId() == null || r.codigos() == null || r.codigos().isEmpty())) {
      throw new Invalido("setor marcado exige loteId e códigos de assento");
    }
    if (r.marcado()) {
      Integer lote =
          jdbc.queryForObject(
              "select count(*) from lote where id = ? and evento_id = ?",
              Integer.class,
              r.loteId(),
              eventoId);
      if (lote == null || lote == 0) {
        throw new NaoEncontrado("lote não pertence ao evento");
      }
    }
    UUID setorId = UUID.randomUUID();
    jdbc.update(
        "insert into setor(id, evento_id, nome, marcado) values (?, ?, ?, ?)",
        setorId,
        eventoId,
        r.nome(),
        r.marcado());
    if (r.marcado()) {
      for (String codigo : r.codigos()) {
        jdbc.update(
            "insert into assento(id, setor_id, lote_id, codigo) values (?, ?, ?, ?)",
            UUID.randomUUID(),
            setorId,
            r.loteId(),
            codigo);
      }
    }
    catalogo.invalidar();
    return setorId;
  }

  private void exigirDono(UUID eventoId, String organizadorId) {
    String dono =
        jdbc
            .query(
                "select organizador_id from evento where id = ?",
                (rs, i) -> rs.getString(1),
                eventoId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new NaoEncontrado("evento não encontrado"));
    if (!dono.equals(organizadorId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "evento de outro organizador");
    }
  }
}
