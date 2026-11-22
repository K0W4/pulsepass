package br.pucrs.pulsepass.inventario.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucrs.pulsepass.inventario.domain.FiltroCatalogo.Criterios;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FiltroCatalogoTest {

  private static Map<String, Object> ev(String nome, String local, Object dataHora) {
    var m = new HashMap<String, Object>();
    m.put("nome", nome);
    m.put("local", local);
    m.put("data_hora", dataHora);
    return m;
  }

  private static final Map<String, Object> ROCK =
      ev("Rock in Rio", "Rio de Janeiro", "2027-03-10T23:30:00.000+00:00"); // 20h30 em São Paulo
  private static final Map<String, Object> FORRO =
      ev("Festival de Forró", "São Paulo", Timestamp.from(Instant.parse("2027-03-12T12:00:00Z")));
  private static final List<Map<String, Object>> TODOS = List.of(ROCK, FORRO);

  private static List<String> nomes(Criterios c) {
    return FiltroCatalogo.aplicar(TODOS, c).stream().map(e -> (String) e.get("nome")).toList();
  }

  @Test
  void semCriteriosDevolveTudo() {
    assertThat(nomes(new Criterios(null, null, null, null))).hasSize(2);
    assertThat(nomes(new Criterios("  ", "", null, null))).hasSize(2);
  }

  @Test
  void buscaPorNomeIgnoraMaiusculasEAcentos() {
    assertThat(nomes(new Criterios("ROCK", null, null, null))).containsExactly("Rock in Rio");
    assertThat(nomes(new Criterios("forro", null, null, null)))
        .containsExactly("Festival de Forró");
    assertThat(nomes(new Criterios("  festival ", null, null, null)))
        .containsExactly("Festival de Forró");
    assertThat(nomes(new Criterios("inexistente", null, null, null))).isEmpty();
  }

  @Test
  void buscaPorLocalIgnoraAcentos() {
    assertThat(nomes(new Criterios(null, "sao paulo", null, null)))
        .containsExactly("Festival de Forró");
    assertThat(nomes(new Criterios(null, "RIO", null, null))).containsExactly("Rock in Rio");
  }

  @Test
  void criteriosSeCombinamComE() {
    assertThat(nomes(new Criterios("rock", "sao paulo", null, null))).isEmpty();
    assertThat(nomes(new Criterios("rock", "rio", null, null))).containsExactly("Rock in Rio");
  }

  @Test
  void campoAusenteNaoCasaComBusca() {
    var semLocal = ev("Sem local", null, "2027-03-10T12:00:00Z");
    assertThat(FiltroCatalogo.aplicar(List.of(semLocal), new Criterios(null, "x", null, null)))
        .isEmpty();
    assertThat(FiltroCatalogo.aplicar(List.of(semLocal), new Criterios("sem", null, null, null)))
        .hasSize(1);
  }

  @Test
  void janelaDeDatasEInclusivaNasDuasPontas() {
    var dia10 = LocalDate.of(2027, 3, 10);
    var dia12 = LocalDate.of(2027, 3, 12);
    assertThat(nomes(new Criterios(null, null, dia10, dia10))).containsExactly("Rock in Rio");
    assertThat(nomes(new Criterios(null, null, dia12, dia12))).containsExactly("Festival de Forró");
    assertThat(nomes(new Criterios(null, null, dia10, dia12))).hasSize(2);
    assertThat(nomes(new Criterios(null, null, LocalDate.of(2027, 3, 11), null)))
        .containsExactly("Festival de Forró");
    assertThat(nomes(new Criterios(null, null, null, LocalDate.of(2027, 3, 11))))
        .containsExactly("Rock in Rio");
    assertThat(nomes(new Criterios(null, null, LocalDate.of(2027, 3, 13), null))).isEmpty();
    assertThat(nomes(new Criterios(null, null, null, LocalDate.of(2027, 3, 9)))).isEmpty();
  }

  @Test
  void dataUsaOFusoDeSaoPauloEnaoUtc() {
    // 23h30 UTC do dia 10 = 20h30 em São Paulo (ainda dia 10); 02h UTC do dia 11 = 23h do dia 10
    var tarde = ev("Madrugada UTC", "x", "2027-03-11T02:00:00Z");
    assertThat(
            FiltroCatalogo.aplicar(
                List.of(tarde),
                new Criterios(null, null, LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 10))))
        .hasSize(1);
  }

  @Test
  void dataInvalidaOuAusenteFicaForaQuandoHaJanela() {
    var sem = ev("Sem data", "x", null);
    var estranha = ev("Tipo estranho", "x", 42);
    var dia = LocalDate.of(2027, 3, 10);
    assertThat(FiltroCatalogo.aplicar(List.of(sem, estranha), new Criterios(null, null, dia, null)))
        .isEmpty();
    assertThat(
            FiltroCatalogo.aplicar(List.of(sem, estranha), new Criterios(null, null, null, null)))
        .hasSize(2);
  }

  @Test
  void aceitaInstantTambem() {
    var e = ev("Instant", "x", Instant.parse("2027-03-10T15:00:00Z"));
    var dia = LocalDate.of(2027, 3, 10);
    assertThat(FiltroCatalogo.aplicar(List.of(e), new Criterios(null, null, dia, dia))).hasSize(1);
  }
}
