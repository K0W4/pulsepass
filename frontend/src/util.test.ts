import { describe, expect, it } from 'vitest';
import {
  agruparPorSetor, gerarCodigos, mmss, papeis, rotuloAssento, segundosRestantes, totalSelecionado,
  type Assento,
} from './util';

const a = (id: string, codigo: string, setor: string, status: Assento['status'] = 'LIVRE', preco = 100): Assento =>
  ({ id, codigo, setor, status, lote_id: 'l', preco });

describe('util', () => {
  it('formata o cronômetro em mm:ss', () => {
    expect(mmss(0)).toBe('00:00');
    expect(mmss(65)).toBe('01:05');
    expect(mmss(600)).toBe('10:00');
  });

  it('calcula os segundos restantes sem passar de zero', () => {
    const agora = Date.parse('2026-11-05T12:00:00Z');
    expect(segundosRestantes('2026-11-05T12:10:00Z', agora)).toBe(600);
    expect(segundosRestantes('2026-11-05T11:59:00Z', agora)).toBe(0);
  });

  it('lê os papéis do token e tolera token ausente', () => {
    expect(papeis({ realm_access: { roles: ['cliente', 'operador'] } })).toEqual(['cliente', 'operador']);
    expect(papeis({})).toEqual([]);
    expect(papeis(undefined)).toEqual([]);
  });

  it('agrupa assentos por setor', () => {
    const g = agruparPorSetor([a('1', 'A1', 'Setor A'), a('2', 'B1', 'Setor B'), a('3', 'A2', 'Setor A')]);
    expect([...g.keys()]).toEqual(['Setor A', 'Setor B']);
    expect(g.get('Setor A')?.map((x) => x.codigo)).toEqual(['A1', 'A2']);
  });

  it('ordena os assentos de forma natural dentro do setor', () => {
    const g = agruparPorSetor([a('1', 'A10', 'S'), a('2', 'A2', 'S'), a('3', 'A1', 'S'), a('4', 'A11', 'S')]);
    expect(g.get('S')?.map((x) => x.codigo)).toEqual(['A1', 'A2', 'A10', 'A11']);
  });

  it('gera códigos de assento', () => {
    expect(gerarCodigos('A', 3)).toEqual(['A1', 'A2', 'A3']);
    expect(gerarCodigos('A', 0)).toEqual([]);
  });

  it('soma só os assentos selecionados', () => {
    const lista = [a('1', 'A1', 'S', 'LIVRE', 250), a('2', 'A2', 'S', 'LIVRE', 100.5), a('3', 'A3', 'S', 'LIVRE', 40)];
    expect(totalSelecionado(lista, new Set(['1', '2']))).toBeCloseTo(350.5);
    expect(totalSelecionado(lista, new Set())).toBe(0);
  });

  it('descreve o assento para leitores de tela', () => {
    expect(rotuloAssento(a('1', 'A10', 'Setor A', 'VENDIDO', 250))).toContain('Setor A, assento A10, vendido');
  });
});
