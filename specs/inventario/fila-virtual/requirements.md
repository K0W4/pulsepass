# Fila virtual e rate limit

Histórias: US18, RNF23 (fila por ordem de chegada). Pacote 2: FilaController no Inventário e rate limit no API Gateway.

Fila virtual
1. O organizador liga e desliga a fila de um evento (`PUT /eventos/{id}/fila-ativa`, só o dono).
2. Com a fila ligada, o cliente entra (`POST /eventos/{id}/fila`) e consulta a posição (`GET /eventos/{id}/fila`). A ordem é a de chegada; entrar de novo mantém a posição original.
3. Um worker admite os primeiros da fila a uma taxa configurável (`pulsepass.fila.admissoes-por-segundo`, padrão 20). A admissão vale 5 min.
4. Com a fila ligada, `POST /reservas` sem admissão devolve 403 com `{"motivo":"FILA_VIRTUAL"}`; com admissão, reserva normalmente e a admissão é consumida. Com a fila desligada nada muda.
5. Com mais de uma réplica, ninguém é admitido duas vezes e a taxa total não passa do configurado.

Rate limit (gateway)
6. O gateway limita cada cliente (`sub` do JWT, ou IP se anônimo) por balde de fichas: `pulsepass.ratelimit.rps` (padrão 100/s) e `burst` (padrão 200). Acima disso responde 429 com `Retry-After`. Não vale para `/actuator` nem `/api/realtime`.
