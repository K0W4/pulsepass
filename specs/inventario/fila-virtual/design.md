# Fila virtual e rate limit: desenho

- Redis: `fila:{evento}` (ZSET, score = instante de chegada, `ZADD NX`), `fila:ok:{evento}:{cliente}` (chave com TTL de 5 min), `fila:tick` (`SET NX PX 1000`, para só uma réplica admitir por segundo). A verdade da reserva continua no PostgreSQL; o Redis só decide quem pode tentar (regra 4).
- `evento.fila_ativa boolean` (migração V4).
- `FilaService` (entrar, posição, admitir) e `FilaWorker` (@Scheduled). `ReservaController` consulta `FilaService.podeReservar`.
- Se o Redis estiver fora, `podeReservar` falha aberto (não bloqueia a compra) e registra aviso: a fila é proteção, não regra de negócio. [limite registrado]
- Gateway: `TokenBucket` (domínio, puro, com relógio injetável) e `RateLimitFilter` (GlobalFilter reativo) com mapa em memória por instância (limite: cada réplica conta separado; a evolução é um balde no Redis).
