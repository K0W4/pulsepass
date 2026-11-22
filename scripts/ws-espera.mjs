// Ouvinte de WebSocket para os scripts de verificação (Node 22 já traz o WebSocket nativo).
// Uso: node scripts/ws-espera.mjs URL TEXTO [SEGUNDOS]
//   imprime "conectado" ao abrir; sai com 0 ao receber uma mensagem que contenha TEXTO;
//   TEXTO = @conectar: sai com 0 assim que a conexão abre;
//   TEXTO = @fechado:  sai com 0 se a conexão for recusada ou fechada pelo servidor antes do tempo acabar
//                      (o gateway aceita o handshake e só então consulta o serviço, então a recusa
//                      aparece como abrir e fechar logo em seguida), e com 1 se ela ficar aberta.
// Códigos: 1 = tempo esgotado, 2 = o socket falhou (nos demais modos).
const [url, texto, segundos = '30'] = process.argv.slice(2);
if (!url || texto === undefined) {
  console.error('uso: node ws-espera.mjs URL TEXTO [SEGUNDOS]');
  process.exit(64);
}
const limite = setTimeout(() => {
  console.error(texto === '@fechado' ? 'a conexão ficou aberta' : `tempo esgotado esperando "${texto}"`);
  process.exit(1);
}, Number(segundos) * 1000);

const ws = new WebSocket(url);
ws.addEventListener('open', () => {
  console.log('conectado');
  if (texto === '@conectar') {
    clearTimeout(limite);
    ws.close();
    process.exit(0);
  }
});
ws.addEventListener('message', (e) => {
  const mensagem = String(e.data);
  if (mensagem.includes(texto)) {
    console.log(mensagem);
    clearTimeout(limite);
    ws.close();
    process.exit(0);
  }
});
ws.addEventListener('close', () => {
  if (texto === '@fechado') {
    console.log('fechado pelo servidor');
    process.exit(0);
  }
});
ws.addEventListener('error', () => {
  if (texto === '@fechado') {
    console.log('recusado');
    process.exit(0);
  }
  console.error('o WebSocket falhou (recusado ou fechado)');
  process.exit(2);
});
