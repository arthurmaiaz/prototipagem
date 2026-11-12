# QR Codes de exemplo

Gerados com o `GeradorQrCode` do app (600 × 600 px, correção de erro H, margem 1) e lidos de
volta com o `DecodificadorQr`, o mesmo usado na leitura pela galeria.

| Arquivo | Conteúdo gravado | Patrimônio lido pelo app |
|---|---|---|
| `qr-PAT-001.png` | `PAT-001` | `PAT-001` |
| `qr-PAT-002.png` | `PAT-002` | `PAT-002` |
| `qr-PAT-003.png` | `PAT-003` | `PAT-003` |
| `qr-projetor-PAT-004.png` | `projetor:PAT-004` | `PAT-004` (prefixo da PoC aceito) |
| `qr-projetor-1-recusado.png` | `projetor:1` | recusado: formato da PoC fora do padrão PAT |
| `qr-url-recusado.png` | `https://exemplo.com/nao-e-projetor` | recusado: não é projetor |

Para testar no celular: abra um destes arquivos na tela do PC e aponte a câmera da tela
"Ler QR Code", ou copie o arquivo para o celular e use **Ler de uma imagem**. O próprio app
também gera o QR de qualquer patrimônio em **Gerar QR Code** e pode salvá-lo em Imagens/CCT.
