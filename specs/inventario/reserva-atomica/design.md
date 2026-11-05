# Design

- `POST /reservas` (papel CLIENTE): `{eventoId, assentoIds[], itens[{loteId, quantidade}]}` -> 201 `{reservaId, expiraEm}` ou 409.
- Passo 1: filtro de contenção no Redis (`SET NX PX`) para cada assento, em ordem de UUID. Se o Redis falhar, segue sem ele (a fonte da verdade é o PostgreSQL).
- Passo 2 (uma transação): `UPDATE assento SET status='RESERVADO' WHERE id=? AND status='LIVRE'`; `UPDATE lote SET qtd_reservada = qtd_reservada + :n WHERE id=? AND qtd_reservada + qtd_vendida + :n <= quantidade`; 0 linhas afetadas = 409 e rollback. Depois cria `reserva`, `reserva_item` e grava `ReservaCriada` e `AssentoReservado` no Outbox.
- `ExpiracaoWorker` (a cada 2 s): `FOR UPDATE SKIP LOCKED`, respeita `suspensa_ate`.
- Consumidor de `checkout.pedido.v1`: `PedidoPago` confirma a venda; `PedidoAguardandoPagamento` suspende a expiração. Deduplicação por `eventId`.
- Eventos: `inventario.reserva.v1` (ReservaCriada, ReservaExpirada, ReservaCancelada) e `inventario.assento.v1` (AssentoReservado, AssentoLiberado, AssentoVendido).
- `ReservaCriada` leva `eventoNome` e, por item, `descricao` e `precoUnitario` (usados por Checkout e Emissão).
