#!/bin/sh
# Gera os PDFs da entrega do Pacote 4 a partir do HTML (Chrome headless; os diagramas usam Mermaid via CDN):
#   PulsePass_Pacote4_Documentacao.pdf, PulsePass_Pacote1_atualizado.pdf e PulsePass_Pacote2_atualizado.pdf
# Antes, gere as evidências (scripts/evidencias.py) e rode os scripts de verificação, que gravam em docs/evidencias.
# Uso: docs/pacotes/build.sh [REPO_URL] [RESULTADO_DA_CI]
# Os PDFs dos pacotes anteriores (Pacote1_entregue, Pacote2_entregue e Pacote3_Documentacao) não são regenerados.
cd "$(dirname "$0")" || exit 1
REPO=${1:-https://github.com/K0W4/pulsepass}
python3 src/gerar_pacote4.py "$REPO" "${2:-a registrar depois do push (a CI só roda no GitHub)}" || exit 1
python3 src/gerar_pacote1.py "$REPO" || exit 1
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for par in pacote4:PulsePass_Pacote4_Documentacao pacote1_atualizado:PulsePass_Pacote1_atualizado pacote2_atualizado:PulsePass_Pacote2_atualizado; do
  src=${par%%:*}; out=${par##*:}
  "$C" --headless=new --disable-gpu --no-pdf-header-footer --virtual-time-budget=60000 --print-to-pdf="$out.pdf" "file://$PWD/src/$src.html" 2>/dev/null
  echo "$out.pdf: $(pdfinfo "$out.pdf" | grep Pages)"
done
