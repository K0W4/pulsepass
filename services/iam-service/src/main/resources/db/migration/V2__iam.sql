-- US05: pedido de cadastro de organizador, decidido por um admin
create table solicitacao_organizador (
  id uuid primary key,
  usuario_id text not null,
  razao_social text not null,
  cnpj text not null,
  contato text not null,
  status text not null,
  criada_em timestamptz not null default now(),
  decidida_em timestamptz,
  decidida_por text,
  motivo text
);
-- uma solicitação pendente por usuário; depois de decidida, pode haver outra
create unique index solicitacao_pendente_por_usuario
  on solicitacao_organizador (usuario_id) where status = 'PENDENTE';

-- US06: histórico de acessos (Keycloak) e decisões do próprio serviço. Guarda só o id do usuário
-- (o sub do token) e o IP, que é o dado que a auditoria de acessos suspeitos precisa.
create table auditoria (
  id uuid primary key,
  origem text not null,
  tipo text not null,
  usuario_id text,
  ip text,
  detalhes text,
  ocorrido_em timestamptz not null,
  chave text not null unique
);
create index auditoria_ocorrido on auditoria (ocorrido_em desc);
