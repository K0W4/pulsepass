create table outbox_event (
  id uuid primary key,
  topic text not null,
  msg_key text not null,
  body text not null,
  created_at timestamptz not null default now(),
  published_at timestamptz
);
create index outbox_pendentes on outbox_event (created_at) where published_at is null;

create table evento_processado (
  event_id uuid primary key,
  processado_em timestamptz not null default now()
);
