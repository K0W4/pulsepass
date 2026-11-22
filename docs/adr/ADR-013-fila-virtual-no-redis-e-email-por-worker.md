# ADR-013: fila virtual no Redis e e-mail por worker

**Estado:** aceita (Pacote 4).

## Fila virtual (US18, RNF23)

O Redis só decide **quem pode tentar** reservar; a verdade da reserva continua no PostgreSQL (regra 4 do projeto). A fila é um ZSET por evento (`ZADD NX` com um contador `INCR` como pontuação, então a ordem é a de chegada e entrar de novo não muda a posição). Um worker admite os primeiros a uma taxa configurável (`ZPOPMIN`, atômico); a admissão é uma chave com TTL de 5 minutos e vale **uma** reserva. Uma chave de compasso (`SET NX PX 900`) faz só uma réplica admitir por segundo, então a taxa total não passa do configurado.

**Falha aberta.** Se o Redis cair, a fila é ignorada e a compra segue: a fila é proteção contra pico, não regra de negócio, e o limite de sobre-venda é do banco.

## E-mail do ingresso (US25)

A Emissão grava a notificação na **mesma transação** que emite os ingressos (sem escrita dupla); um worker à parte envia por SMTP, com `SKIP LOCKED`, recuo crescente e até 5 tentativas. SMTP fora do ar atrasa o e-mail e nunca impede o ingresso (RNF11). A unicidade por pedido impede e-mail duplicado quando o evento chega de novo.

**LGPD (RNF15).** O e-mail vem do token no pagamento e só viaja no evento `PedidoPago`; o Checkout não o grava em tabela. Na Emissão o endereço é apagado da linha assim que o e-mail sai (ou na falha definitiva). Limite registrado: o endereço permanece no corpo do evento (retenção de 7 dias no Kafka e linha do Outbox).
