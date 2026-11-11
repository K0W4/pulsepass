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

/** Descrição falada pelo leitor de tela para cada assento. */
export function rotuloAssento(a: Assento): string {
  const estado = { LIVRE: 'livre', RESERVADO: 'reservado', VENDIDO: 'vendido' }[a.status];
  return `${a.setor}, assento ${a.codigo}, ${estado}, ${reais(a.preco)}`;
}
