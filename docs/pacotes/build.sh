#!/bin/sh
# Gera os PDFs dos pacotes a partir do HTML (Chrome headless; os diagramas usam Mermaid via CDN).
cd "$(dirname "$0")"
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for par in pacote3:PulsePass_Pacote3_Documentacao pacote2_atualizado:PulsePass_Pacote2_atualizado; do
  src=${par%%:*}; out=${par##*:}
  "$C" --headless=new --disable-gpu --no-pdf-header-footer --virtual-time-budget=60000 --print-to-pdf="$out.pdf" "file://$PWD/src/$src.html" 2>/dev/null
  echo "$out.pdf: $(pdfinfo "$out.pdf" | grep Pages)"
done
