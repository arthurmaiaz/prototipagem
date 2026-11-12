'use strict';

const $ = (id) => document.getElementById(id);
const botoes = document.querySelectorAll('[data-opcao]');

async function carregarConfig() {
  const resposta = await fetch('/api/config');
  const config = await resposta.json();
  $('ip').value = config.ip;
  $('porta').value = config.porta;
}

async function salvarAlvo(ip, porta) {
  const resposta = await fetch('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ip, porta }),
  });
  const dados = await resposta.json();
  definirEstado(resposta.ok ? `Alvo: ${dados.ip}:${dados.porta}` : dados.erro, resposta.ok ? '' : 'erro');
  return resposta.ok;
}

$('form-alvo').addEventListener('submit', (evento) => {
  evento.preventDefault();
  salvarAlvo($('ip').value.trim(), Number($('porta').value));
});

async function usarCelular(achado) {
  $('ip').value = achado.ip;
  $('porta').value = achado.porta;
  if (await salvarAlvo(achado.ip, achado.porta)) {
    definirEstado(`Alvo: ${achado.ip}:${achado.porta} ${achado.tokenOk ? '(online)' : '(token não confere)'}`,
      achado.tokenOk ? 'ok' : 'erro');
  }
}

$('procurar').addEventListener('click', async () => {
  const caixa = $('procura');
  const botao = $('procurar');
  // A procura usa a porta salva no servidor; salva a do campo antes.
  if (!(await salvarAlvo($('ip').value.trim(), Number($('porta').value)))) return;

  botao.disabled = true;
  caixa.hidden = false;
  caixa.className = 'procura';
  caixa.textContent = `Procurando na rede, porta ${$('porta').value}… (pode levar alguns segundos)`;

  try {
    const resposta = await fetch('/api/procurar', { method: 'POST' });
    const dados = await resposta.json();
    if (!resposta.ok) throw new Error(dados.erro);

    caixa.textContent = '';
    const info = document.createElement('p');
    info.textContent = `${dados.testados} endereços testados em ${(dados.duracaoMs / 1000).toFixed(1)} s ` +
      `(redes: ${dados.redes.join(', ') || 'nenhuma'} + 127.0.0.1).`;
    caixa.append(info);

    if (dados.encontrados.length === 0) {
      caixa.classList.add('erro');
      const p = document.createElement('p');
      p.textContent = 'Nenhum celular encontrado. Confira se a API está ligada e se o PC e o celular estão ' +
        'na mesma rede (a rede da UNIFOR isola os aparelhos).';
      caixa.append(p);
      return;
    }

    for (const achado of dados.encontrados) {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = `achado ${achado.tokenOk ? 'ok' : 'erro'}`;
      item.textContent = `${achado.ip}:${achado.porta} — HTTP ${achado.status} ` +
        (achado.tokenOk ? '(token OK)' : '(é a API, mas o token não confere)');
      item.addEventListener('click', () => usarCelular(achado));
      caixa.append(item);
    }

    const comToken = dados.encontrados.filter((a) => a.tokenOk);
    if (comToken.length === 1) await usarCelular(comToken[0]);
  } catch (erro) {
    caixa.classList.add('erro');
    caixa.textContent = `Falha na procura: ${erro.message}`;
  } finally {
    botao.disabled = false;
  }
});

function definirEstado(texto, classe) {
  $('estado').textContent = texto;
  $('estado').className = `estado ${classe || ''}`;
}

function mostrar(resultado) {
  const resumo = $('resumo');
  if (resultado.erro) {
    resumo.className = 'resumo erro';
    resumo.textContent = `Falhou (${resultado.alvo || 'servidor'}): ${resultado.erro}`;
    $('json').textContent = '';
    return;
  }
  resumo.className = `resumo ${resultado.ok ? 'ok' : 'erro'}`;
  resumo.textContent = `${resultado.requisicao} → HTTP ${resultado.status} em ${resultado.duracaoMs} ms (${resultado.alvo})`;
  $('json').textContent = typeof resultado.corpo === 'string'
    ? resultado.corpo
    : JSON.stringify(resultado.corpo, null, 2);
}

async function executar(botao) {
  const opcao = botao.dataset.opcao;
  botoes.forEach((b) => { b.disabled = true; });
  botao.classList.add('carregando');
  $('aviso').hidden = !botao.dataset.aviso;
  $('aviso').textContent = botao.dataset.aviso || '';
  $('resumo').className = 'resumo';
  $('resumo').textContent = 'Aguardando o celular…';
  $('json').textContent = '';

  try {
    const query = opcao === 'logs' ? '?limit=20&offset=0' : '';
    const resposta = await fetch(`/api/${opcao}${query}`, { method: 'POST' });
    const resultado = await resposta.json();
    mostrar(resultado);
    if (opcao === 'health') definirEstado(resultado.ok ? 'Online' : 'Offline', resultado.ok ? 'ok' : 'erro');
  } catch (erro) {
    mostrar({ erro: `servidor web fora do ar (${erro.message})` });
  } finally {
    botoes.forEach((b) => { b.disabled = false; });
    botao.classList.remove('carregando');
    $('aviso').hidden = true;
  }
}

botoes.forEach((botao) => botao.addEventListener('click', () => executar(botao)));
carregarConfig().catch(() => definirEstado('servidor web fora do ar', 'erro'));
