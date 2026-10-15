// Testes do Code.gs sem precisar do Google: carrega o script num contexto
// isolado do Node, com SpreadsheetApp, PropertiesService etc. falsos e uma
// planilha em memória.
//
// Rodar: node apps-script/teste/teste.js

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const TOKEN = 'token-de-teste';

function novaPlanilha(abas) {
  const sheets = {};
  for (const [nome, linhas] of Object.entries(abas)) {
    const dados = linhas.map((l) => l.slice());
    sheets[nome] = {
      dados,
      getDataRange: () => ({ getValues: () => dados.map((l) => l.slice()) }),
      appendRow: (linha) => { dados.push(Array.from(linha)); },
      getRange: (linha, coluna) => ({
        setValue: (v) => {
          while (dados.length < linha) dados.push([]);
          dados[linha - 1][coluna - 1] = v;
        },
      }),
    };
  }
  return {
    getSheetByName: (nome) => sheets[nome] || null,
    getSpreadsheetTimeZone: () => 'America/Fortaleza',
    sheets,
  };
}

function carregar(abas, opcoes = {}) {
  const ss = novaPlanilha(abas);
  let lockLivre = opcoes.lockLivre !== false;
  const contexto = {
    // Mesmo Date dos testes, para instanceof funcionar dos dois lados.
    Date,
    console: { log() {}, error() {} },
    SpreadsheetApp: { getActiveSpreadsheet: () => ss },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (k) => (k === 'TOKEN' ? opcoes.token ?? TOKEN : (opcoes.propriedades || {})[k] ?? null),
      }),
    },
    LockService: {
      getScriptLock: () => ({ tryLock: () => lockLivre, releaseLock() {} }),
    },
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput: (t) => ({ texto: t, setMimeType() { return this; } }),
    },
    UrlFetchApp: { fetch: (url, o) => opcoes.fetch(url, o) },
    Utilities: {
      getUuid: () => 'uuid-de-teste',
      // Basta para os testes: a planilha falsa usa datas em UTC-3 fixo.
      formatDate: (d, _fuso, formato) => {
        const local = new Date(d.getTime() - 3 * 3600 * 1000);
        const dois = (n) => String(n).padStart(2, '0');
        if (formato === 'yyyy-MM-dd') {
          return `${local.getUTCFullYear()}-${dois(local.getUTCMonth() + 1)}-${dois(local.getUTCDate())}`;
        }
        return `${dois(local.getUTCHours())}:${dois(local.getUTCMinutes())}`;
      },
    },
  };
  vm.createContext(contexto);
  for (const arquivo of ['Code.gs', 'TesteNfc.gs']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '..', arquivo), 'utf8'), contexto);
  }
  return {
    ss,
    // Passa a resposta por JSON, como acontece no fio HTTP.
    pedir: (corpo) => JSON.parse(JSON.stringify(
        contexto.tratarPedido(typeof corpo === 'string' ? corpo : JSON.stringify(corpo)))),
    doPost: (e) => JSON.parse(contexto.doPost(e).texto),
    contexto,
  };
}

function abasPadrao() {
  return {
    professores: [
      ['id', 'nome', 'matricula', 'ativo'],
      [1, 'Ana Souza', '12345', true],
      [2, 'Bruno Lima', '', false],
      [3, 'Carla Dias', '777', 'VERDADEIRO'],
    ],
    projetores: [
      ['codigo', 'descricao', 'ativo'],
      ['PAT-001', 'Epson sala 101', true],
      ['PAT-002', 'BenQ', false],
      ['PAT-003', 'LG', true],
    ],
    reservas: [
      ['id', 'professorId', 'projetorId', 'data', 'inicio', 'fim', 'status', 'criadaEm', 'canceladaEm'],
    ],
    config: [
      ['chave', 'valor'],
      ['proximoReservaId', 1000],
    ],
  };
}

function criar(app, extra = {}) {
  return app.pedir(Object.assign({
    acao: 'criarReserva', token: TOKEN, professorId: 1, projetorId: 'PAT-001',
    data: '2026-09-22', inicio: '19:00', fim: '21:00',
  }, extra));
}

const testes = {
  'cria reserva e grava na aba reservas'() {
    const app = carregar(abasPadrao());
    const r = criar(app);
    assert.deepStrictEqual([r.sucesso, r.reservaId], [true, 1000]);
    const linha = app.ss.sheets.reservas.dados[1];
    assert.deepStrictEqual(linha.slice(0, 7), [1000, 1, 'PAT-001', '2026-09-22', '19:00', '21:00', 'ATIVA']);
    assert.ok(linha[7] instanceof Date);
    assert.strictEqual(app.ss.sheets.config.dados[1][1], 1001);
  },

  'ids seguem em sequência'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(criar(app).reservaId, 1000);
    assert.strictEqual(criar(app, { inicio: '21:00', fim: '22:00' }).reservaId, 1001);
  },

  'contador apagado ou atrasado não repete id'() {
    const abas = abasPadrao();
    abas.config = [['chave', 'valor']];
    abas.reservas.push([1005, 1, 'PAT-003', '2026-09-22', '08:00', '10:00', 'CANCELADA', '', '']);
    const app = carregar(abas);
    assert.strictEqual(criar(app).reservaId, 1006);
    assert.deepStrictEqual(app.ss.sheets.config.dados[1], ['proximoReservaId', 1007]);
  },

  'sobreposição no mesmo projetor e dia é recusada'() {
    const app = carregar(abasPadrao());
    criar(app);
    const r = criar(app, { professorId: 3, inicio: '20:00', fim: '22:00' });
    assert.strictEqual(r.sucesso, false);
    assert.strictEqual(r.mensagem, 'Projetor PAT-001 já reservado das 19:00 às 21:00 nesse dia.');
  },

  'horários encostados, outro projetor ou outro dia não conflitam'() {
    const app = carregar(abasPadrao());
    criar(app);
    assert.ok(criar(app, { inicio: '21:00', fim: '22:00' }).sucesso);
    assert.ok(criar(app, { projetorId: 'PAT-003' }).sucesso);
    assert.ok(criar(app, { data: '2026-09-23' }).sucesso);
  },

  'reserva cancelada libera o horário'() {
    const app = carregar(abasPadrao());
    const id = criar(app).reservaId;
    assert.ok(app.pedir({ acao: 'cancelarReserva', token: TOKEN, reservaId: id }).sucesso);
    assert.ok(criar(app, { professorId: 3 }).sucesso);
  },

  'datas e horas convertidas pelo Sheets ainda contam no conflito'() {
    const abas = abasPadrao();
    // 22/09/2026 00:00 e 19:00/21:00 em UTC-3, como o Sheets guardaria.
    abas.reservas.push([999, 1, 'PAT-001', new Date('2026-09-22T03:00:00Z'),
      new Date('1899-12-30T22:00:00Z'), new Date('1899-12-31T00:00:00Z'), 'ATIVA', '', '']);
    const app = carregar(abas);
    assert.strictEqual(criar(app, { inicio: '20:00', fim: '20:30' }).sucesso, false);
  },

  'valida professor'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(criar(app, { professorId: 99 }).mensagem, 'Professor não cadastrado.');
    assert.strictEqual(criar(app, { professorId: 2 }).mensagem, 'Professor inativo. Procure a coordenação.');
    assert.strictEqual(criar(app, { professorId: 'abc' }).mensagem, 'Professor inválido.');
    assert.ok(criar(app, { professorId: 3 }).sucesso, 'VERDADEIRO digitado à mão conta como ativo');
  },

  'valida projetor'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(criar(app, { projetorId: 'PAT-999' }).mensagem, 'Projetor PAT-999 não cadastrado.');
    assert.strictEqual(criar(app, { projetorId: 'PAT-002' }).mensagem, 'Projetor PAT-002 indisponível (inativo).');
    assert.strictEqual(criar(app, { projetorId: 'sala 3' }).mensagem, 'Código de projetor inválido.');
  },

  'valida data e horário'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(criar(app, { data: '22/09/2026' }).sucesso, false);
    assert.strictEqual(criar(app, { data: '2026-02-30' }).sucesso, false);
    assert.strictEqual(criar(app, { inicio: '7:00' }).sucesso, false);
    assert.strictEqual(criar(app, { fim: '24:00' }).sucesso, false);
    assert.strictEqual(criar(app, { inicio: '21:00', fim: '19:00' }).mensagem,
      'O horário de fim deve ser depois do início.');
  },

  'cancelar e consultar'() {
    const app = carregar(abasPadrao());
    const id = criar(app).reservaId;

    const consulta = app.pedir({ acao: 'consultarReserva', token: TOKEN, reservaId: id });
    assert.strictEqual(consulta.mensagem, 'Reserva 1000: PAT-001 em 22/09/2026, das 19:00 às 21:00 (ATIVA).');

    const cancel = app.pedir({ acao: 'cancelarReserva', token: TOKEN, reservaId: id });
    assert.deepStrictEqual([cancel.sucesso, cancel.reservaId], [true, 1000]);
    assert.strictEqual(app.ss.sheets.reservas.dados[1][6], 'CANCELADA');
    assert.ok(app.ss.sheets.reservas.dados[1][8] instanceof Date);

    const deNovo = app.pedir({ acao: 'cancelarReserva', token: TOKEN, reservaId: id });
    assert.strictEqual(deNovo.mensagem, 'A reserva 1000 não está ativa (CANCELADA).');

    const inexistente = app.pedir({ acao: 'consultarReserva', token: TOKEN, reservaId: 5 });
    assert.strictEqual(inexistente.mensagem, 'Reserva não encontrada.');
  },

  'listar professores'() {
    const app = carregar(abasPadrao());
    const r = app.pedir({ acao: 'listarProfessores', token: TOKEN });
    assert.strictEqual(r.sucesso, true);
    assert.deepStrictEqual(r.professores.map((p) => [p.id, p.ativo]), [[1, true], [2, false], [3, true]]);
    assert.strictEqual(r.professores[1].matricula, null);
  },

  'token errado, ausente ou não configurado é recusado'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(criar(app, { token: 'outro' }).mensagem, 'Terminal não autorizado.');
    assert.strictEqual(app.pedir({ acao: 'listarProfessores' }).mensagem, 'Terminal não autorizado.');
    const semToken = carregar(abasPadrao(), { token: null });
    assert.strictEqual(semToken.pedir({ acao: 'listarProfessores', token: null }).mensagem,
      'Terminal não autorizado.');
    assert.strictEqual(app.ss.sheets.reservas.dados.length, 1, 'nada foi gravado');
  },

  'corpo inválido e ação desconhecida'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(app.pedir('não é json').mensagem, 'Requisição inválida.');
    assert.strictEqual(app.pedir('null').mensagem, 'Requisição inválida.');
    assert.strictEqual(app.pedir({ acao: 'apagarTudo', token: TOKEN }).mensagem, 'Ação desconhecida: apagarTudo');
  },

  'lock ocupado devolve erro sem gravar'() {
    const app = carregar(abasPadrao(), { lockLivre: false });
    assert.strictEqual(criar(app).mensagem, 'Servidor ocupado. Tente novamente.');
    assert.strictEqual(app.ss.sheets.reservas.dados.length, 1);
  },

  'aba faltando vira erro interno, sem exceção'() {
    const abas = abasPadrao();
    delete abas.projetores;
    const app = carregar(abas);
    assert.strictEqual(criar(app).mensagem, 'Erro interno no servidor. Tente novamente.');
  },

  'doPost devolve o JSON no formato do AppsScriptClient'() {
    const app = carregar(abasPadrao());
    const r = app.doPost({ postData: { contents: JSON.stringify({
      acao: 'criarReserva', token: TOKEN, professorId: 1, projetorId: 'PAT-001',
      data: '2026-09-22', inicio: '19:00', fim: '21:00' }) } });
    assert.deepStrictEqual(Object.keys(r).sort(), ['mensagem', 'reservaId', 'sucesso']);
    assert.strictEqual(app.doPost({}).mensagem, 'Requisição inválida.');
  },

  'testarLeituraNfc chama a API do celular e registra na aba leituras_nfc'() {
    const abas = abasPadrao();
    abas.leituras_nfc = [['registradoEm']];
    let pedido;
    const app = carregar(abas, {
      propriedades: { NFC_API_URL: 'https://tunel.exemplo/', NFC_API_TOKEN: 'tok' },
      fetch: (url, o) => {
        pedido = { url, o };
        return {
          getResponseCode: () => 200,
          getContentText: () => JSON.stringify({
            success: true, valid: true, patrimony: 'PAT-001', tagId: '04A1',
            texts: ['PAT-001'], urls: [], message: 'Projetor PAT-001 identificado.',
            correlationId: 'uuid-de-teste',
          }),
        };
      },
    });
    const r = app.contexto.testarLeituraNfc();
    assert.strictEqual(r.status, 200);
    assert.strictEqual(pedido.url, 'https://tunel.exemplo/v1/nfc/read');
    assert.strictEqual(pedido.o.method, 'post');
    assert.strictEqual(pedido.o.headers.Authorization, 'Bearer tok');
    assert.strictEqual(pedido.o.muteHttpExceptions, true);
    const linha = app.ss.sheets.leituras_nfc.dados[1];
    assert.deepStrictEqual(linha.slice(1, 7), ['/v1/nfc/read', 200, 'PAT-001', true, '04A1', 'PAT-001']);
  },

  'erro HTTP da API também é registrado, sem exceção'() {
    const abas = abasPadrao();
    abas.leituras_nfc = [['registradoEm']];
    const app = carregar(abas, {
      propriedades: { NFC_API_URL: 'https://tunel.exemplo', NFC_API_TOKEN: 'tok' },
      fetch: () => ({
        getResponseCode: () => 408,
        getContentText: () => '{"success":false,"message":"Nenhuma tag foi encostada em 30 s."}',
      }),
    });
    const r = app.contexto.testarLeituraNfc();
    assert.strictEqual(r.status, 408);
    assert.strictEqual(app.ss.sheets.leituras_nfc.dados[1][8], 'Nenhuma tag foi encostada em 30 s.');
  },

  'sem NFC_API_URL avisa o que configurar'() {
    const app = carregar(abasPadrao());
    assert.throws(() => app.contexto.testarHealthNfc(), /NFC_API_URL/);
  },
};

let falhas = 0;
for (const [nome, teste] of Object.entries(testes)) {
  try {
    teste();
    console.log('ok    ' + nome);
  } catch (erro) {
    falhas++;
    console.log('FALHA ' + nome + '\n      ' + erro.message);
  }
}
console.log(`\n${Object.keys(testes).length - falhas} de ${Object.keys(testes).length} testes passaram.`);
process.exit(falhas ? 1 : 0);
