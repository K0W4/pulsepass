# ADR-012: telemetria agregada por minuto no PostgreSQL

**Estado:** aceita (Pacote 4). **Contexto:** ingestão contínua de eventos de navegação e compra, painel em tempo real e medição do p95 de emissão (RNF08).

**Decisão.** O `telemetria-service` consome os tópicos de domínio e `telemetria.navegacao.v1`, deduplica por `eventId` na mesma transação (`evento_processado`) e agrega em `metrica_minuto(bucket, nome, qtd)` com `INSERT ... ON CONFLICT DO UPDATE`. O tempo de emissão vem de `emissao_tempo`, onde `PedidoPago` e `IngressoEmitido` gravam cada um o seu instante, em qualquer ordem de chegada; o p95 é calculado por interpolação linear (mesmo critério do `percentile_cont`).

**Por quê.** O volume por minuto é pequeno e consultável por SQL, sem outro armazenamento; a idempotência vem de graça da mesma técnica dos demais consumidores. O Prometheus continua dono das métricas técnicas; a tela de administração (US33) as lê dele pelo próprio `telemetria-service`.

**Navegação.** A SPA envia só o nome da tela, de uma lista fechada (qualquer outro valor vira `outra`), sem identificar a pessoa; a ingestão passa pelo Outbox e pelo Kafka como as demais.

**Limites.** O bucket usa a hora do evento, não a do processamento. Um pedido com vários ingressos vale pelo último emitido. Sem retenção: a tabela cresce 1 linha por métrica por minuto (a limpeza fica como evolução).
