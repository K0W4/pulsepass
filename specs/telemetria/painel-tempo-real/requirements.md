# Telemetria e painel analítico: requisitos

Histórias: US32 (painel em tempo real), US33 (métricas operacionais), RNF08 (ingresso em até 30 s, p95).

1. A SPA envia eventos de navegação (`POST /api/telemetria/navegacao`, sem identificar a pessoa); o serviço os publica em `telemetria.navegacao.v1` pelo Outbox.
2. O serviço consome os tópicos de domínio (reserva, pedido, ingresso, IAM) e a navegação, deduplica por `eventId` e agrega por minuto: reservas criadas, expiradas e canceladas, pedidos pagos, pagamentos recusados, ingressos emitidos e lidos, páginas vistas.
3. `GET /telemetria/resumo?minutos=N` devolve totais e série por minuto (papéis organizador e admin).
4. `GET /telemetria/emissao` devolve quantidade, p50 e p95 do tempo entre `PedidoPago` e `IngressoEmitido` e se a meta de 30 s foi atendida (RNF08). Funciona qualquer que seja a ordem de chegada dos dois eventos.
5. `GET /telemetria/operacional` (admin) devolve latência p95, taxa de erros 5xx e lag do consumidor Kafka, lidos do Prometheus; se o Prometheus não responder, devolve os campos nulos e `disponivel=false`.
6. As contagens e o tempo de emissão também saem em `/actuator/prometheus` para o Grafana.
7. Nenhum dado pessoal entra nas métricas (RNF15).
