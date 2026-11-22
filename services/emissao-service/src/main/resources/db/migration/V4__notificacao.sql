-- US25: e-mail com os ingressos. Uma notificação por pedido; o endereço só existe até o envio (RNF15).
create table notificacao (
  id uuid primary key,
  pedido_id uuid not null unique,
  destinatario text,
  status text not null default 'PENDENTE',
  tentativas int not null default 0,
  criada_em timestamptz not null default now(),
  proxima_tentativa timestamptz not null default now(),
  enviada_em timestamptz,
  erro text
);
create index notificacao_pendente on notificacao (proxima_tentativa) where status = 'PENDENTE';
