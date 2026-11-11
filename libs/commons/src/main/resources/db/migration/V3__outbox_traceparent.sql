-- contexto de trace (W3C traceparent) do pedido que gerou o evento, para o trace atravessar o Outbox
alter table outbox_event add column traceparent text;
