# Protótipo CCT - Unifor: reserva de projetores

App Android (Java) do terminal de reserva de projetores do CCT. O professor
se identifica pela digital (leitor Nitgen Hamster DX), e o projetor é
identificado pela tag NFC com o código de patrimônio (`PAT-001`). As
reservas ficam no Google Sheets, por um Web App do Apps Script.

| Parte | Documentação |
|---|---|
| NFC: leitura, gravação, API REST, Swagger e Apps Script | [`docs/nfc/README.md`](docs/nfc/README.md) |
| Apps Script e planilha | [`apps-script/README.md`](apps-script/README.md) |
| SDK da Nitgen (fora do Git) | [`app/libs/README.md`](app/libs/README.md) |

## Biometria

O professor é identificado pelo **e-mail** em todo o sistema: na aba
`professores` da planilha, nas reservas, no banco de digitais e nas telas.
Maiúsculas e espaços nas pontas não contam.

As digitais ficam só no aparelho, cifradas, em **2 arrays de 300 posições**
(`BiometricDatabase`, 600 professores):

- **Cadastro:** o professor informa o e-mail. As duas amostras do dedo vão
  para a primeira posição livre do array com menos digitais, para os dois
  ficarem do mesmo tamanho. Se o professor já tinha digital, ela é trocada
  na mesma posição.
- **Identificação:** os dois arrays são percorridos **ao mesmo tempo**, cada
  um numa thread com a sua própria instância do SDK, comparando a digital
  lida com cada posição ocupada (`VerifyMatch` 1:1). O tempo fica perto do
  de um array só. Se a digital conferir com mais de um professor, ninguém é
  identificado e o app pede o dedo de novo.
- **Conferência do paralelo:** o SDK é beta e pode não aceitar mais de uma
  instância. Na primeira identificação com digitais cadastradas, o
  `NitgenMatcher` compara as mesmas amostras em todas as instâncias ao mesmo
  tempo. Se algum resultado for diferente do da instância principal, ou se
  a instância extra não abrir, a busca fica sequencial: mais lenta, mas
  certa. A tela de digitais mostra qual modo está em uso.
- **Digitais antigas:** as cadastradas antes do e-mail (com id numérico) são
  descartadas ao abrir o app e precisam ser cadastradas de novo.
- **Quem cadastra:** cadastrar e remover digitais exige o **PIN da
  coordenação** (`admin/PinAdministrador`), de 4 a 6 dígitos. Identificar
  não pede PIN.
  - No primeiro uso, a tela de digitais pede para criar o PIN. Ele é guardado
    só como hash PBKDF2 com sal, no aparelho.
  - Depois de digitado, vale por 5 minutos ou até sair da tela.
  - Com 5 erros seguidos, as tentativas ficam bloqueadas por 5 minutos, mesmo
    reabrindo o app.
  - **Esqueceu o PIN?** Limpe os dados do app em Configurações > Apps. Isso
    apaga também todas as digitais, que precisam ser cadastradas de novo.

## Fluxo do terminal (reservar e devolver)

O card **Reservar** da tela inicial abre a `ReservaActivity`. Toda a regra
está no `fluxo/FluxoReserva`, que não depende de tela:

| Modo | Etapas |
|---|---|
| Reservar | `DIGITAL` → `IDENTIFICANDO` → `PROJETOR` (tag NFC) → `HORARIO` → `ENVIANDO` → `CONCLUIDA` |
| Devolver | `PROJETOR` (tag NFC) → `ENVIANDO` → `CONCLUIDA` (não pede digital) |

- A tela repassa os eventos: `lerDigital()`, `projetorLido(ResultadoNfc)`
  (ou `informarProjetor(codigo)`, para o QR Code), `confirmarHorario(...)`,
  `voltar()`, `reiniciar()` e `iniciar(Modo)`.
- A cada mudança, o fluxo entrega um `Estado` imutável com a etapa, o
  professor, o projetor, o horário, o número da reserva e a mensagem. Se
  `erro == true`, a mensagem deve aparecer em destaque.
- Erros não encerram o fluxo: a digital não reconhecida fica em `DIGITAL`,
  e o projetor ocupado volta para `HORARIO` com a mensagem do servidor.
- O horário sugerido começa agora e dura 2 horas. A duração máxima e a
  recusa de horário que já passou são conferidas pelo Apps Script.
- A devolução usa só a tag: o Apps Script acha a reserva em uso daquele
  projetor.
- A `ReservaActivity` volta ao começo 15 s depois de concluir, ou depois de
  2 min parada no meio. O visual é provisório.

## Build e testes

```bash
./gradlew testDebugUnitTest assembleDebug   # precisa do .aar da Nitgen em app/libs/
node apps-script/teste/teste.js
```
