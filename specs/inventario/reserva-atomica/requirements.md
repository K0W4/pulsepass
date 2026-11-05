# Reserva atômica de assentos e lotes (US15, US16, RNF01, RNF02)

## Critérios de aceite (Gherkin)
- Dado o assento A10 LIVRE, quando 500 clientes tentam reservá-lo ao mesmo tempo, então exatamente 1 recebe 201 (reserva ATIVA, expira em 10 min) e os outros 499 recebem 409 "assento indisponível".
- Dado um lote com quantidade N, quando as reservas concorrentes somam mais que N, então a soma de `qtd_reservada + qtd_vendida` nunca ultrapassa N e o excedente recebe 409.
- Dado uma reserva ATIVA com `expira_em` vencido, quando o `ExpiracaoWorker` roda, então a reserva vira EXPIRADA, assentos voltam a LIVRE, unidades de lote são devolvidas e `ReservaExpirada` é publicado.
- Dado um pedido AGUARDANDO pagamento, a expiração da reserva é suspensa por até 30 s (ADR-008).
- Dado `PedidoPago`, a reserva vira CONFIRMADA, assentos VENDIDO e as unidades de lote passam de reservadas a vendidas.

## Fora do escopo desta spec
Fila virtual (US18) e mapa por WebSocket (R2).
