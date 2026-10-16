/**
 * Reserva de Projetores CCT: Web App do Google Apps Script.
 *
 * Recebe os pedidos do terminal Android (AppsScriptClient) por POST, com
 * JSON no corpo, e guarda tudo na planilha à qual este script está ligado.
 *
 * Pedidos:
 *   { acao: "criarReserva", token, professorId, projetorId, data, inicio, fim }
 *   { acao: "cancelarReserva" | "consultarReserva" | "registrarDevolucao", token, reservaId }
 *   { acao: "registrarDevolucao", token, projetorId }   (devolução pela tag, sem o id)
 *   { acao: "listarProfessores", token }
 * Resposta:
 *   { sucesso, reservaId, mensagem }   (listarProfessores também traz "professores")
 *
 * O token do terminal fica nas Propriedades do script (chave TOKEN), nunca
 * na planilha. Rode definirToken() uma vez para gerar.
 *
 * Regras da reserva: o fim não pode ter passado, a duração não passa de
 * duracaoMaximaMinutos (aba config, padrão 240) e não pode haver outra
 * reserva ATIVA do mesmo projetor no mesmo horário. A devolução muda a
 * reserva para FINALIZADA e libera o resto do horário.
 *
 * Nenhum dado biométrico chega aqui: o terminal só envia o professorId.
 */

var ABA_PROFESSORES = 'professores';
var ABA_PROJETORES = 'projetores';
var ABA_RESERVAS = 'reservas';
var ABA_CONFIG = 'config';

var CABECALHOS = {
  professores: ['id', 'nome', 'matricula', 'ativo'],
  projetores: ['codigo', 'descricao', 'ativo'],
  reservas: ['id', 'professorId', 'projetorId', 'data', 'inicio', 'fim', 'status', 'criadaEm', 'canceladaEm', 'devolvidaEm'],
  config: ['chave', 'valor']
};

// Colunas da aba reservas (1 = A), usadas para atualizar uma célula.
var COL_STATUS = 7;
var COL_CANCELADA_EM = 9;
var COL_DEVOLVIDA_EM = 10;

var PRIMEIRO_RESERVA_ID = 1000;
var ESPERA_LOCK_MS = 10000;
var DURACAO_MAXIMA_PADRAO_MIN = 240;

var PADRAO_PROJETOR = /^PAT-\d{3,}$/;
var PADRAO_DATA = /^\d{4}-\d{2}-\d{2}$/;
var PADRAO_HORA = /^([01]\d|2[0-3]):[0-5]\d$/;

// ------------------------------------------------------------ entrada HTTP

function doPost(e) {
  return responderJson(tratarPedido(e && e.postData ? e.postData.contents : null));
}

/** GET serve só para conferir no navegador se o Web App está no ar. */
function doGet() {
  return responderJson({ sucesso: true, reservaId: null, mensagem: 'Web App de reservas no ar.' });
}

function responderJson(objeto) {
  return ContentService.createTextOutput(JSON.stringify(objeto))
      .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Separado do doPost para poder ser testado sem HTTP.
 * Nunca lança exceção: qualquer falha vira { sucesso: false, mensagem }.
 */
function tratarPedido(corpo) {
  var pedido;
  try {
    pedido = JSON.parse(corpo);
  } catch (erro) {
    return falha('Requisição inválida.');
  }
  if (!pedido || typeof pedido !== 'object') {
    return falha('Requisição inválida.');
  }

  var tokenEsperado = PropertiesService.getScriptProperties().getProperty('TOKEN');
  if (!tokenEsperado || pedido.token !== tokenEsperado) {
    return falha('Terminal não autorizado.');
  }

  try {
    switch (pedido.acao) {
      case 'criarReserva':
        return comLock(function () { return criarReserva(pedido); });
      case 'cancelarReserva':
        return comLock(function () { return cancelarReserva(pedido); });
      case 'registrarDevolucao':
        return comLock(function () { return registrarDevolucao(pedido); });
      case 'consultarReserva':
        return consultarReserva(pedido);
      case 'listarProfessores':
        return listarProfessores();
      default:
        return falha('Ação desconhecida: ' + pedido.acao);
    }
  } catch (erro) {
    console.error(erro);
    return falha('Erro interno no servidor. Tente novamente.');
  }
}

/** Impede que dois terminais reservem o mesmo horário ao mesmo tempo. */
function comLock(funcao) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(ESPERA_LOCK_MS)) {
    return falha('Servidor ocupado. Tente novamente.');
  }
  try {
    return funcao();
  } finally {
    lock.releaseLock();
  }
}

// ------------------------------------------------------------------ ações

function criarReserva(p) {
  var professorId = Number(p.professorId);
  if (!Number.isInteger(professorId)) return falha('Professor inválido.');
  if (!PADRAO_PROJETOR.test(p.projetorId || '')) return falha('Código de projetor inválido.');
  if (!dataValida(p.data)) return falha('Data inválida. Use o formato aaaa-mm-dd.');
  if (!PADRAO_HORA.test(p.inicio || '') || !PADRAO_HORA.test(p.fim || '')) {
    return falha('Horário inválido. Use o formato hh:mm.');
  }
  if (p.fim <= p.inicio) return falha('O horário de fim deve ser depois do início.');

  var duracaoMaxima = duracaoMaximaMinutos();
  if (minutos(p.fim) - minutos(p.inicio) > duracaoMaxima) {
    return falha('A reserva pode durar no máximo ' + textoDuracao(duracaoMaxima) + '.');
  }
  var hoje = dataHoraLocal(agora());
  if (p.data < hoje.data || (p.data === hoje.data && p.fim <= hoje.hora)) {
    return falha('Esse horário já passou. Escolha um horário futuro.');
  }

  var professor = buscar(lerProfessores(), function (x) { return x.id === professorId; });
  if (!professor) return falha('Professor não cadastrado.');
  if (!professor.ativo) return falha('Professor inativo. Procure a coordenação.');

  var projetor = buscar(lerProjetores(), function (x) { return x.codigo === p.projetorId; });
  if (!projetor) return falha('Projetor ' + p.projetorId + ' não cadastrado.');
  if (!projetor.ativo) return falha('Projetor ' + p.projetorId + ' indisponível (inativo).');

  var conflito = buscar(lerReservas(), function (r) {
    return r.status === 'ATIVA'
        && r.projetorId === p.projetorId
        && r.data === p.data
        && p.inicio < r.fim
        && r.inicio < p.fim;
  });
  if (conflito) {
    return falha('Projetor ' + p.projetorId + ' já reservado das '
        + conflito.inicio + ' às ' + conflito.fim + ' nesse dia.');
  }

  var id = proximoReservaId();
  aba(ABA_RESERVAS).appendRow([
    id, professorId, p.projetorId, p.data, p.inicio, p.fim, 'ATIVA', new Date(), ''
  ]);
  return { sucesso: true, reservaId: id, mensagem: 'Reserva realizada com sucesso.' };
}

function cancelarReserva(p) {
  var reserva = buscarReserva(p.reservaId);
  if (!reserva) return falha('Reserva não encontrada.');
  if (reserva.status !== 'ATIVA') {
    return falha('A reserva ' + reserva.id + ' não está ativa (' + reserva.status + ').');
  }
  var planilha = aba(ABA_RESERVAS);
  planilha.getRange(reserva.linha, COL_STATUS).setValue('CANCELADA');
  planilha.getRange(reserva.linha, COL_CANCELADA_EM).setValue(new Date());
  return { sucesso: true, reservaId: reserva.id, mensagem: 'Reserva cancelada.' };
}

/**
 * O professor devolveu o projetor: a reserva termina e o horário fica livre.
 * Aceita o reservaId ou, no terminal, só o projetorId lido na tag: nesse
 * caso vale a reserva ATIVA do projetor que começou por último.
 */
function registrarDevolucao(p) {
  var temId = p.reservaId !== undefined && p.reservaId !== null && p.reservaId !== '';
  if (!temId && p.projetorId !== undefined) {
    if (!PADRAO_PROJETOR.test(p.projetorId || '')) return falha('Código de projetor inválido.');
    var emUso = reservaEmUso(p.projetorId);
    if (!emUso) return falha('O projetor ' + p.projetorId + ' não tem reserva em uso para devolver.');
    p = { reservaId: emUso.id };
  }
  var reserva = buscarReserva(p.reservaId);
  if (!reserva) return falha('Reserva não encontrada.');
  if (reserva.status !== 'ATIVA') {
    return falha('A reserva ' + reserva.id + ' não está ativa (' + reserva.status + ').');
  }
  var momento = agora();
  var local = dataHoraLocal(momento);
  if (local.data + ' ' + local.hora < reserva.data + ' ' + reserva.inicio) {
    return falha('A reserva ' + reserva.id + ' ainda não começou. Para desistir, cancele a reserva.');
  }
  var planilha = aba(ABA_RESERVAS);
  planilha.getRange(reserva.linha, COL_STATUS).setValue('FINALIZADA');
  planilha.getRange(reserva.linha, COL_DEVOLVIDA_EM).setValue(momento);
  return { sucesso: true, reservaId: reserva.id, mensagem: 'Devolução registrada.' };
}

/** Reserva ATIVA do projetor que já começou; se houver mais de uma, a mais recente. */
function reservaEmUso(projetorId) {
  var local = dataHoraLocal(agora());
  var agoraTexto = local.data + ' ' + local.hora;
  return lerReservas().filter(function (r) {
    return r.status === 'ATIVA' && r.projetorId === projetorId
        && r.data + ' ' + r.inicio <= agoraTexto;
  }).reduce(function (escolhida, r) {
    return !escolhida || r.data + ' ' + r.inicio > escolhida.data + ' ' + escolhida.inicio ? r : escolhida;
  }, null);
}

function consultarReserva(p) {
  var r = buscarReserva(p.reservaId);
  if (!r) return falha('Reserva não encontrada.');
  return {
    sucesso: true,
    reservaId: r.id,
    mensagem: 'Reserva ' + r.id + ': ' + r.projetorId + ' em ' + dataBr(r.data)
        + ', das ' + r.inicio + ' às ' + r.fim + ' (' + r.status + ').'
  };
}

/** Para o terminal sincronizar o ProfessorRepository. */
function listarProfessores() {
  var lista = lerProfessores().map(function (x) {
    return { id: x.id, nome: x.nome, matricula: x.matricula, ativo: x.ativo };
  });
  return { sucesso: true, reservaId: null, mensagem: lista.length + ' professor(es).', professores: lista };
}

// ---------------------------------------------------------- leitura de abas

function lerProfessores() {
  return linhas(ABA_PROFESSORES).map(function (l) {
    return { id: Number(l.valores[0]), nome: texto(l.valores[1]), matricula: texto(l.valores[2]) || null,
             ativo: booleano(l.valores[3]) };
  }).filter(function (x) { return Number.isInteger(x.id) && x.nome; });
}

function lerProjetores() {
  return linhas(ABA_PROJETORES).map(function (l) {
    return { codigo: texto(l.valores[0]).toUpperCase(), descricao: texto(l.valores[1]),
             ativo: booleano(l.valores[2]) };
  }).filter(function (x) { return x.codigo; });
}

function lerReservas() {
  return linhas(ABA_RESERVAS).map(function (l) {
    var v = l.valores;
    return { linha: l.linha, id: Number(v[0]), professorId: Number(v[1]), projetorId: texto(v[2]),
             data: textoData(v[3]), inicio: textoHora(v[4]), fim: textoHora(v[5]),
             status: texto(v[6]).toUpperCase() };
  }).filter(function (x) { return Number.isInteger(x.id); });
}

function buscarReserva(reservaId) {
  var id = Number(reservaId);
  if (!Number.isInteger(id)) return null;
  return buscar(lerReservas(), function (r) { return r.id === id; });
}

/** Linhas de dados (sem cabeçalho), com o número da linha na planilha. */
function linhas(nomeAba) {
  var valores = aba(nomeAba).getDataRange().getValues();
  var resultado = [];
  for (var i = 1; i < valores.length; i++) {
    resultado.push({ linha: i + 1, valores: valores[i] });
  }
  return resultado;
}

function aba(nome) {
  var planilha = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(nome);
  if (!planilha) {
    throw new Error('Aba "' + nome + '" não existe. Rode configurarPlanilha().');
  }
  return planilha;
}

/** Lê e incrementa o contador da aba config. Chamar só dentro do lock. */
function proximoReservaId() {
  var config = linhas(ABA_CONFIG);
  var linhaContador = buscar(config, function (l) { return texto(l.valores[0]) === 'proximoReservaId'; });
  var maiorExistente = lerReservas().reduce(function (m, r) { return Math.max(m, r.id); }, PRIMEIRO_RESERVA_ID - 1);
  var id = linhaContador ? Number(linhaContador.valores[1]) : NaN;
  // Se alguém apagou ou mexeu no contador, nunca repete um id já usado.
  if (!Number.isInteger(id) || id <= maiorExistente) {
    id = maiorExistente + 1;
  }
  if (linhaContador) {
    aba(ABA_CONFIG).getRange(linhaContador.linha, 2).setValue(id + 1);
  } else {
    aba(ABA_CONFIG).appendRow(['proximoReservaId', id + 1]);
  }
  return id;
}

/** Lê um valor da aba config; devolve o padrão se a chave não existir. */
function lerConfig(chave, padrao) {
  var linha = buscar(linhas(ABA_CONFIG), function (l) { return texto(l.valores[0]) === chave; });
  return linha ? linha.valores[1] : padrao;
}

function duracaoMaximaMinutos() {
  var valor = Number(lerConfig('duracaoMaximaMinutos', DURACAO_MAXIMA_PADRAO_MIN));
  return Number.isInteger(valor) && valor > 0 ? valor : DURACAO_MAXIMA_PADRAO_MIN;
}

// --------------------------------------------------------------- utilidades

/** Separado para os testes poderem fixar o relógio. */
function agora() {
  return new Date();
}

/** "hh:mm" em minutos desde a meia-noite. */
function minutos(hora) {
  var partes = hora.split(':').map(Number);
  return partes[0] * 60 + partes[1];
}

function textoDuracao(totalMinutos) {
  var h = Math.floor(totalMinutos / 60);
  var m = totalMinutos % 60;
  if (!m) return h + (h === 1 ? ' hora' : ' horas');
  return h ? h + 'h' + String(m).padStart(2, '0') : m + ' minutos';
}

function falha(mensagem) {
  return { sucesso: false, reservaId: null, mensagem: mensagem };
}

function buscar(lista, condicao) {
  for (var i = 0; i < lista.length; i++) {
    if (condicao(lista[i])) return lista[i];
  }
  return null;
}

function texto(valor) {
  return valor === null || valor === undefined ? '' : String(valor).trim();
}

/** Aceita a caixa de seleção do Sheets e também textos digitados à mão. */
function booleano(valor) {
  if (valor === true) return true;
  var t = texto(valor).toUpperCase();
  return t === 'TRUE' || t === 'VERDADEIRO' || t === 'SIM' || t === '1';
}

/**
 * As colunas de data e hora são formatadas como texto pelo
 * configurarPlanilha(), mas se alguém digitar à mão o Sheets pode
 * convertê-las em Date. Aqui as duas formas viram "aaaa-mm-dd" / "hh:mm".
 */
function textoData(valor) {
  if (valor instanceof Date) return Utilities.formatDate(valor, fuso(), 'yyyy-MM-dd');
  return texto(valor);
}

function textoHora(valor) {
  if (valor instanceof Date) return Utilities.formatDate(valor, fuso(), 'HH:mm');
  return texto(valor);
}

/** Data "aaaa-mm-dd" e hora "hh:mm" de um instante, no fuso da planilha. */
function dataHoraLocal(momento) {
  return {
    data: Utilities.formatDate(momento, fuso(), 'yyyy-MM-dd'),
    hora: Utilities.formatDate(momento, fuso(), 'HH:mm')
  };
}

function fuso() {
  return SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
}

function dataValida(data) {
  if (!PADRAO_DATA.test(data || '')) return false;
  var partes = data.split('-').map(Number);
  var d = new Date(Date.UTC(partes[0], partes[1] - 1, partes[2]));
  return d.getUTCFullYear() === partes[0] && d.getUTCMonth() === partes[1] - 1 && d.getUTCDate() === partes[2];
}

function dataBr(data) {
  var p = data.split('-');
  return p[2] + '/' + p[1] + '/' + p[0];
}

// ---------------------------------------------- configuração (rodar à mão)

/**
 * Cria as abas que faltam, com cabeçalho, e formata as colunas de data e
 * hora como texto. Pode rodar de novo sem perder dados.
 */
function configurarPlanilha() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  Object.keys(CABECALHOS).forEach(function (nome) {
    var planilha = ss.getSheetByName(nome) || ss.insertSheet(nome);
    var cabecalho = CABECALHOS[nome];
    planilha.getRange(1, 1, 1, cabecalho.length).setValues([cabecalho]).setFontWeight('bold');
    planilha.setFrozenRows(1);
  });

  var maxLinhas = 1000;
  // matricula, codigo e data/hora como texto, para o Sheets não converter.
  ss.getSheetByName(ABA_PROFESSORES).getRange(2, 3, maxLinhas, 1).setNumberFormat('@');
  ss.getSheetByName(ABA_PROFESSORES).getRange(2, 4, maxLinhas, 1).insertCheckboxes();
  ss.getSheetByName(ABA_PROJETORES).getRange(2, 1, maxLinhas, 1).setNumberFormat('@');
  ss.getSheetByName(ABA_PROJETORES).getRange(2, 3, maxLinhas, 1).insertCheckboxes();
  ss.getSheetByName(ABA_RESERVAS).getRange(2, 3, maxLinhas, 4).setNumberFormat('@');

  var config = ss.getSheetByName(ABA_CONFIG);
  var temContador = config.getDataRange().getValues().some(function (l) { return l[0] === 'proximoReservaId'; });
  if (!temContador) config.appendRow(['proximoReservaId', PRIMEIRO_RESERVA_ID]);
  var temDuracao = config.getDataRange().getValues().some(function (l) { return l[0] === 'duracaoMaximaMinutos'; });
  if (!temDuracao) config.appendRow(['duracaoMaximaMinutos', DURACAO_MAXIMA_PADRAO_MIN]);

  var padrao = ss.getSheetByName('Página1') || ss.getSheetByName('Sheet1');
  if (padrao && padrao.getLastRow() === 0 && ss.getSheets().length > 1) ss.deleteSheet(padrao);
}

/**
 * Gera um token novo para o terminal e mostra no registro de execução.
 * Copie o valor para appsScript.token no local.properties do Android.
 * Rodar de novo troca o token: o terminal antigo deixa de funcionar.
 */
function definirToken() {
  var token = Utilities.getUuid() + Utilities.getUuid().replace(/-/g, '');
  PropertiesService.getScriptProperties().setProperty('TOKEN', token);
  console.log('Token do terminal: ' + token);
}
