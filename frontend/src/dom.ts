// Construção de DOM sem innerHTML: todo texto entra como nó de texto (sem risco de XSS).
type Filho = Node | string | null | undefined | false;

export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  atributos: Record<string, unknown> = {},
  ...filhos: Array<Filho | Filho[]>
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [chave, valor] of Object.entries(atributos)) {
    if (valor === undefined || valor === null || valor === false) continue;
    if (chave.startsWith('on') && typeof valor === 'function') {
      el.addEventListener(chave.slice(2).toLowerCase(), valor as EventListener);
    } else if (chave === 'class') {
      el.className = String(valor);
    } else if (chave in el && chave !== 'list') {
      (el as unknown as Record<string, unknown>)[chave] = valor;
    } else {
      el.setAttribute(chave, valor === true ? '' : String(valor));
    }
  }
  for (const f of filhos.flat()) {
    if (f) el.append(f);
  }
  return el;
}

const saidas: Array<() => void> = [];

/** Registra uma limpeza (timers) executada quando o usuário troca de tela. */
export function aoSair(fn: () => void): void {
  saidas.push(fn);
}

export function limparTela(): void {
  while (saidas.length) saidas.pop()?.();
}

export function aviso(mensagem: string, tipo: 'ok' | 'erro' | 'info' = 'info'): void {
  const el = document.getElementById('aviso');
  if (!el) return;
  el.className = `aviso ${mensagem ? tipo : ''}`;
  el.textContent = mensagem;
}
