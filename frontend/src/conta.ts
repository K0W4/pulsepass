import { chamar, mensagemDeErro } from './api';
import { renovarSessao, temPapel } from './auth';
import { aviso, h } from './dom';
import { cnpjFormatado, dataHora } from './util';

interface Solicitacao { id: string; razao_social: string; cnpj: string; status: string; criada_em: string; decidida_em: string | null; motivo: string | null }

/** US05: o cliente pede para virar organizador; um admin aprova. */
export async function soliciteOrganizador(): Promise<HTMLElement> {
  const raiz = h('section', {}, h('h1', { tabindex: -1 }, 'Quero ser organizador'));
  if (temPapel('organizador')) {
    raiz.append(h('p', {}, 'Você já é organizador. '), h('a', { class: 'botao', href: '#/organizador' }, 'Abrir a área do organizador'));
    return raiz;
  }
  const atual = await chamar<Solicitacao>('GET', '/iam/minha-solicitacao');
  const existente = atual.status === 200 ? atual.dados : null;

  if (existente?.status === 'PENDENTE') {
    raiz.append(h('p', { role: 'status' },
      `Recebemos o pedido de ${existente.razao_social} (CNPJ ${cnpjFormatado(existente.cnpj)}) em ${dataHora(existente.criada_em)}. Um administrador vai analisá-lo.`));
    return raiz;
  }
  if (existente?.status === 'APROVADA') {
    raiz.append(
      h('p', { role: 'status' }, `Pedido aprovado${existente.decidida_em ? ` em ${dataHora(existente.decidida_em)}` : ''}. Atualize sua sessão para ativar o novo papel.`),
      h('button', {
        class: 'botao', type: 'button', onclick: async () => {
          await renovarSessao();
          location.hash = '#/organizador';
          location.reload();
        },
      }, 'Atualizar sessão'));
    return raiz;
  }
  if (existente?.status === 'REJEITADA') {
    raiz.append(h('p', { class: 'nota', role: 'status' }, `Seu último pedido foi rejeitado${existente.motivo ? `: ${existente.motivo}` : ''}. Você pode enviar um novo.`));
  }

  const campo = (id: string, rotulo: string, extra: Record<string, unknown> = {}): HTMLElement =>
    h('p', {}, h('label', { htmlFor: id }, rotulo), h('input', { id, name: id, type: 'text', required: true, ...extra }));
  const valor = (f: HTMLFormElement, n: string): string => (f.elements.namedItem(n) as HTMLInputElement).value;

  raiz.append(h('form', {
    class: 'formulario',
    onsubmit: async (e: Event) => {
      e.preventDefault();
      const f = e.target as HTMLFormElement;
      const r = await chamar('POST', '/iam/organizadores/solicitacoes', {
        corpo: { razaoSocial: valor(f, 'razaoSocial'), cnpj: valor(f, 'cnpj'), contato: valor(f, 'contato') },
      });
      if (r.status === 201) {
        window.dispatchEvent(new HashChangeEvent('hashchange'));
        aviso('Pedido enviado. Você será avisado quando um administrador decidir.', 'ok');
      } else if (r.status === 400) {
        aviso('Confira os dados: o CNPJ precisa ser válido e todos os campos são obrigatórios.', 'erro');
      } else if (r.status === 409) {
        aviso('Você já tem um pedido pendente.', 'erro');
      } else {
        aviso(mensagemDeErro(r.status), 'erro');
      }
    },
  },
  h('fieldset', {}, h('legend', {}, 'Dados da empresa'),
    campo('razaoSocial', 'Razão social', { maxLength: 200 }),
    campo('cnpj', 'CNPJ', { inputMode: 'text', placeholder: '00.000.000/0000-00', maxLength: 18 }),
    campo('contato', 'Contato (e-mail ou telefone)', { maxLength: 200 }),
    h('button', { class: 'botao', type: 'submit' }, 'Enviar pedido'))));
  return raiz;
}
