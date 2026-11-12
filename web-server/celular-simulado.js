// Celular simulado: responde às mesmas rotas da API do app br.unifor.cct
// (NfcRestApi.java), com dados de exemplo. Serve para demonstrar o servidor web
// sem um Android por perto.
//
//   node celular-simulado.js            (ou: npm run celular)
//
// Variáveis de ambiente (opcionais):
//   PORTA       porta da API simulada        (padrão 8787, a mesma do app)
//   HOST        interface de escuta          (padrão 127.0.0.1)
//   TOKEN_PATH  arquivo com o token          (padrão ../appnfc-test-token.txt)
//   ATRASO_MS   tempo "esperando" a leitura  (padrão 2000)
//
// Diferenças do app real: o QR Code devolve uma leitura de exemplo (no app ainda
// é 501, porque a Fase 2 não foi integrada), e NFC/digital "leem" sozinhos depois
// de ATRASO_MS, sem tag nem dedo.

'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORTA = Number(process.env.PORTA || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const TOKEN_PATH = process.env.TOKEN_PATH || path.join(__dirname, '..', 'appnfc-test-token.txt');
const ATRASO_MS = Number(process.env.ATRASO_MS || 2000);
const VERSAO = '1.0-simulado';
const TIMEOUT_MAXIMO_S = 35;

const PATRIMONIOS = ['PAT-001', 'PAT-002', 'PAT-003'];
const PROFESSORES = ['professor.teste@unifor.br', 'ana.souza@unifor.br', 'carlos.lima@unifor.br'];

const auditoria = [];
let proximoId = 1;
let leituraNfc = false;
let capturando = false;
let contador = 0;

function sortear(lista) {
  return lista[Math.floor(Math.random() * lista.length)];
}

function tagIdAleatorio() {
  return crypto.randomBytes(7).toString('hex').toUpperCase();
}

function lerToken() {
  try {
    return fs.readFileSync(TOKEN_PATH, 'utf8').replace(/\s+/g, '');
  } catch {
    return '';
  }
}

function autorizado(req) {
  const esperado = lerToken();
  const header = req.headers.authorization || '';
  if (!esperado || !/^bearer /i.test(header)) return false;
  const recebido = Buffer.from(header.slice(7).trim());
  const certo = Buffer.from(esperado);
  return recebido.length === certo.length && crypto.timingSafeEqual(recebido, certo);
}

function falha(status, mensagem) {
  return [status, { success: false, message: mensagem }];
}

const esperar = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Mesmo comportamento do timeoutSeconds do app: corpo vazio ou {} usa o padrão.
function validarCorpo(corpo) {
  if (!corpo.trim()) return null;
  let pedido;
  try { pedido = JSON.parse(corpo); } catch { return falha(400, 'Corpo precisa ser um objeto JSON (ex.: {}).'); }
  if (pedido === null || typeof pedido !== 'object' || Array.isArray(pedido)) {
    return falha(400, 'Corpo precisa ser um objeto JSON (ex.: {}).');
  }
  if ('timeoutSeconds' in pedido) {
    const t = pedido.timeoutSeconds;
    if (!Number.isInteger(t) || t < 1 || t > TIMEOUT_MAXIMO_S) {
      return falha(400, `timeoutSeconds deve ser inteiro de 1 a ${TIMEOUT_MAXIMO_S}.`);
    }
  }
  return null;
}

async function health() {
  return [200, {
    status: 'ok',
    service: 'br.unifor.cct NFC API',
    version: VERSAO,
    time: new Date().toISOString(),
    nfcAvailable: true,
    nfcEnabled: true,
    nfcScreenOpen: true,
    writing: false,
    readPending: leituraNfc,
    biometricAvailable: true,
    biometricMode: 'simulado',
    biometricPending: capturando,
  }];
}

async function lerNfc(corpo, extra) {
  const erro = validarCorpo(corpo);
  if (erro) return erro;
  if (leituraNfc) return falha(409, 'Já existe uma leitura esperando a tag.');
  leituraNfc = true;
  try {
    await esperar(ATRASO_MS);
  } finally {
    leituraNfc = false;
  }
  // A cada 4 leituras, uma tag sem patrimônio cadastrado.
  contador++;
  const valida = contador % 4 !== 0;
  const patrimonio = valida ? sortear(PATRIMONIOS) : null;
  extra.patrimonio = patrimonio;
  return [200, {
    success: true,
    valid: valida,
    patrimony: patrimonio,
    message: valida ? `Projetor ${patrimonio} identificado.` : 'Tag lida, mas não tem um patrimônio de projetor.',
    tagId: tagIdAleatorio(),
    techList: ['NfcA', 'MifareUltralight', 'Ndef'],
    texts: valida ? [patrimonio] : ['etiqueta sem cadastro'],
    urls: [],
    readAt: new Date().toISOString(),
  }];
}

async function lerQr(corpo) {
  const erro = validarCorpo(corpo);
  if (erro) return erro;
  await esperar(ATRASO_MS);
  const patrimonio = sortear(PATRIMONIOS);
  return [200, {
    success: true,
    text: patrimonio,
    format: 'QR_CODE',
    message: `QR Code lido: ${patrimonio} (simulado).`,
    readAt: new Date().toISOString(),
  }];
}

async function identificarDigital(corpo) {
  const erro = validarCorpo(corpo);
  if (erro) return erro;
  if (capturando) return falha(409, 'Já existe uma captura de digital em andamento.');
  capturando = true;
  try {
    await esperar(ATRASO_MS);
  } finally {
    capturando = false;
  }
  // 1 em cada 4 digitais não é reconhecida.
  const identificado = Math.random() >= 0.25;
  return [200, {
    success: true,
    identified: identificado,
    email: identificado ? sortear(PROFESSORES) : null,
    message: identificado ? 'Professor identificado com sucesso.' : 'Digital não cadastrada.',
    mode: 'simulado',
    readAt: new Date().toISOString(),
  }];
}

async function logs(url) {
  const inteiro = (texto, padrao) => (texto === null || texto === '' ? padrao : (/^-?\d+$/.test(texto) ? Number(texto) : NaN));
  const limit = inteiro(url.searchParams.get('limit'), 20);
  const offset = inteiro(url.searchParams.get('offset'), 0);
  if (!(limit >= 1 && limit <= 50)) return falha(400, 'limit deve ser inteiro de 1 a 50.');
  if (!(offset >= 0 && offset <= 10000)) return falha(400, 'offset deve ser inteiro de 0 a 10000.');
  // Mais recentes primeiro.
  const itens = auditoria.slice().reverse().slice(offset, offset + limit);
  return [200, { success: true, total: auditoria.length, limit, offset, items: itens }];
}

const ROTAS = {
  '/health': { metodo: 'GET', tratar: health },
  '/v1/nfc/read': { metodo: 'POST', tratar: (ctx) => lerNfc(ctx.corpo, ctx.extra) },
  '/v1/qr/read': { metodo: 'POST', tratar: (ctx) => lerQr(ctx.corpo) },
  '/v1/biometric/identify': { metodo: 'POST', tratar: (ctx) => identificarDigital(ctx.corpo) },
  '/v1/audit/logs': { metodo: 'GET', tratar: (ctx) => logs(ctx.url) },
};

async function rotear(req, url, corpo, extra) {
  if (!autorizado(req)) return falha(401, 'Token ausente ou inválido. Use Authorization: Bearer <token>.');
  const rota = ROTAS[url.pathname];
  if (!rota) return falha(404, `Rota não encontrada: ${url.pathname}`);
  if (req.method !== rota.metodo) return falha(405, `Use ${rota.metodo} nesta rota.`);
  return rota.tratar({ url, corpo, extra });
}

function lerCorpo(req) {
  return new Promise((resolve) => {
    let dados = '';
    req.on('data', (parte) => { if (dados.length < 10_000) dados += parte; });
    req.on('end', () => resolve(dados));
  });
}

const servidor = http.createServer(async (req, res) => {
  const inicio = Date.now();
  const url = new URL(req.url, 'http://localhost');
  const recebido = (req.headers['x-correlation-id'] || '').trim();
  const correlationId = /^[A-Za-z0-9._:-]{1,100}$/.test(recebido) ? recebido : crypto.randomUUID();
  const extra = { patrimonio: null };

  let status;
  let json;
  try {
    [status, json] = await rotear(req, url, await lerCorpo(req), extra);
  } catch (erro) {
    [status, json] = falha(500, erro.message);
  }
  json.correlationId = correlationId;

  const duracaoMs = Date.now() - inicio;
  auditoria.push({
    id: proximoId++,
    timestamp: new Date().toISOString(),
    clientIp: req.socket.remoteAddress,
    method: req.method,
    path: url.pathname,
    status,
    correlationId,
    durationMs: duracaoMs,
    patrimony: extra.patrimonio,
  });
  console.log(`${req.method} ${url.pathname} -> ${status} (${duracaoMs} ms)`);

  const headers = { 'Content-Type': 'application/json; charset=utf-8', 'X-Correlation-Id': correlationId };
  if (status === 401) headers['WWW-Authenticate'] = 'Bearer';
  res.writeHead(status, headers);
  res.end(JSON.stringify(json));
});

if (!lerToken()) {
  console.error(`Aviso: token não encontrado em ${TOKEN_PATH}; todas as chamadas vão dar 401.`);
}

servidor.listen(PORTA, HOST, () => {
  console.log(`Celular simulado em http://${HOST}:${PORTA} (token em ${TOKEN_PATH}, atraso ${ATRASO_MS} ms)`);
});
