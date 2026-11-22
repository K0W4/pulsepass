-- Contagem por minuto e por nome de métrica (reservas_criadas, pedidos_pagos, paginas_vistas, ...)
create table metrica_minuto (
  bucket timestamptz not null,
  nome text not null,
  qtd bigint not null default 0,
  primary key (bucket, nome)
);

-- RNF08: tempo entre PedidoPago e IngressoEmitido; cada lado grava o seu instante, em qualquer ordem
create table emissao_tempo (
  pedido_id uuid primary key,
  pago_em timestamptz,
  emitido_em timestamptz
);
create index emissao_tempo_emitido on emissao_tempo (emitido_em);
