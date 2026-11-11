import { chamar, mensagemDeErro } from './api';
import { aoSair, aviso, h } from './dom';
import { agruparPorSetor, dataHora, reais, rotuloAssento, totalSelecionado, type Assento } from './util';

interface Evento { id: string; nome: string; local: string; data_hora: string }
interface Lote { id: string; nome: string; preco: number; inicio_venda: string; fim_venda: string; disponivel: number }

export async function catalogo(): Promise<HTMLElement> {
  const r = await chamar<Evento[]>('GET', '/eventos');
  const raiz = h('section', {}, h('h1', { tabindex: -1 }, 'Eventos'));
  if (r.status !== 200) {
    aviso(mensagemDeErro(r.status), 'erro');
    return raiz;
  }
  if (r.dados.length === 0) {
    raiz.append(h('p', {}, 'Nenhum evento publicado ainda.'));
    return raiz;
  }
  raiz.append(
    h('ul', { class: 'lista-cartoes' },
      r.dados.map((e) =>
        h('li', { class: 'cartao' },
          h('h2', {}, h('a', { href: `#/evento/${e.id}` }, e.nome)),
          h('p', {}, `${e.local} · ${dataHora(e.data_hora)}`)))),
  );
  return raiz;
}

export async function detalhe(id: string): Promise<HTMLElement> {
  const r = await chamar<{ evento: Evento; lotes: Lote[] }>('GET', `/eventos/${id}`);
  const raiz = h('section', {});
  if (r.status !== 200) {
    aviso(mensagemDeErro(r.status), 'erro');
    raiz.append(h('h1', { tabindex: -1 }, 'Evento não encontrado'), h('a', { href: '#/' }, 'Voltar ao catálogo'));
    return raiz;
  }
  const { evento, lotes } = r.dados;
  raiz.append(
    h('h1', { tabindex: -1 }, evento.nome),
    h('p', {}, `${evento.local} · ${dataHora(evento.data_hora)}`),
    h('h2', {}, 'Lotes e disponibilidade'),
    h('table', { class: 'tabela' },
      h('caption', { class: 'so-leitor' }, 'Lotes do evento'),
      h('thead', {}, h('tr', {}, ['Lote', 'Preço', 'Vendas até', 'Disponíveis'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, lotes.map((l) =>
        h('tr', {},
          h('th', { scope: 'row' }, l.nome),
          h('td', {}, reais(l.preco)),
          h('td', {}, dataHora(l.fim_venda)),
          h('td', {}, String(l.disponivel)))))),
    h('p', {}, h('a', { class: 'botao', href: `#/evento/${id}/assentos` }, 'Escolher ingressos')),
  );
  return raiz;
}

export async function mapaDeAssentos(id: string): Promise<HTMLElement> {
  const [det, ass] = await Promise.all([
    chamar<{ evento: Evento; lotes: Lote[] }>('GET', `/eventos/${id}`),
    chamar<Assento[]>('GET', `/eventos/${id}/assentos`),
  ]);
  if (det.status !== 200 || ass.status !== 200) {
    aviso(mensagemDeErro(det.status || ass.status), 'erro');
    return h('section', {}, h('h1', { tabindex: -1 }, 'Evento indisponível'));
  }
  let assentos = ass.dados;
  const lotes = det.dados.lotes;
  const lotesComAssento = new Set(assentos.map((a) => a.lote_id));
  const lotesDePista = lotes.filter((l) => !lotesComAssento.has(l.id));
  const selecionados = new Set<string>();
  const quantidades = new Map<string, number>();
  let modo: 'mapa' | 'lista' = 'mapa';

  const resumo = h('p', { class: 'resumo', 'aria-live': 'polite' });
  const area = h('div', {});
  const botaoReservar = h('button', { class: 'botao', type: 'button', onclick: () => void reservar() }, 'Reservar');

  function atualizarResumo(): void {
    const pista = lotesDePista.reduce((s, l) => s + (quantidades.get(l.id) ?? 0) * Number(l.preco), 0);
    const itens = selecionados.size + [...quantidades.values()].reduce((s, q) => s + q, 0);
    resumo.textContent = `${itens} ingresso(s) selecionado(s), total ${reais(totalSelecionado(assentos, selecionados) + pista)}`;
    botaoReservar.disabled = itens === 0;
  }

  function alternar(a: Assento): void {
    if (selecionados.has(a.id)) selecionados.delete(a.id);
    else selecionados.add(a.id);
    desenhar();
  }

  function desenhar(): void {
    area.replaceChildren();
    const grupos = agruparPorSetor(assentos);
    for (const [setor, lista] of grupos) {
      area.append(h('h3', {}, setor));
      if (modo === 'mapa') {
        area.append(
          h('div', { class: 'grade-assentos', role: 'group', 'aria-label': `Mapa do ${setor}` },
            lista.map((a) =>
              h('button', {
                type: 'button',
                class: `assento ${a.status.toLowerCase()}${selecionados.has(a.id) ? ' selecionado' : ''}`,
                disabled: a.status !== 'LIVRE',
                'aria-pressed': selecionados.has(a.id) ? 'true' : 'false',
                'aria-label': rotuloAssento(a),
                onclick: () => alternar(a),
              }, a.codigo))));
      } else {
        area.append(
          h('table', { class: 'tabela' },
            h('caption', { class: 'so-leitor' }, `Assentos do ${setor}`),
            h('thead', {}, h('tr', {}, ['Escolher', 'Assento', 'Situação', 'Preço'].map((t) => h('th', { scope: 'col' }, t)))),
            h('tbody', {}, lista.map((a) =>
              h('tr', {},
                h('td', {}, h('input', {
                  type: 'checkbox', checked: selecionados.has(a.id), disabled: a.status !== 'LIVRE',
                  'aria-label': rotuloAssento(a), onchange: () => alternar(a),
                })),
                h('th', { scope: 'row' }, a.codigo),
                h('td', {}, { LIVRE: 'Livre', RESERVADO: 'Reservado', VENDIDO: 'Vendido' }[a.status]),
                h('td', {}, reais(a.preco)))))));
      }
    }
    if (lotesDePista.length) {
      area.append(h('h3', {}, 'Ingressos sem assento marcado'));
      for (const l of lotesDePista) {
        const campo = h('input', {
          id: `qtd-${l.id}`, type: 'number', min: 0, max: Math.max(0, Math.min(10, l.disponivel)), value: quantidades.get(l.id) ?? 0,
          onchange: (e: Event) => {
            quantidades.set(l.id, Number((e.target as HTMLInputElement).value) || 0);
            atualizarResumo();
          },
        });
        area.append(h('p', {}, h('label', { htmlFor: `qtd-${l.id}` }, `${l.nome}, ${reais(l.preco)} (${l.disponivel} disponíveis): `), campo));
      }
    }
    atualizarResumo();
  }

  async function recarregar(): Promise<void> {
    const r = await chamar<Assento[]>('GET', `/eventos/${id}/assentos`);
    if (r.status !== 200) return;
    assentos = r.dados;
    const livres = new Set(assentos.filter((a) => a.status === 'LIVRE').map((a) => a.id));
    for (const s of [...selecionados]) if (!livres.has(s)) selecionados.delete(s);
    desenhar();
  }

  async function reservar(): Promise<void> {
    botaoReservar.disabled = true;
    const itens = lotesDePista.filter((l) => (quantidades.get(l.id) ?? 0) > 0).map((l) => ({ loteId: l.id, quantidade: quantidades.get(l.id) ?? 0 }));
    const r = await chamar<{ reservaId: string }>('POST', '/reservas', {
      corpo: { eventoId: id, assentoIds: [...selecionados], itens },
    });
    if (r.status === 201) {
      aviso('', 'info');
      location.hash = `#/checkout/${r.dados.reservaId}`;
      return;
    }
    aviso(r.status === 409 ? 'Um dos ingressos acabou de ser reservado. Escolha outro.' : mensagemDeErro(r.status), 'erro');
    selecionados.clear();
    await recarregar();
  }

  const tempo = window.setInterval(() => void recarregar(), 5000);
  aoSair(() => window.clearInterval(tempo));

  desenhar();
  return h('section', {},
    h('h1', { tabindex: -1 }, `Escolha seus ingressos: ${det.dados.evento.nome}`),
    h('p', { class: 'legenda' },
      h('span', { class: 'amostra livre' }, 'Livre'), ' ',
      h('span', { class: 'amostra reservado' }, 'Reservado'), ' ',
      h('span', { class: 'amostra vendido' }, 'Vendido'), ' ',
      h('span', { class: 'amostra selecionado' }, 'Selecionado')),
    h('div', { class: 'barra' },
      h('button', {
        class: 'botao-sec', type: 'button',
        onclick: (e: Event) => {
          modo = modo === 'mapa' ? 'lista' : 'mapa';
          (e.target as HTMLButtonElement).textContent = modo === 'mapa' ? 'Ver como lista' : 'Ver como mapa';
          desenhar();
        },
      }, 'Ver como lista'),
      h('button', { class: 'botao-sec', type: 'button', onclick: () => void recarregar() }, 'Atualizar disponibilidade')),
    area, resumo, botaoReservar,
    h('p', { class: 'nota' }, 'A reserva dura 10 minutos. A disponibilidade é atualizada a cada 5 segundos.'));
}
