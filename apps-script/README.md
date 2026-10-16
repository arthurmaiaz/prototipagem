# Apps Script: Reserva de Projetores CCT

Web App que recebe os pedidos do terminal Android (`AppsScriptClient`) e guarda
as reservas no Google Sheets.

## Arquivos

| Arquivo | O que é |
|---|---|
| `Code.gs` | O script: regras de reserva, leitura das abas e configuração. |
| `appsscript.json` | Manifesto: fuso de Fortaleza, runtime V8, Web App aberto. |
| `teste/teste.js` | Testes que rodam no Node com uma planilha falsa. |
| `TesteNfc.gs` | Chamada de teste da API REST do NFC do celular (ver `docs/nfc/README.md`). |

## Abas da planilha

| Aba | Colunas |
|---|---|
| `professores` | `id`, `nome`, `matricula`, `ativo` |
| `projetores` | `codigo` (ex.: `PAT-001`), `descricao`, `ativo` |
| `reservas` | `id`, `professorId`, `projetorId`, `data`, `inicio`, `fim`, `status`, `criadaEm`, `canceladaEm`, `devolvidaEm` |
| `config` | `chave`, `valor` (guarda `proximoReservaId`, que começa em 1000, e `duracaoMaximaMinutos`, padrão 240) |
| `leituras_nfc` | Criada pelo `TesteNfc.gs`: uma linha por chamada à API do NFC. |

- O `id` do professor precisa ser o mesmo usado no cadastro da biometria no terminal.
- `status` é `ATIVA`, `CANCELADA` ou `FINALIZADA` (projetor devolvido). Nada é apagado: a aba `reservas` é o histórico.
- Só reservas `ATIVA` ocupam o horário. Cancelar ou devolver libera o projetor para outra reserva.
- Professor ou projetor com `ativo` desmarcado não pode reservar nem ser reservado.

## Como implantar

1. Crie uma planilha nova no Google Sheets, na conta que vai ser dona do sistema.
2. Na planilha, abra **Extensões > Apps Script**.
3. Apague o conteúdo do `Código.gs` e cole o conteúdo de `Code.gs`.
4. Em **Configurações do projeto** (engrenagem), marque **Mostrar o arquivo de
   manifesto "appsscript.json"** e cole o conteúdo de `appsscript.json` nele.
5. No editor, escolha a função **`configurarPlanilha`** e clique em **Executar**.
   Na primeira vez o Google pede autorização: aceite. As quatro abas são criadas.
6. Escolha a função **`definirToken`** e clique em **Executar**. O token aparece
   no **Registro de execução**. Guarde-o: ele vai para o Android.
7. Clique em **Implantar > Nova implantação**, tipo **App da Web**:
   - Executar como: **Eu**
   - Quem pode acessar: **Qualquer pessoa**
8. Copie a URL que termina em `/exec`.
9. No `local.properties` do projeto Android:
   ```properties
   appsScript.url=https://script.google.com/macros/s/.../exec
   appsScript.token=<o token do passo 6>
   ```
10. Cadastre pelo menos um professor e um projetor nas abas.

Para conferir se está no ar, abra a URL `/exec` no navegador. Ela deve mostrar
`{"sucesso":true,...,"mensagem":"Web App de reservas no ar."}`.

**Depois de mudar o código**, use **Implantar > Gerenciar implantações >
editar > Nova versão**. Assim a URL continua a mesma. Uma "Nova implantação"
gera outra URL. Se a versão nova trouxer colunas ou chaves novas (como
`devolvidaEm` e `duracaoMaximaMinutos`), rode `configurarPlanilha` de novo:
ela completa o cabeçalho e a aba `config` sem apagar dados.

## Segurança

- "Qualquer pessoa" é necessário porque o terminal não faz login no Google.
  Quem protege é o token: sem ele, toda chamada recebe "Terminal não autorizado".
- O token fica nas **Propriedades do script**, não na planilha. Rodar
  `definirToken` de novo troca o token, e o terminal precisa do valor novo.
- A planilha não recebe nenhum dado biométrico, só o `professorId`.

## Protocolo

Todo pedido é um POST com JSON. A resposta sempre traz
`{ "sucesso", "reservaId", "mensagem" }`.

| `acao` | Campos | Observação |
|---|---|---|
| `criarReserva` | `professorId`, `projetorId`, `data` (`aaaa-mm-dd`), `inicio`, `fim` (`hh:mm`) | Recusa conflito de horário no mesmo projetor e dia, horário cujo fim já passou e duração acima de `duracaoMaximaMinutos`. |
| `cancelarReserva` | `reservaId` | Só reservas `ATIVA`. |
| `registrarDevolucao` | `reservaId` | Só reservas `ATIVA` que já começaram. Muda para `FINALIZADA` e grava `devolvidaEm`. |
| `consultarReserva` | `reservaId` | A mensagem descreve a reserva e o status. |
| `listarProfessores` | nenhum | Também devolve `professores: [{ id, nome, matricula, ativo }]`. |

Todos os pedidos levam também o `token`.

## Testes

```
node apps-script/teste/teste.js
```
