import { chamar, mensagemDeErro } from './api';
import { aoSair, aviso, h } from './dom';
import { cameraDisponivel, lerQr } from './camera';
import { painelTelemetria } from './painel';
import { abrirCanal, type Canal } from './tempo-real';
import { dataHora, gerarCodigos, reais } from './util';

interface Evento { id: string; nome: string; data_hora: string; status?: string; fila_ativa?: boolean }
interface Lote { id: string; nome: string }
interface VendaLote { id: string; nome: string; quantidade: number; qtd_reservada: number; qtd_vendida: number; total_vendido: number }

const campo = (id: string, rotulo: string, tipo = 'text', extra: Record<string, unknown> = {}): HTMLElement =>
  h('p', {}, h('label', { htmlFor: id }, rotulo), h('input', { id, name: id, type: tipo, required: true, ...extra }));

const valor = (form: HTMLFormElement, nome: string): string => (form.elements.namedItem(nome) as HTMLInputElement).value;
const isoLocal = (v: string): string => new Date(v).toISOString();

/** Redesenha a tela (as listas passam a incluir o item novo) e mostra a confirmação. */
function salvo(mensagem: string): void {
  window.dispatchEvent(new HashChangeEvent('hashchange'));
  aviso(mensagem, 'ok');
}

function seletorDeEventos(id: string, eventos: Evento[], aoMudar?: () => void): HTMLSelectElement {
  return h('select', { id, name: id, required: true, onchange: aoMudar },
    eventos.map((e) => h('option', { value: e.id }, `${e.nome} (${dataHora(e.data_hora)})`)));
}

/** US10 e US18: o organizador publica, despublica e liga a fila virtual dos seus eventos. */
function secaoMeusEventos(eventos: Evento[]): HTMLElement {
  async function publicar(e: Evento, publicado: boolean): Promise<void> {
    const r = await chamar('POST', `/eventos/${e.id}/${publicado ? 'publicar' : 'despublicar'}`);
    if (r.status === 204) salvo(publicado ? 'Evento publicado: já aparece no catálogo.' : 'Evento despublicado: saiu do catálogo.');
    else aviso(mensagemDeErro(r.status), 'erro');
  }
  async function fila(e: Evento, ativa: boolean): Promise<void> {
    const r = await chamar('PUT', `/eventos/${e.id}/fila-ativa`, { corpo: { ativa } });
    if (r.status === 204) salvo(ativa ? 'Fila virtual ligada.' : 'Fila virtual desligada.');
    else aviso(mensagemDeErro(r.status), 'erro');
  }
  if (eventos.length === 0) {
    return h('fieldset', { class: 'painel' }, h('legend', {}, 'Meus eventos'), h('p', {}, 'Você ainda não criou eventos.'));
  }
  return h('fieldset', { class: 'painel' }, h('legend', {}, 'Meus eventos'),
    h('table', { class: 'tabela' },
      h('caption', { class: 'so-leitor' }, 'Meus eventos'),
      h('thead', {}, h('tr', {}, ['Evento', 'Data', 'Situação', 'Fila virtual', 'Ações'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, eventos.map((e) => {
        const publicado = e.status !== 'RASCUNHO';
        const filaLigada = Boolean(e.fila_ativa);
        return h('tr', {},
          h('th', { scope: 'row' }, e.nome),
          h('td', {}, dataHora(e.data_hora)),
          h('td', {}, publicado ? 'Publicado' : 'Rascunho'),
          h('td', {}, filaLigada ? 'Ligada' : 'Desligada'),
          h('td', {}, h('span', { class: 'barra' },
            h('button', { class: 'botao-sec', type: 'button', onclick: () => void publicar(e, !publicado) }, publicado ? 'Despublicar' : 'Publicar'),
            h('button', { class: 'botao-sec', type: 'button', onclick: () => void fila(e, !filaLigada) }, filaLigada ? 'Desligar fila' : 'Ligar fila'))));
      }))));
}

export async function organizador(): Promise<HTMLElement> {
  const lista = await chamar<Evento[]>('GET', '/eventos/meus'); // inclui rascunhos
  const eventos = lista.status === 200 ? lista.dados : [];
  const raiz = h('section', {}, h('h1', { tabindex: -1 }, 'Área do organizador'));

  const formEvento = h('form', { class: 'formulario', onsubmit: async (e: Event) => {
    e.preventDefault();
    const f = e.target as HTMLFormElement;
    const r = await chamar<{ id: string }>('POST', '/eventos', {
      corpo: {
        nome: valor(f, 'nome'), local: valor(f, 'local'), dataHora: isoLocal(valor(f, 'data')), imagemUrl: valor(f, 'imagem') || null,
        publicar: (f.elements.namedItem('publicar') as HTMLInputElement).checked,
      },
    });
    if (r.status === 201) salvo('Evento criado. Agora crie um lote e os setores dele.');
    else aviso(mensagemDeErro(r.status), 'erro');
  } },
    h('fieldset', {}, h('legend', {}, 'Novo evento'),
      campo('nome', 'Nome'), campo('local', 'Local (cidade e endereço)'), campo('data', 'Data e hora', 'datetime-local'),
      h('p', {}, h('label', { htmlFor: 'imagem' }, 'Imagem (URL, opcional)'), h('input', { id: 'imagem', name: 'imagem', type: 'url' })),
      h('p', {}, h('input', { id: 'publicar', name: 'publicar', type: 'checkbox', checked: true }),
        ' ', h('label', { htmlFor: 'publicar' }, 'Publicar no catálogo agora (desmarque para criar como rascunho)')),
      h('button', { class: 'botao', type: 'submit' }, 'Criar evento')));

  const formLote = h('form', { class: 'formulario', onsubmit: async (e: Event) => {
    e.preventDefault();
    const f = e.target as HTMLFormElement;
    const r = await chamar('POST', `/eventos/${valor(f, 'evento-lote')}/lotes`, {
      corpo: { nome: valor(f, 'nome-lote'), preco: Number(valor(f, 'preco')), quantidade: Number(valor(f, 'quantidade')),
        inicioVenda: isoLocal(valor(f, 'inicio')), fimVenda: isoLocal(valor(f, 'fim')) },
    });
    if (r.status === 201) salvo('Lote criado.');
    else aviso(mensagemDeErro(r.status), 'erro');
  } },
    h('fieldset', {}, h('legend', {}, 'Novo lote'),
      h('p', {}, h('label', { htmlFor: 'evento-lote' }, 'Evento'), seletorDeEventos('evento-lote', eventos)),
      campo('nome-lote', 'Nome do lote'), campo('preco', 'Preço (R$)', 'number', { min: 0, step: '0.01' }),
      campo('quantidade', 'Quantidade', 'number', { min: 1 }),
      campo('inicio', 'Início das vendas', 'datetime-local'), campo('fim', 'Fim das vendas', 'datetime-local'),
      h('button', { class: 'botao', type: 'submit' }, 'Criar lote')));

  const seletorLote = h('select', { id: 'lote-setor', name: 'lote-setor', required: true });
  const carregarLotes = async (): Promise<void> => {
    const ev = (document.getElementById('evento-setor') as HTMLSelectElement | null)?.value;
    if (!ev) return;
    const r = await chamar<{ lotes: Lote[] }>('GET', `/eventos/${ev}`);
    seletorLote.replaceChildren(...(r.status === 200 ? r.dados.lotes : []).map((l) => h('option', { value: l.id }, l.nome)));
  };
  const formSetor = h('form', { class: 'formulario', onsubmit: async (e: Event) => {
    e.preventDefault();
    const f = e.target as HTMLFormElement;
    const r = await chamar('POST', `/eventos/${valor(f, 'evento-setor')}/setores`, {
      corpo: { nome: valor(f, 'nome-setor'), marcado: true, loteId: valor(f, 'lote-setor'), codigos: gerarCodigos(valor(f, 'prefixo'), Number(valor(f, 'qtd-assentos'))) },
    });
    if (r.status === 201) salvo('Setor e assentos criados.');
    else aviso(mensagemDeErro(r.status), 'erro');
  } },
    h('fieldset', {}, h('legend', {}, 'Novo setor com assentos numerados'),
      h('p', {}, h('label', { htmlFor: 'evento-setor' }, 'Evento'), seletorDeEventos('evento-setor', eventos, () => void carregarLotes())),
      h('p', {}, h('label', { htmlFor: 'lote-setor' }, 'Lote (define o preço)'), seletorLote),
      campo('nome-setor', 'Nome do setor'), campo('prefixo', 'Prefixo dos assentos (ex.: A)'),
      campo('qtd-assentos', 'Quantidade de assentos', 'number', { min: 1, max: 5000 }),
      h('button', { class: 'botao', type: 'submit' }, 'Criar setor')));

  // painel de vendas ao vivo (US31 e US32): atualiza sozinho quando um assento muda
  const painel = h('div', { 'aria-live': 'polite' });
  let canalVendas: Canal | undefined;
  let adiar: number | undefined;
  let renovar: number | undefined;
  async function mostrarVendas(ev: string): Promise<void> {
    const r = await chamar<VendaLote[]>('GET', `/eventos/${ev}/vendas`);
    painel.replaceChildren(r.status !== 200 ? h('p', {}, mensagemDeErro(r.status)) :
      h('table', { class: 'tabela' },
        h('caption', {}, 'Ocupação por lote (atualiza sozinha)'),
        h('thead', {}, h('tr', {}, ['Lote', 'Capacidade', 'Reservados', 'Vendidos', 'Total vendido'].map((t) => h('th', { scope: 'col' }, t)))),
        h('tbody', {}, r.dados.map((l) => h('tr', {}, h('th', { scope: 'row' }, l.nome), h('td', {}, String(l.quantidade)),
          h('td', {}, String(l.qtd_reservada)), h('td', {}, String(l.qtd_vendida)), h('td', {}, reais(l.total_vendido)))))));
  }
  const formVendas = h('form', { class: 'formulario', onsubmit: async (e: Event) => {
    e.preventDefault();
    const ev = valor(e.target as HTMLFormElement, 'evento-vendas');
    await mostrarVendas(ev);
    canalVendas?.fechar();
    window.clearInterval(renovar);
    canalVendas = abrirCanal(`/eventos/${ev}`, {
      aoMensagem: () => {
        window.clearTimeout(adiar);
        adiar = window.setTimeout(() => void mostrarVendas(ev), 500);
      },
    });
    // lotes sem assento marcado não geram evento de assento: uma releitura periódica cobre esse caso
    renovar = window.setInterval(() => void mostrarVendas(ev), 10_000);
  } },
    h('fieldset', {}, h('legend', {}, 'Painel de vendas'),
      h('p', {}, h('label', { htmlFor: 'evento-vendas' }, 'Evento'), seletorDeEventos('evento-vendas', eventos)),
      h('button', { class: 'botao', type: 'submit' }, 'Ver vendas')), painel);
  aoSair(() => {
    window.clearTimeout(adiar);
    window.clearInterval(renovar);
  });

  raiz.append(secaoMeusEventos(eventos), formEvento, formLote, formSetor, formVendas, painelTelemetria());
  void carregarLotes();
  return raiz;
}

export function portaria(): HTMLElement {
  const resultado = h('div', { class: 'banner', role: 'status', 'aria-live': 'assertive' });
  const campoCodigo = h('input', { id: 'codigo', name: 'codigo', type: 'text', required: true, autocomplete: 'off', autofocus: true });
  const video = h('video', { class: 'camera', playsInline: true, muted: true, hidden: true, 'aria-label': 'Imagem da câmera' });
  let parar = false;
  aoSair(() => { parar = true; });

  async function validar(codigo: string): Promise<void> {
    const r = await chamar<{ resultado: string; mensagem?: string; evento?: string; descricao?: string; primeiraLeitura?: string }>(
      'POST', '/validacoes', { corpo: { codigo: codigo.trim() } });
    const d = r.dados;
    if (r.status === 200) {
      resultado.className = 'banner ok';
      resultado.replaceChildren(h('strong', {}, 'Entrada liberada'), h('p', {}, `${d.evento}, ${d.descricao}`));
    } else if (r.status === 409) {
      resultado.className = 'banner alerta';
      resultado.replaceChildren(h('strong', {}, 'Ingresso já utilizado'), h('p', {}, `Primeira leitura: ${d.primeiraLeitura ? dataHora(d.primeiraLeitura) : 'desconhecida'}`));
    } else if (r.status === 422) {
      resultado.className = 'banner erro';
      resultado.replaceChildren(h('strong', {}, 'Ingresso inválido'), h('p', {}, 'O QR Code foi alterado ou não existe.'));
    } else {
      resultado.className = 'banner erro';
      resultado.replaceChildren(h('strong', {}, mensagemDeErro(r.status)));
    }
    campoCodigo.select();
  }

  const botaoCamera = h('button', { class: 'botao-sec', type: 'button', onclick: async () => {
    if (!cameraDisponivel()) {
      aviso('Este navegador não lê QR Code pela câmera. Digite o código ou use o Chrome ou o Edge.', 'info');
      return;
    }
    if (!video.hidden) { // segundo clique: para a câmera
      parar = true;
      return;
    }
    parar = false;
    video.hidden = false;
    botaoCamera.textContent = 'Parar a câmera';
    const lido = await lerQr(video, () => parar);
    video.hidden = true;
    botaoCamera.textContent = 'Ler com a câmera';
    if (lido) {
      campoCodigo.value = lido;
      await validar(lido);
    } else if (!parar) {
      aviso('Não foi possível abrir a câmera. Digite o código.', 'erro');
    }
  } }, 'Ler com a câmera');

  const form = h('form', { class: 'formulario', onsubmit: async (e: Event) => {
    e.preventDefault();
    await validar(campoCodigo.value);
  } },
    h('fieldset', {}, h('legend', {}, 'Validar ingresso'),
      h('p', {}, h('label', { htmlFor: 'codigo' }, 'Código do QR Code'), campoCodigo),
      h('div', { class: 'barra' }, h('button', { class: 'botao', type: 'submit' }, 'Validar'), botaoCamera),
      video));
  return h('section', {}, h('h1', { tabindex: -1 }, 'Portaria'), form, resultado);
}
