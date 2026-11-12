// Servidor web de demonstração: mostra os botões numa página e repassa cada
// clique para a API REST do celular (app br.unifor.cct, porta 8787).
// O navegador nunca fala com o celular nem vê o token: quem chama é este servidor.
//
// Sem dependências: só Node 18+ (usa http, fs e fetch nativos).
//
//   node server.js
//   CELULAR_IP=10.50.103.53 node server.js
//
// Variáveis de ambiente (todas opcionais):
//   CELULAR_IP     IP do celular             (padrão 10.50.103.53)
//   CELULAR_PORTA  porta da API no celular   (padrão 8787)
//   TOKEN_PATH     arquivo com o token       (padrão ../appnfc-test-token.txt)
//   PORTA          porta deste servidor      (padrão 3000)
//   HOST           interface de escuta       (padrão 127.0.0.1)

'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');

const PORTA = Number(process.env.PORTA || 3000);
const HOST = process.env.HOST || '127.0.0.1';
const TOKEN_PATH = process.env.TOKEN_PATH || path.join(__dirname, '..', 'appnfc-test-token.txt');
const PUBLIC_DIR = path.join(__dirname, 'public');

// Alvo atual (pode ser trocado pela página durante a demonstração).
const celular = {
  ip: process.env.CELULAR_IP || '10.50.103.53',
  porta: Number(process.env.CELULAR_PORTA || 8787),
};

// Cada opção da página vira uma chamada à API do celular.
const OPCOES = {
  health:    { metodo: 'GET',  caminho: '/health',                timeoutMs: 10_000 },
  nfc:       { metodo: 'POST', caminho: '/v1/nfc/read',           timeoutMs: 40_000 },
  qr:        { metodo: 'POST', caminho: '/v1/qr/read',            timeoutMs: 40_000 },
  biometria: { metodo: 'POST', caminho: '/v1/biometric/identify', timeoutMs: 40_000 },
  logs:      { metodo: 'GET',  caminho: '/v1/audit/logs',         timeoutMs: 10_000 },
};

function lerToken() {
  let token;
  try {
    token = fs.readFileSync(TOKEN_PATH, 'utf8').replace(/\s+/g, '');
  } catch {
    throw new Error(`arquivo de token não encontrado: ${TOKEN_PATH}`);
  }
  if (!token || token === 'COLE_O_TOKEN_AQUI') {
    throw new Error(`preencha o token em ${TOKEN_PATH}`);
  }
  return token;
}

async function chamarCelular(opcao, query) {
  const { metodo, caminho, timeoutMs } = OPCOES[opcao];
  const url = `http://${celular.ip}:${celular.porta}${caminho}${query}`;
  const correlationId = crypto.randomUUID();
  const inicio = Date.now();

  const resposta = await fetch(url, {
    method: metodo,
    headers: {
      Authorization: `Bearer ${lerToken()}`,
      'X-Correlation-Id': correlationId,
      ...(metodo === 'POST' ? { 'Content-Type': 'application/json' } : {}),
    },
    body: metodo === 'POST' ? '{}' : undefined,
    signal: AbortSignal.timeout(timeoutMs),
  });

  const texto = await resposta.text();
  let corpo;
  try { corpo = JSON.parse(texto); } catch { corpo = texto; }

  return {
    ok: resposta.ok,
    status: resposta.status,
    duracaoMs: Date.now() - inicio,
    requisicao: `${metodo} ${caminho}${query}`,
    alvo: `${celular.ip}:${celular.porta}`,
    correlationId,
    corpo,
  };
}

// ---- Procurar celular -------------------------------------------------------
// Testa GET /health na porta configurada em cada IP das redes locais deste PC
// (no máximo um /24 por interface) e em 127.0.0.1 (caso de adb forward).
// Qualquer resposta HTTP conta: 200 = token certo, 401 = é a API, mas o token
// do arquivo não confere.

function ipParaNumero(ip) {
  return ip.split('.').reduce((total, parte) => total * 256 + Number(parte), 0);
}

function numeroParaIp(n) {
  return [24, 16, 8, 0].map((desloc) => Math.floor(n / 2 ** desloc) % 256).join('.');
}

function candidatosDaRede() {
  const candidatos = new Set(['127.0.0.1']);
  const redes = [];
  for (const enderecos of Object.values(os.networkInterfaces())) {
    for (const end of enderecos || []) {
      if (end.family !== 'IPv4' || end.internal) continue;
      const prefixo = Math.max(24, Number(end.cidr.split('/')[1]));
      const tamanho = 2 ** (32 - prefixo);
      const rede = Math.floor(ipParaNumero(end.address) / tamanho) * tamanho;
      redes.push(`${numeroParaIp(rede)}/${prefixo}`);
      for (let n = rede + 1; n < rede + tamanho - 1; n++) {
        const ip = numeroParaIp(n);
        if (ip !== end.address) candidatos.add(ip);
      }
    }
  }
  return { candidatos: [...candidatos], redes };
}

async function testarHealth(ip, porta, token) {
  try {
    const resposta = await fetch(`http://${ip}:${porta}/health`, {
      headers: { Authorization: `Bearer ${token}`, 'X-Correlation-Id': crypto.randomUUID() },
      signal: AbortSignal.timeout(1500),
    });
    const texto = await resposta.text();
    let corpo;
    try { corpo = JSON.parse(texto); } catch { corpo = texto; }
    return { ip, porta, status: resposta.status, tokenOk: resposta.ok, corpo };
  } catch {
    return null;
  }
}

async function procurarCelular(porta) {
  const token = lerToken();
  const { candidatos, redes } = candidatosDaRede();
  const encontrados = [];
  const fila = [...candidatos];
  const inicio = Date.now();
  const trabalhadores = Array.from({ length: 64 }, async () => {
    while (fila.length) {
      const achado = await testarHealth(fila.shift(), porta, token);
      if (achado) encontrados.push(achado);
    }
  });
  await Promise.all(trabalhadores);
  encontrados.sort((a, b) => Number(b.tokenOk) - Number(a.tokenOk) || ipParaNumero(a.ip) - ipParaNumero(b.ip));
  return { porta, redes, testados: candidatos.length, duracaoMs: Date.now() - inicio, encontrados };
}

function explicarErro(erro) {
  if (erro.name === 'TimeoutError') return 'o celular não respondeu a tempo';
  const causa = erro.cause && erro.cause.code;
  if (causa === 'ECONNREFUSED') return 'conexão recusada: a API está ligada no celular?';
  if (causa === 'EHOSTUNREACH' || causa === 'ENETUNREACH') return 'celular inacessível: estão na mesma rede?';
  if (causa === 'ETIMEDOUT' || causa === 'UND_ERR_CONNECT_TIMEOUT') return 'tempo esgotado ao conectar no celular';
  return erro.cause && erro.cause.message ? `${erro.message}: ${erro.cause.message}` : erro.message;
}

function enviarJson(res, status, dados) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(dados));
}

function lerCorpo(req) {
  return new Promise((resolve, reject) => {
    let dados = '';
    req.on('data', (parte) => {
      dados += parte;
      if (dados.length > 10_000) reject(new Error('corpo grande demais'));
    });
    req.on('end', () => resolve(dados));
    req.on('error', reject);
  });
}

const TIPOS = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8' };

function servirArquivo(res, nome) {
  const arquivo = path.join(PUBLIC_DIR, nome);
  if (!arquivo.startsWith(PUBLIC_DIR + path.sep)) return enviarJson(res, 404, { erro: 'não encontrado' });
  fs.readFile(arquivo, (erro, conteudo) => {
    if (erro) return enviarJson(res, 404, { erro: 'não encontrado' });
    res.writeHead(200, { 'Content-Type': TIPOS[path.extname(arquivo)] || 'application/octet-stream' });
    res.end(conteudo);
  });
}

const servidor = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const rota = url.pathname;

  try {
    // Configuração do alvo (IP/porta do celular).
    if (rota === '/api/config' && req.method === 'GET') {
      return enviarJson(res, 200, { ...celular, opcoes: Object.keys(OPCOES) });
    }
    if (rota === '/api/config' && req.method === 'POST') {
      const { ip, porta } = JSON.parse((await lerCorpo(req)) || '{}');
      const ipValido = typeof ip === 'string' && /^[a-zA-Z0-9.-]{1,253}$/.test(ip);
      const portaValida = Number.isInteger(porta) && porta >= 1 && porta <= 65535;
      if (!ipValido || !portaValida) return enviarJson(res, 400, { erro: 'IP ou porta inválidos' });
      celular.ip = ip;
      celular.porta = porta;
      console.log(`Alvo alterado para ${ip}:${porta}`);
      return enviarJson(res, 200, celular);
    }

    // Procura o celular nas redes locais, na porta configurada.
    if (rota === '/api/procurar' && req.method === 'POST') {
      const resultado = await procurarCelular(celular.porta);
      console.log(`Procura em ${resultado.redes.join(', ') || 'só 127.0.0.1'}: ` +
        `${resultado.encontrados.length} encontrado(s) em ${resultado.duracaoMs} ms`);
      return enviarJson(res, 200, resultado);
    }

    // Opções: /api/health, /api/nfc, /api/qr, /api/biometria, /api/logs
    const opcao = rota.startsWith('/api/') ? rota.slice(5) : null;
    if (opcao && Object.hasOwn(OPCOES, opcao)) {
      if (req.method !== 'POST') return enviarJson(res, 405, { erro: 'use POST' });

      let query = '';
      if (opcao === 'logs') {
        const limit = Number(url.searchParams.get('limit') || 20);
        const offset = Number(url.searchParams.get('offset') || 0);
        if (!Number.isInteger(limit) || limit < 1 || limit > 50 || !Number.isInteger(offset) || offset < 0 || offset > 10000) {
          return enviarJson(res, 400, { erro: 'limit deve ficar entre 1 e 50 e offset entre 0 e 10000' });
        }
        query = `?limit=${limit}&offset=${offset}`;
      }

      try {
        const resultado = await chamarCelular(opcao, query);
        console.log(`${resultado.requisicao} -> HTTP ${resultado.status} (${resultado.duracaoMs} ms)`);
        return enviarJson(res, 200, resultado);
      } catch (erro) {
        const mensagem = explicarErro(erro);
        console.log(`${OPCOES[opcao].caminho} -> falhou: ${mensagem}`);
        return enviarJson(res, 502, { ok: false, alvo: `${celular.ip}:${celular.porta}`, erro: mensagem });
      }
    }

    if (req.method === 'GET') {
      return servirArquivo(res, rota === '/' ? 'index.html' : rota.slice(1));
    }
    return enviarJson(res, 404, { erro: 'não encontrado' });
  } catch (erro) {
    return enviarJson(res, 500, { erro: erro.message });
  }
});

servidor.listen(PORTA, HOST, () => {
  console.log(`Servidor web em http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORTA}`);
  console.log(`Celular alvo: http://${celular.ip}:${celular.porta}  (token em ${TOKEN_PATH})`);
});
