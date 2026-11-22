import { chamar, mensagemDeErro } from './api';
import { aoSair, aviso, h } from './dom';
import { painelTelemetria } from './painel';
import { cnpjFormatado, dataHora } from './util';

interface Solicitacao { id: string; usuario_id: string; razao_social: string; cnpj: string; contato: string; status: string; criada_em: string; motivo: string | null }
interface Registro { id: string; origem: string; tipo: string; usuario_id: string | null; ip: string | null; detalhes: string | null; ocorrido_em: string }
interface Operacional {
  disponivel: boolean;
  latenciaP95Segundos?: Record<string, number>;
  errosPorSegundo?: Record<string, number>;
  lagKafka?: Record<string, number>;
}

function tabela(legenda: string, cabecalhos: string[], linhas: Array<Array<string | HTMLElement>>): HTMLElement {
  return h('table', { class: 'tabela' },
    h('caption', {}, legenda),
    h('thead', {}, h('tr', {}, cabecalhos.map((t) => h('th', { scope: 'col' }, t)))),
    h('tbody', {}, linhas.map((l) => h('tr', {}, l.map((c, i) => (i === 0 ? h('th', { scope: 'row' }, c) : h('td', {}, c)))))));
}

/** US05: o admin decide as solicitações de organizador. */
function secaoSolicitacoes(): HTMLElement {
  const lista = h('div', { 'aria-live': 'polite' });
  const filtro = h('select', { id: 'filtro-status', name: 'filtro-status', onchange: () => void carregar() },
    ['PENDENTE', 'APROVADA', 'REJEITADA', ''].map((s) => h('option', { value: s }, s || 'Todas')));

  async function decidir(id: string, aprovar: boolean, motivo: string): Promise<void> {
    const r = await chamar('POST', `/iam/organizadores/solicitacoes/${id}/${aprovar ? 'aprovar' : 'rejeitar'}`,
      aprovar ? {} : { corpo: { motivo: motivo || null } });
    if (r.status === 204) {
      aviso(aprovar ? 'Organizador aprovado.' : 'Solicitação rejeitada.', 'ok');
      await carregar();
    } else if (r.status === 409) {
      aviso('Esta solicitação já foi decidida.', 'erro');
      await carregar();
    } else if (r.status === 503) {
      aviso('O provedor de identidade não respondeu; nada foi alterado. Tente de novo.', 'erro');
    } else {
      aviso(mensagemDeErro(r.status), 'erro');
    }
  }

  async function carregar(): Promise<void> {
    const status = (filtro as HTMLSelectElement).value;
    const r = await chamar<Solicitacao[]>('GET', `/iam/organizadores/solicitacoes${status ? `?status=${status}` : ''}`);
    if (r.status !== 200) {
      lista.replaceChildren(h('p', {}, mensagemDeErro(r.status)));
      return;
    }
    if (r.dados.length === 0) {
      lista.replaceChildren(h('p', {}, 'Nenhuma solicitação neste filtro.'));
      return;
    }
    lista.replaceChildren(tabela('Solicitações de organizador',
      ['Empresa', 'CNPJ', 'Contato', 'Pedido em', 'Situação', 'Ação'],
      r.dados.map((s) => {
        const motivo = h('input', { type: 'text', 'aria-label': `Motivo da rejeição de ${s.razao_social}`, placeholder: 'Motivo (opcional)' });
        return [
          s.razao_social, cnpjFormatado(s.cnpj), s.contato, dataHora(s.criada_em),
          s.status + (s.motivo ? `: ${s.motivo}` : ''),
          s.status === 'PENDENTE'
            ? h('span', { class: 'barra' },
                h('button', { class: 'botao', type: 'button', onclick: () => void decidir(s.id, true, '') }, 'Aprovar'),
                motivo,
                h('button', { class: 'botao-sec', type: 'button', onclick: () => void decidir(s.id, false, motivo.value.trim()) }, 'Rejeitar'))
            : '—',
        ];
      })));
  }

  void carregar();
  return h('fieldset', { class: 'painel' }, h('legend', {}, 'Organizadores'),
    h('p', {}, h('label', { htmlFor: 'filtro-status' }, 'Mostrar: '), filtro), lista);
}

/** US06: histórico de acessos e decisões. */
function secaoAuditoria(): HTMLElement {
  const lista = h('div', { 'aria-live': 'polite' });
  const tipo = h('select', { id: 'filtro-tipo', name: 'filtro-tipo', onchange: () => void carregar() },
    ['', 'LOGIN', 'LOGIN_ERROR', 'LOGOUT', 'REGISTER', 'UPDATE_PASSWORD', 'ORGANIZADOR_SOLICITADO', 'ORGANIZADOR_APROVADO', 'ORGANIZADOR_REJEITADO']
      .map((t) => h('option', { value: t }, t || 'Todos os tipos')));

  async function carregar(): Promise<void> {
    const t = (tipo as HTMLSelectElement).value;
    const r = await chamar<Registro[]>('GET', `/iam/auditoria?limite=50${t ? `&tipo=${t}` : ''}`);
    if (r.status !== 200) {
      lista.replaceChildren(h('p', {}, mensagemDeErro(r.status)));
      return;
    }
    lista.replaceChildren(r.dados.length === 0
      ? h('p', {}, 'Nenhum registro ainda.')
      : tabela('Últimos registros', ['Quando', 'Tipo', 'Usuário', 'IP', 'Origem', 'Detalhes'],
          r.dados.map((x) => [dataHora(x.ocorrido_em), x.tipo, x.usuario_id ?? '—', x.ip ?? '—', x.origem, x.detalhes ?? ''])));
  }

  void carregar();
  return h('fieldset', { class: 'painel' }, h('legend', {}, 'Auditoria de acessos'),
    h('p', {}, h('label', { htmlFor: 'filtro-tipo' }, 'Filtrar por tipo: '), tipo,
      ' ', h('button', { class: 'botao-sec', type: 'button', onclick: () => void carregar() }, 'Atualizar')), lista);
}

/** US33: latência, erros e lag do Kafka, lidos do Prometheus pelo telemetria-service. */
function secaoSaude(): HTMLElement {
  const area = h('div', { 'aria-live': 'off' });

  async function carregar(): Promise<void> {
    const r = await chamar<Operacional>('GET', '/telemetria/operacional');
    if (r.status !== 200) {
      area.replaceChildren(h('p', {}, mensagemDeErro(r.status)));
      return;
    }
    if (!r.dados.disponivel) {
      area.replaceChildren(h('p', {}, 'O Prometheus não respondeu; as métricas operacionais voltam quando ele voltar.'));
      return;
    }
    const lat = r.dados.latenciaP95Segundos ?? {};
    const err = r.dados.errosPorSegundo ?? {};
    const lag = r.dados.lagKafka ?? {};
    const apps = [...new Set([...Object.keys(lat), ...Object.keys(err), ...Object.keys(lag)])].sort();
    area.replaceChildren(tabela('Saúde por serviço (janela de 1 minuto)',
      ['Serviço', 'Latência p95', 'Erros 5xx por s', 'Lag máximo do Kafka'],
      apps.map((a) => [a, a in lat ? `${(lat[a] * 1000).toFixed(0)} ms` : '—', a in err ? err[a].toFixed(2) : '0.00', a in lag ? String(Math.round(lag[a])) : '—'])));
  }

  void carregar();
  const timer = window.setInterval(() => void carregar(), 5000);
  aoSair(() => window.clearInterval(timer));
  return h('fieldset', { class: 'painel' }, h('legend', {}, 'Saúde da plataforma'), area);
}

export function admin(): HTMLElement {
  return h('section', {}, h('h1', { tabindex: -1 }, 'Administração'),
    secaoSolicitacoes(), secaoAuditoria(), secaoSaude(), painelTelemetria());
}
