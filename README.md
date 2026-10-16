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

As digitais ficam só no aparelho, cifradas, num **array de posições**
(`BiometricDatabase`, 100 posições):

- **Cadastro:** percorre o array até a primeira posição livre e grava ali as
  duas amostras do dedo. Se o professor já tinha digital, ela é trocada na
  mesma posição.
- **Identificação:** percorre o array e compara a digital lida com cada
  posição ocupada (`VerifyMatch` 1:1 do SDK). Se conferir com mais de um
  professor, ninguém é identificado e o app pede o dedo de novo.
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
