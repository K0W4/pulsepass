// Funções puras da interface (testadas em util.test.ts).

export interface Assento {
  id: string;
  codigo: string;
  setor: string;
  status: 'LIVRE' | 'RESERVADO' | 'VENDIDO';
  lote_id: string;
  preco: number;
}

const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

export function reais(valor: number | string): string {
  return brl.format(Number(valor));
}

export function dataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
}

export function segundosRestantes(expiraEm: string, agora: number = Date.now()): number {
  return Math.max(0, Math.floor((new Date(expiraEm).getTime() - agora) / 1000));
}

export function mmss(totalSegundos: number): string {
  const m = Math.floor(totalSegundos / 60);
  const s = totalSegundos % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/** Lê os papéis do realm de um token JWT já decodificado. */
export function papeis(token: { realm_access?: { roles?: string[] } } | undefined): string[] {
  return token?.realm_access?.roles ?? [];
}

export function agruparPorSetor(assentos: Assento[]): Map<string, Assento[]> {
  const grupos = new Map<string, Assento[]>();
  for (const a of assentos) {
    const lista = grupos.get(a.setor) ?? [];
    lista.push(a);
    grupos.set(a.setor, lista);
  }
  // ordem natural: A1, A2, ..., A10 (e não A1, A10, A11, A2)
  for (const lista of grupos.values()) {
    lista.sort((x, y) => x.codigo.localeCompare(y.codigo, 'pt-BR', { numeric: true }));
  }
  return grupos;
}

/** Gera códigos de assento "A1" a "A20" a partir de um prefixo e uma quantidade. */
export function gerarCodigos(prefixo: string, quantidade: number): string[] {
  return Array.from({ length: quantidade }, (_, i) => `${prefixo}${i + 1}`);
}

export function totalSelecionado(assentos: Assento[], selecionados: Set<string>): number {
  return assentos.filter((a) => selecionados.has(a.id)).reduce((soma, a) => soma + Number(a.preco), 0);
}

/** Aplica no mapa a mudança de um assento recebida em tempo real (status desconhecido é ignorado). */
export function aplicarAssentoMudou(assentos: Assento[], m: { assentoId?: unknown; status?: unknown }): Assento[] {
  if (m.status !== 'LIVRE' && m.status !== 'RESERVADO' && m.status !== 'VENDIDO') return assentos;
  const status = m.status;
  return assentos.map((a) => (a.id === m.assentoId ? { ...a, status } : a));
}

/** Endereço do WebSocket pelo mesmo domínio da SPA (o Nginx repassa /api/realtime ao gateway). */
export function urlDoCanal(local: { protocol: string; host: string }, caminho: string, token?: string): string {
  const esquema = local.protocol === 'https:' ? 'wss' : 'ws';
  const consulta = token ? `?token=${encodeURIComponent(token)}` : '';
  return `${esquema}://${local.host}/api/realtime${caminho}${consulta}`;
}

/** Recuo exponencial entre tentativas de reconexão: 1 s, 2 s, 4 s ... no máximo 30 s. */
export function esperaDeReconexao(tentativa: number): number {
  return Math.min(30_000, 1000 * 2 ** Math.max(0, tentativa));
}

/** Nome (de uma lista fechada) da tela, para a telemetria de navegação (sem dado pessoal). */
export function paginaDaRota(caminho: string): string {
  if (caminho === '/' || caminho === '') return 'catalogo';
  if (/^\/evento\/[\w-]+\/assentos$/.test(caminho)) return 'assentos';
  if (/^\/evento\/[\w-]+$/.test(caminho)) return 'evento';
  if (/^\/checkout\/[\w-]+$/.test(caminho)) return 'checkout';
  if (caminho === '/ingressos') return 'ingressos';
  if (caminho === '/organizador') return 'organizador';
  if (caminho === '/portaria') return 'portaria';
  if (caminho === '/admin') return 'admin';
  return 'outra';
}

/** Monta a query da busca no catálogo, sem campos vazios. */
export function parametrosBusca(f: { q?: string; local?: string; de?: string; ate?: string }): string {
  const p = new URLSearchParams();
  for (const [chave, valor] of Object.entries(f)) {
    if (valor && valor.trim()) p.set(chave, valor.trim());
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}

/** Mensagem para quem espera na fila virtual. */
export function textoDaFila(s: { status: string; posicao?: number }): string {
  if (s.status === 'ADMITIDO') return 'Sua vez chegou! Você tem 5 minutos para reservar.';
  if (s.status === 'AGUARDANDO') return `Você está na fila: posição ${s.posicao ?? '?'}. Mantenha esta página aberta.`;
  return 'Você não está na fila deste evento.';
}

/** Texto do status do pedido para cada evento recebido em tempo real (US27); null se não muda a tela. */
export function statusDoPedido(tipo: string): string | null {
  switch (tipo) {
    case 'PedidoAguardandoPagamento': return 'Aguardando a confirmação do pagamento...';
    case 'PedidoPago': return 'Pagamento aprovado. Emitindo seus ingressos...';
    case 'PagamentoRecusado': return 'Pagamento recusado.';
    case 'PedidoExpirado': return 'O prazo da reserva acabou.';
    case 'IngressoEmitido': return 'Ingressos emitidos!';
    default: return null;
  }
}

/** 11222333000181 vira 11.222.333/0001-81 (vale também para o CNPJ alfanumérico). */
export function cnpjFormatado(c: string): string {
  const s = c.replace(/[^0-9A-Za-z]/g, '').toUpperCase();
  if (s.length !== 14) return c;
  return `${s.slice(0, 2)}.${s.slice(2, 5)}.${s.slice(5, 8)}/${s.slice(8, 12)}-${s.slice(12)}`;
}

const ROTULOS: Record<string, string> = {
  reservas_criadas: 'Reservas criadas',
  reservas_expiradas: 'Reservas expiradas',
  reservas_canceladas: 'Reservas canceladas',
  pedidos_pagos: 'Pedidos pagos',
  pagamentos_recusados: 'Pagamentos recusados',
  pedidos_expirados: 'Pedidos expirados',
  ingressos_emitidos: 'Ingressos emitidos',
  ingressos_utilizados: 'Ingressos lidos na portaria',
  organizadores_aprovados: 'Organizadores aprovados',
  paginas_vistas: 'Páginas vistas',
};

/** Nome legível de uma métrica da telemetria (as de página viram "Tela: catalogo"). */
export function rotuloMetrica(nome: string): string {
  if (nome.startsWith('pagina.')) return `Tela: ${nome.slice('pagina.'.length)}`;
  return ROTULOS[nome] ?? nome;
}

/** Descrição falada pelo leitor de tela para cada assento. */
export function rotuloAssento(a: Assento): string {
  const estado = { LIVRE: 'livre', RESERVADO: 'reservado', VENDIDO: 'vendido' }[a.status];
  return `${a.setor}, assento ${a.codigo}, ${estado}, ${reais(a.preco)}`;
}
