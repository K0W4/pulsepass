package br.pucrs.pulsepass.inventario.integration;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucrs.pulsepass.common.Conflito;
import br.pucrs.pulsepass.inventario.api.Dtos.ItemLote;
import br.pucrs.pulsepass.inventario.service.EventoService;
import br.pucrs.pulsepass.inventario.service.ReservaService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** RNF01: zero sobre-venda sob concorrência, com PostgreSQL e Redis reais. */
@SpringBootTest
class ReservaConcorrenteIT extends ContainersBase {

  @Autowired ReservaService reservas;
  @Autowired EventoService eventos;
  @Autowired JdbcTemplate jdbc;

  @Test
  void quinhentasReservasNoMesmoAssentoDaoExatamenteUmSucesso() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "A10");

    int[] resultado =
        disparar(
            500,
            i -> reservas.reservar("cliente-" + i, f.eventoId(), List.of(f.assentoId()), null));

    assertThat(resultado[0]).as("sucessos").isEqualTo(1);
    assertThat(resultado[1]).as("conflitos 409").isEqualTo(499);
    assertThat(
            jdbc.queryForObject(
                "select status from assento where id = ?", String.class, f.assentoId()))
        .isEqualTo("RESERVADO");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reserva where evento_id = ? and status = 'ATIVA'",
                Integer.class,
                f.eventoId()))
        .isEqualTo(1);
  }

  @Test
  void loteNuncaUltrapassaACapacidade() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "B1");
    UUID pista = Fixtures.lotePista(eventos, f.eventoId(), 50);

    int[] resultado =
        disparar(
            200,
            i ->
                reservas.reservar(
                    "cliente-" + i, f.eventoId(), null, List.of(new ItemLote(pista, 1))));

    assertThat(resultado[0]).as("sucessos").isEqualTo(50);
    assertThat(resultado[1]).as("conflitos 409").isEqualTo(150);
    assertThat(
            jdbc.queryForObject(
                "select qtd_reservada from lote where id = ?", Integer.class, pista))
        .isEqualTo(50);
  }

  @Test
  void falhaNoSegundoAssentoDesfazOPrimeiro() {
    var f = Fixtures.criar(eventos, jdbc, "C1");
    UUID inexistente = UUID.randomUUID();

    try {
      reservas.reservar("cliente-x", f.eventoId(), List.of(f.assentoId(), inexistente), null);
    } catch (Conflito esperado) {
      // o segundo assento não existe: a transação inteira deve ser desfeita
    }

    assertThat(
            jdbc.queryForObject(
                "select status from assento where id = ?", String.class, f.assentoId()))
        .isEqualTo("LIVRE");
  }

  /** Dispara n chamadas ao mesmo tempo; devolve {sucessos, conflitos}. */
  private int[] disparar(int n, java.util.function.IntFunction<Object> chamada) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(50);
    CountDownLatch largada = new CountDownLatch(1);
    AtomicInteger sucessos = new AtomicInteger();
    AtomicInteger conflitos = new AtomicInteger();
    List<Future<?>> futuros = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      int indice = i;
      Callable<Void> tarefa =
          () -> {
            largada.await();
            try {
              chamada.apply(indice);
              sucessos.incrementAndGet();
            } catch (Conflito e) {
              conflitos.incrementAndGet();
            }
            return null;
          };
      futuros.add(pool.submit(tarefa));
    }
    largada.countDown();
    for (Future<?> futuro : futuros) {
      futuro.get();
    }
    pool.shutdown();
    return new int[] {sucessos.get(), conflitos.get()};
  }
}
