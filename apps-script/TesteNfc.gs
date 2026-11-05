/**
 * Chamada de teste da API REST do NFC e da biometria do terminal Android
 * (ServicoApiNfc).
 *
 * O Apps Script roda nos servidores do Google e não alcança o IP local do
 * celular: a API precisa estar atrás de uma URL pública HTTPS (túnel, ver
 * docs/nfc/README.md). Configure nas Propriedades do script:
 *
 *   NFC_API_URL    ex.: https://xxxx.trycloudflare.com   (sem barra no fim)
 *   NFC_API_TOKEN  o token mostrado na tela de NFC do celular
 *
 * Rode testarHealthNfc() para conferir a conexão e testarLeituraNfc() para
 * pedir uma leitura: encoste a tag no celular em até 30 s. Cada resposta
 * vai para o Registro de execução e para a aba "leituras_nfc".
 *
 * testarBiometria() pede uma captura de digital (por enquanto o leitor do
 * celular é simulado e sempre identifica o professor de teste) e registra
 * a resposta na aba "leituras_biometria".
 */

var ABA_LEITURAS_NFC = 'leituras_nfc';
var CABECALHO_LEITURAS_NFC = ['registradoEm', 'rota', 'status', 'patrimonio', 'valido', 'tagId',
  'textos', 'urls', 'mensagem', 'correlationId'];

function testarHealthNfc() {
  var r = chamarApiNfc('get', '/health', null);
  registrarRespostaNfc('/health', r);
  return r;
}

function testarLeituraNfc() {
  var r = chamarApiNfc('post', '/v1/nfc/read', { timeoutSeconds: 30 });
  registrarRespostaNfc('/v1/nfc/read', r);
  return r;
}

var ABA_LEITURAS_BIOMETRIA = 'leituras_biometria';
var CABECALHO_LEITURAS_BIOMETRIA = ['registradoEm', 'rota', 'status', 'identificado', 'email',
  'modo', 'mensagem', 'correlationId'];

function testarBiometria() {
  var r = chamarApiNfc('post', '/v1/biometric/identify', { timeoutSeconds: 30 });
  var j = r.json || {};
  console.log('/v1/biometric/identify -> HTTP ' + r.status + ' ' + JSON.stringify(j));
  abaComCabecalho(ABA_LEITURAS_BIOMETRIA, CABECALHO_LEITURAS_BIOMETRIA).appendRow([
    new Date(),
    '/v1/biometric/identify',
    r.status,
    j.identified === undefined ? '' : j.identified,
    j.email || '',
    j.mode || '',
    j.message || '',
    j.correlationId || ''
  ]);
  return r;
}

/** @return { status, json } — nunca lança exceção por status HTTP de erro. */
function chamarApiNfc(metodo, caminho, corpo) {
  var props = PropertiesService.getScriptProperties();
  var url = props.getProperty('NFC_API_URL');
  var token = props.getProperty('NFC_API_TOKEN');
  if (!url || !token) {
    throw new Error('Defina NFC_API_URL e NFC_API_TOKEN nas Propriedades do script.');
  }
  var opcoes = {
    method: metodo,
    headers: {
      Authorization: 'Bearer ' + token,
      'X-Correlation-Id': Utilities.getUuid()
    },
    muteHttpExceptions: true
  };
  if (corpo !== null) {
    opcoes.contentType = 'application/json';
    opcoes.payload = JSON.stringify(corpo);
  }
  var resposta = UrlFetchApp.fetch(url.replace(/\/+$/, '') + caminho, opcoes);
  var json;
  try {
    json = JSON.parse(resposta.getContentText());
  } catch (erro) {
    json = { success: false, message: 'Resposta não é JSON: ' + resposta.getContentText().slice(0, 200) };
  }
  return { status: resposta.getResponseCode(), json: json };
}

function registrarRespostaNfc(rota, r) {
  console.log(rota + ' -> HTTP ' + r.status + ' ' + JSON.stringify(r.json));
  var j = r.json || {};
  abaComCabecalho(ABA_LEITURAS_NFC, CABECALHO_LEITURAS_NFC).appendRow([
    new Date(),
    rota,
    r.status,
    j.patrimony || '',
    j.valid === undefined ? '' : j.valid,
    j.tagId || '',
    (j.texts || []).join(' | '),
    (j.urls || []).join(' | '),
    j.message || j.status || '',
    j.correlationId || ''
  ]);
}

/** Abre a aba; se não existir, cria com o cabeçalho. */
function abaComCabecalho(nome, cabecalho) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var aba = ss.getSheetByName(nome);
  if (!aba) {
    aba = ss.insertSheet(nome);
    aba.appendRow(cabecalho);
  }
  return aba;
}
