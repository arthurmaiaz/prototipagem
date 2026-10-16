# Testes no celular, em 16/10/2026

Samsung SM-M546B, leitor Nitgen Hamster DX, planilha real e tag NFC real
(PAT-001). Os e-mails dos professores foram tarjados.

| Print | O que mostra |
|---|---|
| `01-digital-cadastro-e-identificacao.jpg` | Digital cadastrada pelo e-mail (array 1, posição 0, 2 amostras) e identificada. O cadastro passou pelo PIN da coordenação. |
| `02-busca-sequencial.jpg` | Ocupação dos 2 arrays de 300 e o modo da busca. |
| `03-digital-tres-professores.jpg` | 3 professores cadastrados, cada um no array com menos digitais. Uma digital repetida foi recusada, e os 3 foram identificados com busca de 1 a 2 ms. |
| `04-motivo-e-tempo-da-busca.jpg` | Por que a busca é sequencial: o SDK não cria a segunda instância fora de uma thread com `Looper`. |
| `05-terminal-reservar-etapa-projetor.jpg` | Fluxo do terminal: professor identificado pela digital, esperando a tag do projetor. |
| `06-terminal-devolucao-registrada.jpg` | Devolução só com a tag (PAT-001, reserva 1001). |
| `07-api-reservas-criar-e-consultar.jpg` | Reserva 1002 criada e consultada (ATIVA); professores sincronizados da planilha. |
| `08-api-reservas-criar.jpg` | Criação da reserva 1002 pela tela de teste. |
| `09-terminal-reservar-etapa-projetor.jpg` | Fluxo do terminal, segunda execução. |

A API REST do NFC tem as evidências próprias em
[`docs/nfc/evidencias/`](../../nfc/evidencias/).
