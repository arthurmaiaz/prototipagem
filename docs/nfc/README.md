# PoC NFC: leitura do patrimônio do projetor

Prova de conceito da issue
[#14 do kanban](https://github.com/marcelobezerra-dotcom/Projeto-Requisitos-GAC/issues/14).
O app Android grava e lê, numa tag NFC, o código de patrimônio do projetor e
devolve esse código em JSON por uma API REST que roda no próprio celular. O
Google Apps Script chama essa API e registra a resposta na planilha.

## Formato do identificador de patrimônio

| Item | Definição |
|---|---|
| Padrão | `PAT-` seguido de 3 ou mais dígitos: regex `^PAT-\d{3,}$` |
| Exemplos válidos | `PAT-001`, `PAT-042`, `PAT-1234` |
| Exemplos inválidos | `PAT-01`, `PAT001`, `pat-001` na tag (na tela de gravação o texto é convertido para maiúsculas) |
| Onde é validado | `Projetor.codigoValido()` no Android e `PADRAO_PROJETOR` no `apps-script/Code.gs` |
| Na tag | Mensagem NDEF com um record de texto (`RTD_TEXT`, idioma `pt`, UTF-8) contendo só o código |

Na leitura, o código é o primeiro record de texto que segue o padrão. Os
outros textos e as URLs da tag também são devolvidos pela API (`texts`, `urls`).

## Bibliotecas

Só APIs do próprio Android e do Java. Não há dependência externa.

| Uso | Biblioteca |
|---|---|
| Gravação e leitura da tag | `android.nfc` (`NfcAdapter` com foreground dispatch, `Ndef`, `NdefFormatable`, `NdefRecord`) |
| Servidor HTTP da API | `java.net.ServerSocket` (classe `rest/ServidorHttp`, feita à mão) |
| JSON | `org.json` (embutido no Android) |
| Serviço em segundo plano | `android.app.Service` em primeiro plano (`foregroundServiceType="specialUse"`) |

Requisitos: Android 8.0 (API 26) ou mais novo, com NFC. Tags NDEF, como
NTAG213/215/216 ou MIFARE Ultralight.

## Código

| Arquivo | O que faz |
|---|---|
| `app/.../nfc/NfcService.java` | Lê e grava tags. Valida o padrão `PAT-001`. |
| `app/.../nfc/ResultadoNfc.java` | Resultado da leitura: código, mensagem, textos, URLs, id e tecnologias da tag. |
| `app/.../ui/TesteNfcActivity.java` | Tela "Teste NFC": leitura, gravação e controle da API. |
| `app/.../rest/ServicoApiNfc.java` | Serviço em primeiro plano que mantém a API no ar (porta 8787). |
| `app/.../rest/NfcRestApi.java` | Rotas, autenticação Bearer e auditoria. |
| `app/.../rest/PonteNfc.java` | Leva a leitura da tela até o pedido HTTP que está esperando. |
| `docs/nfc/openapi.yaml` | Swagger (OpenAPI 3) da API. |
| `scripts/test-api.ps1` | Script de teste do professor (cópia sem alterações). |
| `apps-script/TesteNfc.gs` | Chamada de teste a partir do Google Apps Script. |

## Como executar

1. Compile e instale: `./gradlew installDebug`. Veja `app/libs/README.md`
   para o SDK da Nitgen, que é necessário para o build.
2. No celular, abra o app e toque no card **Cadastrar tag (NFC)**, ou rode
   `adb shell am start -n br.unifor.cct/.ui.TesteNfcActivity`. Ligue o NFC
   se a tela pedir.

### Gravar uma tag

Digite o código (ex.: `PAT-001`), toque em **Gravar tag** e encoste a tag.
A opção "Travar" torna a tag somente leitura, e isso é **irreversível**.

### Ler uma tag

Com a tela aberta, encoste a tag. Aparece "Código lido: PAT-001".

### Ligar a API REST

1. Na mesma tela, toque em **Ligar API**. Se o Android pedir, permita as
   notificações.
2. A tela mostra a URL (`http://<ip>:8787`) e o **token**. Toque em
   **Copiar token**. Uma notificação fixa indica que a API está no ar.
3. O computador precisa estar na mesma rede Wi-Fi do celular.

O serviço continua no ar com o app em segundo plano. Mas **só a tela "Teste
NFC" aberta consegue ler a tag**, porque o Android só entrega tags para a
Activity em primeiro plano. Tocar na notificação abre a tela.

### Testar com o script do professor (Windows / PowerShell)

```powershell
Set-Content .\appnfc-test-token.txt "<token copiado da tela>"
.\scripts\test-api.ps1 -Ip 192.168.0.104              # só /health
.\scripts\test-api.ps1 -Ip 192.168.0.104 -ReadTag     # encoste a tag em até 30 s
.\scripts\test-api.ps1 -Ip 192.168.0.104 -Logs -Limit 10
```

O `appnfc-test-token.txt` está no `.gitignore`.

### Testar com curl (Linux / macOS)

```bash
IP=192.168.0.104; TOKEN='<token>'
curl -s -H "Authorization: Bearer $TOKEN" http://$IP:8787/health
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{}' --max-time 40 http://$IP:8787/v1/nfc/read
curl -s -H "Authorization: Bearer $TOKEN" "http://$IP:8787/v1/audit/logs?limit=5&offset=0"
```

Exemplo de resposta de `POST /v1/nfc/read`:

```json
{
  "success": true,
  "valid": true,
  "patrimony": "PAT-001",
  "message": "Projetor PAT-001 identificado.",
  "tagId": "04A1B2C3D4E5F6",
  "techList": ["NfcA", "MifareUltralight", "Ndef"],
  "texts": ["PAT-001"],
  "urls": [],
  "readAt": "2026-10-15T20:41:07.123Z",
  "correlationId": "5b0c7f9e-..."
}
```

## Endpoints

O detalhe está no [`openapi.yaml`](openapi.yaml). Para visualizar, cole o
arquivo em <https://editor.swagger.io>.

| Rota | Retorno |
|---|---|
| `GET /health` | Estado do serviço e do NFC (`nfcScreenOpen`, `readPending`...). |
| `POST /v1/nfc/read` | Espera a próxima tag (padrão 30 s, `timeoutSeconds` de 1 a 35). Códigos: 200, 408 (sem tag), 409 (outra leitura esperando ou modo de gravação), 503 (tela fechada ou NFC desligado). |
| `POST /v1/qr/read` | 501. O QR Code ainda não foi integrado neste app. |
| `GET /v1/audit/logs?limit=1..50&offset=0..10000` | Chamadas registradas, da mais recente para a mais antiga. |

Todas exigem `Authorization: Bearer <token>`; sem ele, a resposta é 401. O
`X-Correlation-Id` volta no header e no JSON.

## HTTPS e Google Apps Script

O Apps Script roda nos servidores do Google e não alcança o IP local do
celular (`192.168.x.x`). A API precisa então de uma URL **pública HTTPS**.
Na PoC isso é feito com um túnel num computador da mesma rede:

```bash
# https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/
cloudflared tunnel --url http://192.168.0.104:8787
# mostra algo como https://abcd-1234.trycloudflare.com
```

(Com ngrok: `ngrok http 192.168.0.104:8787`.)

No projeto do Apps Script:

1. Adicione o arquivo `apps-script/TesteNfc.gs`, ao lado do `Code.gs`.
2. Em **Configurações do projeto > Propriedades do script**, crie:
   - `NFC_API_URL` = a URL do túnel, sem barra no fim
   - `NFC_API_TOKEN` = o token da tela do celular
3. Rode `testarHealthNfc`. Para `testarLeituraNfc`, deixe a tela "Teste
   NFC" aberta e encoste a tag em até 30 s.
4. Cada resposta vai para o **Registro de execução** e para a aba
   `leituras_nfc`, criada sozinha.

## Evidências

- Testes automatizados (`./gradlew testDebugUnitTest`): `NfcRestApiTest`
  sobe o servidor numa porta local e cobre 401, health, leitura com
  patrimônio, tag sem código válido, 408, 409, 503, 501, 404/405, a
  paginação da auditoria e pedido HTTP malformado.
- Testes do Apps Script (`node apps-script/teste/teste.js`): chamada da API
  e registro na aba `leituras_nfc`, inclusive em caso de erro HTTP.
- Prints do celular e do PowerShell: adicionar em `docs/nfc/evidencias/`
  depois do teste com a tag real (tela de leitura, tela com a API ligada,
  saída do `test-api.ps1 -ReadTag` e aba `leituras_nfc`).

## Limitações

- A leitura só acontece com a tela "Teste NFC" em primeiro plano e a tela
  do celular ligada. É uma limitação do Android para apps comuns.
- HTTP sem TLS na rede local. O HTTPS vem do túnel. Qualquer pessoa na
  mesma rede que tiver o token consegue chamar a API, então troque o token
  na tela se ele vazar.
- Uma leitura por vez. Um segundo `POST /v1/nfc/read` recebe 409.
- A auditoria fica só em memória e se perde quando o app fecha.
- O IP do celular pode mudar quando a Wi-Fi reconecta. A tela e a
  notificação mostram o IP atual.
- O QR Code e o código de barras (leitura por câmera) estão com outra
  pessoa do grupo. A rota `/v1/qr/read` responde 501 até a integração.
