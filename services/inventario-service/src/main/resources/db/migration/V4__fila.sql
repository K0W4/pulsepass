-- Fila virtual (US18): o organizador liga a fila de um evento de alta demanda.
alter table evento add column fila_ativa boolean not null default false;
