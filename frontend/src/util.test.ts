import { describe, expect, it } from 'vitest';
import {
  agruparPorSetor, aplicarAssentoMudou, cnpjFormatado, esperaDeReconexao, gerarCodigos, mmss, paginaDaRota, papeis,
  parametrosBusca, rotuloAssento, rotuloMetrica, segundosRestantes, statusDoPedido, textoDaFila, totalSelecionado,
  urlDoCanal, type Assento,
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

describe('tempo real', () => {
  it('aplica a mudança de um assento sem alterar os outros nem a lista original', () => {
    const lista = [a('1', 'A1', 'S'), a('2', 'A2', 'S')];
    const nova = aplicarAssentoMudou(lista, { assentoId: '2', status: 'RESERVADO' });
    expect(nova.map((x) => x.status)).toEqual(['LIVRE', 'RESERVADO']);
    expect(lista[1].status).toBe('LIVRE');
  });

  it('ignora status desconhecido e assento que não está no mapa', () => {
    const lista = [a('1', 'A1', 'S')];
    expect(aplicarAssentoMudou(lista, { assentoId: '1', status: 'QUALQUER' })).toBe(lista);
    expect(aplicarAssentoMudou(lista, { assentoId: '9', status: 'VENDIDO' }).map((x) => x.status)).toEqual(['LIVRE']);
  });

  it('monta o endereço do WebSocket, com ws ou wss e o token codificado', () => {
    expect(urlDoCanal({ protocol: 'http:', host: 'localhost:3000' }, '/eventos/abc')).toBe('ws://localhost:3000/api/realtime/eventos/abc');
    expect(urlDoCanal({ protocol: 'https:', host: 'pulsepass.test' }, '/pedidos/p', 'a b+c')).toBe('wss://pulsepass.test/api/realtime/pedidos/p?token=a%20b%2Bc');
  });

  it('recua de forma exponencial até 30 s', () => {
    expect([0, 1, 2, 3, 4].map(esperaDeReconexao)).toEqual([1000, 2000, 4000, 8000, 16000]);
    expect(esperaDeReconexao(5)).toBe(30000);
    expect(esperaDeReconexao(50)).toBe(30000);
    expect(esperaDeReconexao(-3)).toBe(1000);
  });

  it('traduz o evento do pedido em texto, e ignora os que não mudam a tela', () => {
    expect(statusDoPedido('PedidoPago')).toContain('aprovado');
    expect(statusDoPedido('IngressoEmitido')).toContain('emitidos');
    expect(statusDoPedido('PagamentoRecusado')).toContain('recusado');
    expect(statusDoPedido('PedidoReservado')).toBeNull();
  });
});

describe('busca, fila e navegação', () => {
  it('monta a query da busca sem campos vazios', () => {
    expect(parametrosBusca({})).toBe('');
    expect(parametrosBusca({ q: '  rock ', local: '', de: '2027-03-10' })).toBe('?q=rock&de=2027-03-10');
    expect(parametrosBusca({ q: 'a&b' })).toBe('?q=a%26b');
  });

  it('descreve a situação na fila', () => {
    expect(textoDaFila({ status: 'AGUARDANDO', posicao: 12 })).toContain('posição 12');
    expect(textoDaFila({ status: 'ADMITIDO' })).toContain('Sua vez chegou');
    expect(textoDaFila({ status: 'FORA' })).toContain('não está na fila');
  });

  it('classifica a tela numa lista fechada, sem vazar identificadores', () => {
    expect(paginaDaRota('/')).toBe('catalogo');
    expect(paginaDaRota('/evento/9f1c-22')).toBe('evento');
    expect(paginaDaRota('/evento/9f1c-22/assentos')).toBe('assentos');
    expect(paginaDaRota('/checkout/9f1c-22')).toBe('checkout');
    expect(paginaDaRota('/ingressos')).toBe('ingressos');
    expect(paginaDaRota('/organizador')).toBe('organizador');
    expect(paginaDaRota('/portaria')).toBe('portaria');
    expect(paginaDaRota('/admin')).toBe('admin');
    expect(paginaDaRota('/qualquer/coisa')).toBe('outra');
  });

  it('formata o CNPJ numérico e o alfanumérico', () => {
    expect(cnpjFormatado('11222333000181')).toBe('11.222.333/0001-81');
    expect(cnpjFormatado('12abc34501de35')).toBe('12.ABC.345/01DE-35');
    expect(cnpjFormatado('curto')).toBe('curto');
  });

  it('dá nome legível às métricas', () => {
    expect(rotuloMetrica('pedidos_pagos')).toBe('Pedidos pagos');
    expect(rotuloMetrica('pagina.catalogo')).toBe('Tela: catalogo');
    expect(rotuloMetrica('desconhecida')).toBe('desconhecida');
  });
});
