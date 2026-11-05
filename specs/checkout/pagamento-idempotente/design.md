# Design

- `POST /pedidos {reservaId}` (CLIENTE) -> CRIADO; vira RESERVADO assim que a projeção `reserva_ref` (alimentada por `ReservaCriada`) confirma reserva ATIVA, fixando total e `expira_em`.
- `POST /pagamentos {pedidoId}` com `Idempotency-Key` (CLIENTE). TX1: insere a chave (PK), pedido -> AGUARDANDO, pagamento PENDENTE, outbox `PedidoAguardandoPagamento`. Fora de transação: chamada ao gateway com timeout, retry com backoff e circuit breaker (Resilience4j). TX2: pagamento APROVADO/RECUSADO, pedido -> PAGO ou volta a RESERVADO, outbox `PedidoPago` ou `PagamentoRecusado`, resposta guardada na chave.
- Falha de infraestrutura no gateway: chave apagada (a mesma chave pode tentar de novo), pedido volta a RESERVADO, resposta 503.
- Chamada concorrente com a mesma chave enquanto a primeira está em curso recebe 409.
- A chave é escopada por cliente (`clienteId:chave`); o gateway recebe o id do pagamento como chave de idempotência própria.
- Eventos em `checkout.pedido.v1` (chave `pedidoId`): PedidoReservado, PedidoAguardandoPagamento, PedidoPago, PagamentoRecusado, PedidoExpirado. `PedidoPago` leva reservaId, clienteId, eventoId, eventoNome, itens e total.
