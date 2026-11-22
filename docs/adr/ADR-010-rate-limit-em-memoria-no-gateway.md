# ADR-010: rate limit em memória no API Gateway

**Estado:** aceita (Pacote 4). **Contexto:** o Pacote 2 prevê rate limit no gateway para que um cliente não esgote os serviços.

**Decisão.** Balde de fichas por cliente (`sub` do JWT; IP se anônimo), em memória, dentro de um `GlobalFilter` do Spring Cloud Gateway: 100 req/s e rajada de 200 (configuráveis por `RATELIMIT_RPS` e `RATELIMIT_BURST`). Acima disso, 429 com `Retry-After`. Ficam de fora `/actuator` e o WebSocket (`/api/realtime`).

**Por quê.** Não exige Redis no caminho de toda requisição e não adiciona latência de rede. O estado é descartável: baldes ociosos há mais de um minuto saem do mapa.

**Limites.** Com várias réplicas do gateway cada uma conta separado, então o limite efetivo é `réplicas × limite`. Para um limite global, o balde passa para o Redis (script Lua atômico). O IP vem de `X-Forwarded-For`, que o Nginx da SPA preenche; atrás de outro proxy é preciso confiar só no proxy conhecido. O teste de carga (k6) sai de um cliente só, então o `carga.sh` eleva o limite durante a medição e o restaura no fim.
