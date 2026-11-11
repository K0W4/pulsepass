import './style.css';
import { cadastrar, entrar, iniciar, logado, sair, temPapel, usuario } from './auth';
import { catalogo, detalhe, mapaDeAssentos } from './catalogo';
import { checkout, meusIngressos } from './compra';
import { aviso, h, limparTela } from './dom';
import { organizador, portaria } from './papeis';

type Tela = (partes: string[]) => HTMLElement | Promise<HTMLElement>;

interface Rota {
  padrao: RegExp;
  titulo: string;
  papel?: string; // exige login e o papel do Keycloak
  tela: Tela;
}

const rotas: Rota[] = [
  { padrao: /^\/$/, titulo: 'Eventos', tela: () => catalogo() },
  { padrao: /^\/evento\/([\w-]+)$/, titulo: 'Detalhe do evento', tela: (p) => detalhe(p[0]) },
  { padrao: /^\/evento\/([\w-]+)\/assentos$/, titulo: 'Escolha de ingressos', papel: 'cliente', tela: (p) => mapaDeAssentos(p[0]) },
  { padrao: /^\/checkout\/([\w-]+)$/, titulo: 'Pagamento', papel: 'cliente', tela: (p) => checkout(p[0]) },
  { padrao: /^\/ingressos$/, titulo: 'Meus ingressos', papel: 'cliente', tela: () => meusIngressos() },
  { padrao: /^\/organizador$/, titulo: 'Área do organizador', papel: 'organizador', tela: () => organizador() },
  { padrao: /^\/portaria$/, titulo: 'Portaria', papel: 'operador', tela: () => portaria() },
];

function desenharMenu(): void {
  const itens: HTMLElement[] = [h('a', { href: '#/' }, 'Eventos')];
  if (temPapel('cliente')) itens.push(h('a', { href: '#/ingressos' }, 'Meus ingressos'));
  if (temPapel('organizador')) itens.push(h('a', { href: '#/organizador' }, 'Organizador'));
  if (temPapel('operador')) itens.push(h('a', { href: '#/portaria' }, 'Portaria'));
  if (logado()) {
    itens.push(h('span', { class: 'usuario' }, usuario()), h('button', { class: 'botao-sec', type: 'button', onclick: sair }, 'Sair'));
  } else {
    itens.push(h('button', { class: 'botao-sec', type: 'button', onclick: entrar }, 'Entrar'),
      h('button', { class: 'botao-sec', type: 'button', onclick: cadastrar }, 'Criar conta'));
  }
  document.getElementById('menu')?.replaceChildren(...itens);
}

async function navegar(): Promise<void> {
  limparTela();
  aviso('', 'info');
  const caminho = location.hash.replace(/^#/, '') || '/';
  const principal = document.getElementById('conteudo') as HTMLElement;
  let conteudo: HTMLElement;
  let titulo = 'Página não encontrada';
  const rota = rotas.map((r) => ({ r, m: caminho.match(r.padrao) })).find((x) => x.m);
  if (!rota) {
    conteudo = h('section', {}, h('h1', { tabindex: -1 }, 'Página não encontrada'), h('a', { href: '#/' }, 'Voltar ao início'));
  } else if (rota.r.papel && !logado()) {
    titulo = rota.r.titulo;
    conteudo = h('section', {}, h('h1', { tabindex: -1 }, 'Entre para continuar'),
      h('p', {}, 'Esta área exige login.'),
      h('button', { class: 'botao', type: 'button', onclick: entrar }, 'Entrar'), ' ',
      h('button', { class: 'botao-sec', type: 'button', onclick: cadastrar }, 'Criar conta'));
  } else if (rota.r.papel && !temPapel(rota.r.papel)) {
    titulo = rota.r.titulo;
    conteudo = h('section', {}, h('h1', { tabindex: -1 }, 'Acesso negado'), h('p', {}, 'Seu perfil não tem acesso a esta área.'));
  } else {
    titulo = rota.r.titulo;
    principal.setAttribute('aria-busy', 'true');
    conteudo = await rota.r.tela((rota.m as RegExpMatchArray).slice(1));
  }
  principal.replaceChildren(conteudo);
  principal.removeAttribute('aria-busy');
  document.title = `${titulo} · PulsePass`;
  // acessibilidade: leva o foco para o título da nova tela
  (conteudo.querySelector('h1') as HTMLElement | null)?.focus();
}

async function principal(): Promise<void> {
  await iniciar();
  const destino = sessionStorage.getItem('rota-apos-login');
  if (destino && logado()) {
    sessionStorage.removeItem('rota-apos-login');
    location.hash = destino;
  }
  desenharMenu();
  window.addEventListener('hashchange', () => void navegar());
  await navegar();
}

void principal();
