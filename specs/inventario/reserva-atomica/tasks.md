# Tasks
1. [x] Migração V2 (evento, lote, setor, assento, reserva, reserva_item).
2. [x] Domínio: ReservaStatus, AssentoStatus, Lote + testes unitários.
3. [x] ReservaService (reservar, cancelar, expirar, confirmar, suspender).
4. [x] SeatLock (Redis) com degradação.
5. [x] ExpiracaoWorker e PedidoEventsConsumer.
6. [x] EventoController e ReservaController + SecurityConfig (RBAC).
7. [x] Teste de integração: 500 reservas concorrentes -> 1 sucesso; lote; expiração; Outbox com Kafka pausado.
