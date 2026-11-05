create table evento (
  id uuid primary key,
  organizador_id text not null,
  nome text not null,
  local text not null,
  data_hora timestamptz not null,
  imagem_url text,
  status text not null default 'PUBLICADO'
);

create table lote (
  id uuid primary key,
  evento_id uuid not null references evento (id),
  nome text not null,
  preco numeric(12, 2) not null check (preco >= 0),
  quantidade int not null check (quantidade >= 0),
  qtd_reservada int not null default 0,
  qtd_vendida int not null default 0,
  inicio_venda timestamptz not null,
  fim_venda timestamptz not null,
  -- última barreira contra sobre-venda, mesmo se o código errar
  check (qtd_reservada >= 0 and qtd_vendida >= 0 and qtd_reservada + qtd_vendida <= quantidade)
);

create table setor (
  id uuid primary key,
  evento_id uuid not null references evento (id),
  nome text not null,
  marcado boolean not null default true
);

create table assento (
  id uuid primary key,
  setor_id uuid not null references setor (id),
  lote_id uuid not null references lote (id),
  codigo text not null,
  status text not null default 'LIVRE',
  versao int not null default 0,
  unique (setor_id, codigo)
);

create table reserva (
  id uuid primary key,
  cliente_id text not null,
  evento_id uuid not null references evento (id),
  status text not null,
  criada_em timestamptz not null default now(),
  expira_em timestamptz not null,
  suspensa_ate timestamptz
);
create index reserva_vencimento on reserva (expira_em) where status = 'ATIVA';

create table reserva_item (
  id uuid primary key,
  reserva_id uuid not null references reserva (id),
  assento_id uuid references assento (id),
  lote_id uuid not null references lote (id),
  quantidade int not null default 1
);
create index reserva_item_reserva on reserva_item (reserva_id);
