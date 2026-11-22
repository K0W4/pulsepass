# Telemetria: desenho

- Banco `db_telemetria`: `metrica_minuto(bucket, nome, qtd)` com upsert `qtd = qtd + 1`; `emissao_tempo(pedido_id, pago_em, emitido_em)` preenchida pelos dois lados.
- Domínio puro (`Agregador`, `Percentil`): classifica um envelope em nome de métrica e calcula percentis; cobertura >= 70% e mutação >= 60%.
- Consumidor Kafka transacional: `ProcessedEvents.primeiraVez` + upsert na mesma transação (RNF05).
- `p95` calculado no banco com `percentile_cont`, sobre a janela pedida.
- Prometheus consultado por `RestClient` (`PROMETHEUS_URL`), com timeout curto.
- Gateway: rota `/api/telemetria/**`; `POST /navegacao` é público (limitado pelo rate limit), o resto exige papel.
