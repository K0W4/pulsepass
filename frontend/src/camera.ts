// Leitura de QR Code pela câmera com a API nativa BarcodeDetector (sem biblioteca extra).
// Limite: só os navegadores que a implementam (Chrome/Edge no Android e no macOS); nos demais a
// portaria continua com a digitação do código.

interface DetectorDeCodigos {
  detect(fonte: HTMLVideoElement): Promise<Array<{ rawValue: string }>>;
}
interface FabricaDeDetector {
  new (opcoes?: { formats: string[] }): DetectorDeCodigos;
}

export function cameraDisponivel(): boolean {
  return 'BarcodeDetector' in window && Boolean(navigator.mediaDevices?.getUserMedia);
}

/**
 * Mostra a câmera em {@code video} até ler um QR Code e devolve o texto lido; null se o usuário
 * cancelar (chamando {@code cancelar}) ou se a câmera não puder ser aberta.
 */
export async function lerQr(video: HTMLVideoElement, cancelado: () => boolean): Promise<string | null> {
  if (!cameraDisponivel()) return null;
  const Detector = (window as unknown as { BarcodeDetector: FabricaDeDetector }).BarcodeDetector;
  const detector = new Detector({ formats: ['qr_code'] });
  let fluxo: MediaStream;
  try {
    fluxo = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
  } catch {
    return null;
  }
  video.srcObject = fluxo;
  await video.play().catch(() => undefined);
  try {
    while (!cancelado()) {
      const achados = await detector.detect(video).catch(() => []);
      if (achados.length > 0) return achados[0].rawValue;
      await new Promise((r) => setTimeout(r, 250));
    }
    return null;
  } finally {
    fluxo.getTracks().forEach((t) => t.stop());
    video.srcObject = null;
  }
}
