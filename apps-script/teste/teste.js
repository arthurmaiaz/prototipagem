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
// Relógio fixo dos testes: as reservas de exemplo (22/09/2026) ficam no futuro.
const AGORA_PADRAO = '2026-09-20T12:00:00-03:00';

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
  const relogio = { agora: new Date(opcoes.agora || AGORA_PADRAO) };
  contexto.agora = () => new Date(relogio.agora.getTime());
  return {
    ss,
    // Passa a resposta por JSON, como acontece no fio HTTP.
    pedir: (corpo) => JSON.parse(JSON.stringify(
        contexto.tratarPedido(typeof corpo === 'string' ? corpo : JSON.stringify(corpo)))),
    doPost: (e) => JSON.parse(contexto.doPost(e).texto),
    acertarRelogio: (iso) => { relogio.agora = new Date(iso); },
    contexto,
  };
}

function abasPadrao() {
  return {
    professores: [
      ['email', 'nome', 'matricula', 'ativo'],
      ['ana@unifor.br', 'Ana Souza', '12345', true],
      ['bruno@unifor.br', 'Bruno Lima', '', false],
      [' Carla@Unifor.br ', 'Carla Dias', '777', 'VERDADEIRO'],
      [7, 'Sem E-mail', '', true],
    ],
    projetores: [
      ['codigo', 'descricao', 'ativo'],
      ['PAT-001', 'Epson sala 101', true],
      ['PAT-002', 'BenQ', false],
      ['PAT-003', 'LG', true],
    ],
    reservas: [
      ['id', 'professorEmail', 'projetorId', 'data', 'inicio', 'fim', 'status', 'criadaEm', 'canceladaEm', 'devolvidaEm'],
    ],
    config: [
      ['chave', 'valor'],
      ['proximoReservaId', 1000],
    ],
  };
}

function criar(app, extra = {}) {
  return app.pedir(Object.assign({
    acao: 'criarReserva', token: TOKEN, professorEmail: 'ana@unifor.br', projetorId: 'PAT-001',
    data: '2026-09-22', inicio: '19:00', fim: '21:00',
  }, extra));
}

const testes = {
  'cria reserva e grava na aba reservas'() {
    const app = carregar(abasPadrao());
    const r = criar(app);
    assert.deepStrictEqual([r.sucesso, r.reservaId], [true, 1000]);
    const linha = app.ss.sheets.reservas.dados[1];
    assert.deepStrictEqual(linha.slice(0, 7), [1000, 'ana@unifor.br', 'PAT-001', '2026-09-22', '19:00', '21:00', 'ATIVA']);
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
    abas.reservas.push([1005, 'ana@unifor.br', 'PAT-003', '2026-09-22', '08:00', '10:00', 'CANCELADA', '', '']);
    const app = carregar(abas);
    assert.strictEqual(criar(app).reservaId, 1006);
    assert.deepStrictEqual(app.ss.sheets.config.dados[1], ['proximoReservaId', 1007]);
  },

  'sobreposição no mesmo projetor e dia é recusada'() {
    const app = carregar(abasPadrao());
    criar(app);
    const r = criar(app, { professorEmail: 'carla@unifor.br', inicio: '20:00', fim: '22:00' });
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
    assert.ok(criar(app, { professorEmail: 'carla@unifor.br' }).sucesso);
  },

  'datas e horas convertidas pelo Sheets ainda contam no conflito'() {
    const abas = abasPadrao();
    // 22/09/2026 00:00 e 19:00/21:00 em UTC-3, como o Sheets guardaria.
    abas.reservas.push([999, 'ana@unifor.br', 'PAT-001', new Date('2026-09-22T03:00:00Z'),
      new Date('1899-12-30T22:00:00Z'), new Date('1899-12-31T00:00:00Z'), 'ATIVA', '', '']);
    const app = carregar(abas);
    assert.strictEqual(criar(app, { inicio: '20:00', fim: '20:30' }).sucesso, false);
  },

  'valida professor'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(criar(app, { professorEmail: 'zeca@unifor.br' }).mensagem, 'Professor não cadastrado.');
    assert.strictEqual(criar(app, { professorEmail: 'bruno@unifor.br' }).mensagem,
      'Professor inativo. Procure a coordenação.');
    assert.strictEqual(criar(app, { professorEmail: 'abc' }).mensagem, 'E-mail do professor inválido.');
    assert.strictEqual(criar(app, { professorEmail: 7 }).mensagem, 'E-mail do professor inválido.');
    assert.ok(criar(app, { professorEmail: ' CARLA@unifor.BR' }).sucesso,
      'e-mail sem diferença de maiúsculas, e VERDADEIRO digitado à mão conta como ativo');
    assert.strictEqual(app.ss.sheets.reservas.dados[1][1], 'carla@unifor.br', 'grava o e-mail normalizado');
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

  'data passada ou horário que já terminou é recusado'() {
    const app = carregar(abasPadrao());
    const passou = 'Esse horário já passou. Escolha um horário futuro.';
    assert.strictEqual(criar(app, { data: '2026-09-19' }).mensagem, passou);
    // Agora são 12:00 de 20/09.
    assert.strictEqual(criar(app, { data: '2026-09-20', inicio: '10:00', fim: '12:00' }).mensagem, passou);
    assert.ok(criar(app, { data: '2026-09-20', inicio: '11:00', fim: '12:30' }).sucesso,
      'reserva que já começou mas ainda não terminou é aceita');
  },

  'duração máxima vem da aba config'() {
    const app = carregar(abasPadrao());
    assert.strictEqual(criar(app, { inicio: '08:00', fim: '12:01' }).mensagem,
      'A reserva pode durar no máximo 4 horas.');
    assert.ok(criar(app, { inicio: '08:00', fim: '12:00' }).sucesso);

    const abas = abasPadrao();
    abas.config.push(['duracaoMaximaMinutos', 90]);
    const curta = carregar(abas);
    assert.strictEqual(criar(curta, { inicio: '19:00', fim: '20:31' }).mensagem,
      'A reserva pode durar no máximo 1h30.');

    const invalida = abasPadrao();
    invalida.config.push(['duracaoMaximaMinutos', 'muito']);
    assert.ok(criar(carregar(invalida), { inicio: '08:00', fim: '12:00' }).sucesso, 'valor inválido usa o padrão');
  },

  'devolução finaliza a reserva e libera o horário'() {
    const app = carregar(abasPadrao());
    const id = criar(app).reservaId;
    const devolver = () => app.pedir({ acao: 'registrarDevolucao', token: TOKEN, reservaId: id });

    assert.strictEqual(devolver().mensagem,
      'A reserva 1000 ainda não começou. Para desistir, cancele a reserva.');

    app.acertarRelogio('2026-09-22T20:00:00-03:00');
    const r = devolver();
    assert.deepStrictEqual([r.sucesso, r.reservaId, r.mensagem], [true, 1000, 'Devolução registrada.']);
    const linha = app.ss.sheets.reservas.dados[1];
    assert.strictEqual(linha[6], 'FINALIZADA');
    assert.ok(linha[9] instanceof Date);

    assert.ok(criar(app, { professorEmail: 'carla@unifor.br', inicio: '20:00', fim: '21:00' }).sucesso);
    assert.strictEqual(devolver().mensagem, 'A reserva 1000 não está ativa (FINALIZADA).');
    assert.strictEqual(app.pedir({ acao: 'consultarReserva', token: TOKEN, reservaId: id }).mensagem,
      'Reserva 1000: PAT-001 em 22/09/2026, das 19:00 às 21:00 (FINALIZADA).');
    assert.strictEqual(app.pedir({ acao: 'registrarDevolucao', token: TOKEN, reservaId: 7 }).mensagem,
      'Reserva não encontrada.');
  },

  'devolução pelo código do projetor acha a reserva em uso'() {
    const app = carregar(abasPadrao());
    criar(app, { data: '2026-09-21', inicio: '08:00', fim: '10:00' });
    criar(app, { inicio: '19:00', fim: '21:00' });
    criar(app, { inicio: '21:00', fim: '22:00' });
    criar(app, { projetorId: 'PAT-003', inicio: '19:00', fim: '21:00' });
    const devolver = (projetorId) => app.pedir({ acao: 'registrarDevolucao', token: TOKEN, projetorId });

    // 20/09 12:00: nenhuma reserva do PAT-001 começou ainda.
    assert.strictEqual(devolver('PAT-001').mensagem,
      'O projetor PAT-001 não tem reserva em uso para devolver.');

    // 22/09 19:30: a de 21/09 (atrasada) e a de 19:00 já começaram; vale a mais recente.
    app.acertarRelogio('2026-09-22T19:30:00-03:00');
    const r = devolver('PAT-001');
    assert.deepStrictEqual([r.sucesso, r.reservaId], [true, 1001]);
    assert.strictEqual(app.ss.sheets.reservas.dados[2][6], 'FINALIZADA');

    assert.strictEqual(devolver('PAT-001').reservaId, 1000, 'depois devolve a atrasada');
    assert.strictEqual(devolver('PAT-001').sucesso, false, 'a das 21:00 ainda não começou');
    assert.strictEqual(app.ss.sheets.reservas.dados[4][6], 'ATIVA', 'o outro projetor não muda');
    assert.strictEqual(devolver('sala 3').mensagem, 'Código de projetor inválido.');
  },

  'listar professores'() {
    const app = carregar(abasPadrao());
    const r = app.pedir({ acao: 'listarProfessores', token: TOKEN });
    assert.strictEqual(r.sucesso, true);
    assert.deepStrictEqual(r.professores.map((p) => [p.email, p.ativo]),
      [['ana@unifor.br', true], ['bruno@unifor.br', false], ['carla@unifor.br', true]],
      'linha sem e-mail válido fica de fora');
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
      acao: 'criarReserva', token: TOKEN, professorEmail: 'ana@unifor.br', projetorId: 'PAT-001',
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
