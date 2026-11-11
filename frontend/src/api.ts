import { token } from './auth';

export interface Resposta<T = any> { // eslint-disable-line @typescript-eslint/no-explicit-any
  status: number;
  dados: T;
}

interface Opcoes {
  corpo?: unknown;
  chave?: string;
}

/** Chama a API pelo gateway (/api/*). Não lança para 4xx/5xx: o chamador decide pela resposta. */
export async function chamar<T = any>(metodo: string, caminho: string, opcoes: Opcoes = {}): Promise<Resposta<T>> { // eslint-disable-line @typescript-eslint/no-explicit-any
  const cab: Record<string, string> = {};
  if (opcoes.corpo !== undefined) cab['Content-Type'] = 'application/json';
  if (opcoes.chave) cab['Idempotency-Key'] = opcoes.chave;
  const t = await token();
  if (t) cab['Authorization'] = `Bearer ${t}`;
  let r: Response;
  try {
    r = await fetch(`/api${caminho}`, {
      method: metodo,
      headers: cab,
      body: opcoes.corpo !== undefined ? JSON.stringify(opcoes.corpo) : undefined,
    });
  } catch {
    return { status: 0, dados: null as T };
  }
  const json = (r.headers.get('content-type') ?? '').includes('json');
  const dados = json ? await r.json().catch(() => null) : null;
  return { status: r.status, dados: dados as T };
}

export async function baixarPdf(id: string): Promise<boolean> {
  const t = await token();
  const r = await fetch(`/api/ingressos/${id}/pdf`, { headers: t ? { Authorization: `Bearer ${t}` } : {} });
  if (!r.ok) return false;
  const url = URL.createObjectURL(await r.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = `ingresso-${id}.pdf`;
  a.click();
  URL.revokeObjectURL(url);
  return true;
}

export function mensagemDeErro(status: number): string {
  switch (status) {
    case 0: return 'Sem conexão com o servidor. Tente de novo.';
    case 401: return 'Sua sessão expirou. Entre novamente.';
    case 403: return 'Você não tem permissão para esta ação.';
    case 404: return 'Não encontrado.';
    case 409: return 'Indisponível: alguém foi mais rápido ou o prazo acabou.';
    case 422: return 'Dados inválidos.';
    case 503: return 'Serviço temporariamente indisponível. Tente em instantes.';
    default: return `Algo deu errado (código ${status}).`;
  }
}
