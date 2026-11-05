create table reserva_ref (
  reserva_id uuid primary key,
  cliente_id text not null,
  evento_id uuid not null,
  evento_nome text not null,
  itens jsonb not null,
  status text not null default 'ATIVA',
  expira_em timestamptz not null
);

create table pedido (
  id uuid primary key,
  cliente_id text not null,
  reserva_id uuid not null unique,
  status text not null,
  total numeric(12, 2) not null default 0,
  criado_em timestamptz not null default now(),
  expira_em timestamptz,
  motivo_expiracao text
);

create table pedido_item (
  id uuid primary key,
  pedido_id uuid not null references pedido (id),
  assento_id uuid,
  lote_id uuid not null,
  descricao text not null,
  quantidade int not null,
  preco_unitario numeric(12, 2) not null
);

create table chave_idempotencia (
  chave text primary key,
  hash_requisicao text not null,
  pronto boolean not null default false,
  resposta_status int,
  resposta_corpo text,
  criada_em timestamptz not null default now()
);

create table pagamento (
  id uuid primary key,
  pedido_id uuid not null references pedido (id),
  chave text not null unique,
  status text not null,
  valor numeric(12, 2) not null,
  gateway_ref text,
  motivo text,
  criado_em timestamptz not null default now()
);
create index pagamento_pedido on pagamento (pedido_id);
