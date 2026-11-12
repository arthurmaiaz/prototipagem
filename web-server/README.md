# Servidor web (demonstração)

Página com as opções do GAC. Cada botão vira uma chamada REST (HTTP + JSON) para a API
do app `br.unifor.cct` no celular, na porta 8787.

```
navegador ──> servidor web (Node, porta 3000) ──REST + Bearer──> celular (IP:8787)
```

Quem fala com o celular é o servidor, não o navegador. Assim o token não vai para a página
e não há problema de CORS.

| Botão               | Rota no celular                |
|---------------------|--------------------------------|
| Status do celular   | `GET /health`                  |
| Ler NFC             | `POST /v1/nfc/read`            |
| Ler QR Code         | `POST /v1/qr/read`             |
| Ler digital         | `POST /v1/biometric/identify`  |
| Logs de auditoria   | `GET /v1/audit/logs?limit=20`  |

## Como rodar

Precisa de Node 18 ou mais novo. Não há dependências, então não é preciso `npm install`.

1. No celular, abra o app e ligue a API (a tela mostra o IP, a porta e o token).
2. Coloque o token em `appnfc-test-token.txt`, na raiz do repositório. É o mesmo arquivo
   usado pelo `scripts/test-api.sh`, e ele está no `.gitignore`.
3. Rode:

```bash
cd web-server
CELULAR_IP=10.50.103.53 npm start
```

4. Abra http://localhost:3000. O IP e a porta do celular também podem ser trocados na página.

Variáveis opcionais: `CELULAR_PORTA` (8787), `TOKEN_PATH`, `PORTA` (3000) e `HOST` (127.0.0.1).
Use `HOST=0.0.0.0` para abrir a página a partir de outro computador da rede.

## Rede

O PC e o celular precisam se enxergar. Na rede da UNIFOR os dispositivos ficam isolados, então
use a mesma rede (por exemplo, o roteador do celular) ou faça `adb forward tcp:8787 tcp:8787` e
rode com `CELULAR_IP=127.0.0.1`.

Este servidor é só para demonstração: não tem login. Qualquer pessoa que abrir a página pode
mandar comandos para o celular.
