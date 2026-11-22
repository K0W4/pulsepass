import { chamar, mensagemDeErro } from './api';
import { aoSair, h } from './dom';
import { dataHora, rotuloMetrica } from './util';

interface Resumo {
  minutos: number;
  totais: Record<string, number>;
  serie: Array<{ minuto: string; valores: Record<string, number> }>;
}
interface Emissao {
  amostras: number;
  p50: number | null;
  p95: number | null;
  metaSegundos: number;
  atendeMeta: boolean;
}

const MINUTOS = 15;

/**
 * Painel analítico em tempo real (US32): contagens dos últimos 15 minutos, série por minuto e o p95
 * do tempo de emissão (RNF08). Atualiza a cada 3 s enquanto a tela estiver aberta.
 */
export function painelTelemetria(): HTMLElement {
  const totais = h('div', { 'aria-live': 'off' });
  const serie = h('div', {});
  const emissao = h('p', { role: 'status' });

  async function atualizar(): Promise<void> {
    const [r, e] = await Promise.all([
      chamar<Resumo>('GET', `/telemetria/resumo?minutos=${MINUTOS}`),
      chamar<Emissao>('GET', `/telemetria/emissao?minutos=${MINUTOS}`),
    ]);
    if (r.status !== 200) {
      totais.replaceChildren(h('p', {}, mensagemDeErro(r.status)));
      return;
    }
    const nomes = Object.keys(r.dados.totais).filter((n) => !n.startsWith('pagina.'));
    totais.replaceChildren(
      nomes.length === 0
        ? h('p', {}, 'Ainda não há eventos nos últimos 15 minutos.')
        : h('table', { class: 'tabela' },
            h('caption', {}, `Totais dos últimos ${MINUTOS} minutos`),
            h('thead', {}, h('tr', {}, ['Métrica', 'Quantidade'].map((t) => h('th', { scope: 'col' }, t)))),
            h('tbody', {}, nomes.map((n) => h('tr', {}, h('th', { scope: 'row' }, rotuloMetrica(n)), h('td', {}, String(r.dados.totais[n])))))),
    );
    const colunas = ['reservas_criadas', 'pedidos_pagos', 'ingressos_emitidos'];
    const recentes = r.dados.serie.slice(-8);
    serie.replaceChildren(
      recentes.length === 0
        ? h('p', {}, '')
        : h('table', { class: 'tabela' },
            h('caption', {}, 'Por minuto'),
            h('thead', {}, h('tr', {}, ['Minuto', ...colunas.map(rotuloMetrica)].map((t) => h('th', { scope: 'col' }, t)))),
            h('tbody', {}, recentes.map((s) => h('tr', {},
              h('th', { scope: 'row' }, dataHora(s.minuto)),
              colunas.map((c) => h('td', {}, String(s.valores[c] ?? 0))))))),
    );
    if (e.status === 200) {
      const d = e.dados;
      emissao.textContent = d.amostras === 0
        ? 'Tempo de emissão do ingresso: sem amostras ainda.'
        : `Tempo de emissão do ingresso (${d.amostras} pedidos): p50 ${d.p50?.toFixed(1)} s, p95 ${d.p95?.toFixed(1)} s. ` +
          `Meta de ${d.metaSegundos} s no p95: ${d.atendeMeta ? 'atendida' : 'NÃO atendida'}.`;
    }
  }

  void atualizar();
  const timer = window.setInterval(() => void atualizar(), 3000);
  aoSair(() => window.clearInterval(timer));
  return h('fieldset', { class: 'painel' }, h('legend', {}, 'Painel em tempo real da plataforma'), emissao, totais, serie);
}
