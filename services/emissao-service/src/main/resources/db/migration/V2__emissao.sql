create table ingresso (
  id uuid primary key,
  pedido_id uuid not null,
  item_ordem int not null,
  unidade int not null,
  cliente_id text not null,
  evento_id uuid not null,
  evento_nome text not null,
  assento_id uuid,
  lote_id uuid,
  descricao text not null,
  status text not null default 'EMITIDO',
  emitido_em timestamptz not null default now(),
  utilizado_em timestamptz,
  utilizado_por text,
  unique (pedido_id, item_ordem, unidade)
);
create index ingresso_cliente on ingresso (cliente_id);

create table validacao_acesso (
  id uuid primary key,
  ingresso_id uuid,
  operador_id text not null,
  resultado text not null,
  lido_em timestamptz not null default now()
);
