'use strict';

const $ = (id) => document.getElementById(id);
const botoes = document.querySelectorAll('[data-opcao]');

async function carregarConfig() {
  const resposta = await fetch('/api/config');
  const config = await resposta.json();
  $('ip').value = config.ip;
  $('porta').value = config.porta;
}

$('form-alvo').addEventListener('submit', async (evento) => {
  evento.preventDefault();
  const resposta = await fetch('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ip: $('ip').value.trim(), porta: Number($('porta').value) }),
  });
  const dados = await resposta.json();
  definirEstado(resposta.ok ? `Alvo: ${dados.ip}:${dados.porta}` : dados.erro, resposta.ok ? '' : 'erro');
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
