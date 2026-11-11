import QRCode from 'qrcode';
import { baixarPdf, chamar, mensagemDeErro } from './api';
import { aoSair, aviso, h } from './dom';
import { dataHora, mmss, reais, segundosRestantes } from './util';

interface Item { assento_id: string | null; descricao: string; quantidade: number; preco_unitario: number }
interface Pedido { id: string; reserva_id: string; status: string; total: number; expira_em: string | null; itens: Item[] }
interface Ingresso { id: string; pedido_id: string; evento_nome: string; descricao: string; status: string; emitido_em: string; codigo: string }

const esperar = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

export async function checkout(reservaId: string): Promise<HTMLElement> {
  const raiz = h('section', {}, h('h1', { tabindex: -1 }, 'Revise e pague'));
  let r = await chamar<Pedido>('POST', '/pedidos', { corpo: { reservaId } });
  if (r.status !== 201) {
    aviso(mensagemDeErro(r.status), 'erro');
    raiz.append(h('a', { href: '#/' }, 'Voltar ao catálogo'));
    return raiz;
  }
  // o pedido nasce CRIADO e vira RESERVADO quando o evento do Inventário chega ao Checkout
  for (let i = 0; i < 20 && r.dados.status === 'CRIADO'; i++) {
    await esperar(1000);
    r = await chamar<Pedido>('GET', `/pedidos/${r.dados.id}`);
  }
  const pedido = r.dados;
  if (pedido.status !== 'RESERVADO' || !pedido.expira_em) {
    aviso('Esta reserva não está mais disponível.', 'erro');
    raiz.append(h('a', { href: '#/' }, 'Voltar ao catálogo'));
    return raiz;
  }

  const expiraEm = pedido.expira_em;
  const relogio = h('strong', { role: 'timer' });
  const resultado = h('div', { 'aria-live': 'polite' });
  const botaoPagar = h('button', { class: 'botao', type: 'button', onclick: () => void pagar() }, `Pagar ${reais(pedido.total)}`);
  const botaoCancelar = h('button', { class: 'botao-sec', type: 'button', onclick: () => void cancelar() }, 'Cancelar reserva');
  let chave = crypto.randomUUID();

  function tique(): void {
    const s = segundosRestantes(expiraEm);
    relogio.textContent = mmss(s);
    if (s === 0) {
      botaoPagar.disabled = true;
      aviso('O prazo da reserva acabou. Os ingressos voltaram à venda.', 'erro');
    }
  }
  tique();
  const timer = window.setInterval(tique, 1000);
  aoSair(() => window.clearInterval(timer));

  async function cancelar(): Promise<void> {
    const c = await chamar('DELETE', `/reservas/${pedido.reserva_id}`);
    if (c.status === 204) {
      aviso('Reserva cancelada.', 'ok');
      location.hash = '#/';
    } else {
      aviso(mensagemDeErro(c.status), 'erro');
    }
  }

  async function pagar(): Promise<void> {
    botaoPagar.disabled = true;
    botaoCancelar.disabled = true;
    aviso('Processando o pagamento...', 'info');
    // a mesma chave em repetições evita cobrança duplicada (RNF03); recusa pede chave nova
    const p = await chamar<{ status: string }>('POST', '/pagamentos', { corpo: { pedidoId: pedido.id }, chave });
    if (p.status === 200) {
      aviso('Pagamento aprovado! Emitindo seus ingressos...', 'ok');
      for (let i = 0; i < 30; i++) {
        const lista = await chamar<Ingresso[]>('GET', '/ingressos');
        if (lista.status === 200 && lista.dados.some((x) => x.pedido_id === pedido.id)) {
          aviso('', 'info');
          location.hash = '#/ingressos';
          return;
        }
        await esperar(1000);
      }
      aviso('Pagamento aprovado. Seus ingressos aparecem em "Meus ingressos" em instantes.', 'ok');
      return;
    }
    if (p.status === 402) {
      chave = crypto.randomUUID();
      aviso('Pagamento recusado. Verifique os dados e tente de novo.', 'erro');
    } else {
      aviso(mensagemDeErro(p.status), 'erro');
    }
    botaoPagar.disabled = segundosRestantes(expiraEm) === 0;
    botaoCancelar.disabled = false;
  }

  raiz.append(
    h('p', {}, 'Tempo restante da reserva: ', relogio),
    h('table', { class: 'tabela' },
      h('caption', { class: 'so-leitor' }, 'Itens do pedido'),
      h('thead', {}, h('tr', {}, ['Item', 'Qtd.', 'Preço'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, pedido.itens.map((i) =>
        h('tr', {}, h('th', { scope: 'row' }, i.descricao), h('td', {}, String(i.quantidade)), h('td', {}, reais(i.preco_unitario)))))),
    h('p', { class: 'total' }, `Total: ${reais(pedido.total)}`),
    h('div', { class: 'barra' }, botaoPagar, botaoCancelar),
    resultado);
  return raiz;
}

export async function meusIngressos(): Promise<HTMLElement> {
  const raiz = h('section', {}, h('h1', { tabindex: -1 }, 'Meus ingressos'));
  const r = await chamar<Ingresso[]>('GET', '/ingressos');
  if (r.status !== 200) {
    aviso(mensagemDeErro(r.status), 'erro');
    return raiz;
  }
  if (r.dados.length === 0) {
    raiz.append(h('p', {}, 'Você ainda não tem ingressos. ', h('a', { href: '#/' }, 'Ver eventos')));
    return raiz;
  }
  const maisNovosPrimeiro = [...r.dados].sort((x, y) => y.emitido_em.localeCompare(x.emitido_em));
  const cartoes = await Promise.all(maisNovosPrimeiro.map(async (i) => {
    const imagem = await QRCode.toDataURL(i.codigo, { width: 220, margin: 1 });
    return h('li', { class: 'cartao ingresso' },
      h('h2', {}, i.evento_nome),
      h('p', {}, i.descricao),
      h('p', {}, `Emitido em ${dataHora(i.emitido_em)} · `, h('strong', {}, i.status === 'UTILIZADO' ? 'Já utilizado' : 'Válido')),
      h('img', { src: imagem, width: 220, height: 220, alt: `QR Code do ingresso de ${i.evento_nome}, ${i.descricao}` }),
      h('p', {}, h('button', { class: 'botao-sec', type: 'button', onclick: () => void baixarPdf(i.id) }, 'Baixar PDF')));
  }));
  raiz.append(h('ul', { class: 'lista-cartoes' }, cartoes));
  return raiz;
}
