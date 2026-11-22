package br.pucrs.pulsepass.inventario.domain;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Busca no catálogo (US13): nome, local e janela de datas, ignorando maiúsculas e acentos. Atua
 * sobre a lista que já está em cache, então não custa uma consulta ao banco (RNF06).
 */
public final class FiltroCatalogo {

  private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

  private FiltroCatalogo() {}

  /** Todos os campos são opcionais; {@code de} e {@code ate} são inclusivos. */
  public record Criterios(String q, String local, LocalDate de, LocalDate ate) {}

  public static List<Map<String, Object>> aplicar(
      List<Map<String, Object>> eventos, Criterios criterios) {
    return eventos.stream()
        .filter(
            e ->
                contem(e.get("nome"), criterios.q())
                    && contem(e.get("local"), criterios.local())
                    && naJanela(e.get("data_hora"), criterios.de(), criterios.ate()))
        .toList();
  }

  static boolean contem(Object campo, String busca) {
    if (busca == null || busca.isBlank()) {
      return true;
    }
    return campo != null && normalizar(campo.toString()).contains(normalizar(busca));
  }

  static String normalizar(String texto) {
    return Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
  }

  static boolean naJanela(Object dataHora, LocalDate de, LocalDate ate) {
    if (de == null && ate == null) {
      return true;
    }
    LocalDate dia = dia(dataHora);
    if (dia == null) {
      return false;
    }
    return (de == null || !dia.isBefore(de)) && (ate == null || !dia.isAfter(ate));
  }

  /** O banco devolve Timestamp; o que vem do cache JSON chega como texto ISO-8601. */
  static LocalDate dia(Object valor) {
    Instant instante =
        switch (valor) {
          case Timestamp t -> t.toInstant();
          case Instant i -> i;
          case String s -> OffsetDateTime.parse(s).toInstant();
          case null, default -> null;
        };
    return instante == null ? null : instante.atZone(FUSO).toLocalDate();
  }
}
