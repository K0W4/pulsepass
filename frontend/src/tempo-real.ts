import { token } from './auth';
import { aoSair } from './dom';
import { esperaDeReconexao, urlDoCanal } from './util';

export interface Canal {
  fechar(): void;
}

export interface OpcoesCanal {
  /** Canais de pedido exigem o token do cliente na query. */
  comToken?: boolean;
  aoMensagem: (mensagem: Record<string, unknown>) => void;
  /** Avisado a cada conexão e queda, para a tela escolher entre o tempo real e o polling. */
  aoMudarEstado?: (conectado: boolean) => void;
}

/**
 * Abre um WebSocket em /api/realtime{caminho} e reconecta com recuo exponencial. Fecha sozinho
 * quando o usuário troca de tela. Se o socket não abrir, a tela continua funcionando por polling.
 */
export function abrirCanal(caminho: string, opcoes: OpcoesCanal): Canal {
  let socket: WebSocket | undefined;
  let tentativa = 0;
  let fechado = false;
  let espera: number | undefined;

  const conectar = async (): Promise<void> => {
    if (fechado) return;
    const t = opcoes.comToken ? await token() : undefined;
    if (opcoes.comToken && !t) return; // sem login não há canal de pedido
    if (fechado) return;
    const s = new WebSocket(urlDoCanal(location, caminho, t));
    socket = s;
    s.onopen = () => {
      tentativa = 0;
      opcoes.aoMudarEstado?.(true);
    };
    s.onmessage = (e: MessageEvent) => {
      try {
        opcoes.aoMensagem(JSON.parse(String(e.data)) as Record<string, unknown>);
      } catch {
        // mensagem que não é JSON: ignorada
      }
    };
    s.onclose = () => {
      opcoes.aoMudarEstado?.(false);
      if (!fechado) espera = window.setTimeout(() => void conectar(), esperaDeReconexao(tentativa++));
    };
    s.onerror = () => s.close();
  };

  void conectar();
  const canal: Canal = {
    fechar() {
      fechado = true;
      window.clearTimeout(espera);
      socket?.close();
    },
  };
  aoSair(() => canal.fechar());
  return canal;
}
