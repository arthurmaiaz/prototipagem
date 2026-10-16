#!/usr/bin/env bash
# Versão em bash do test-api.ps1 (script do professor), para Linux e macOS.
# Faz as mesmas chamadas, com os mesmos parâmetros e o mesmo arquivo de token.
#
#   ./scripts/test-api.sh -Ip 192.168.0.104              # só /health
#   ./scripts/test-api.sh -Ip 192.168.0.104 -ReadTag     # encoste a tag em até 30 s
#   ./scripts/test-api.sh -Ip 192.168.0.104 -Logs -Limit 10
#
# Os parâmetros também aceitam a forma --ip, --read-tag, --logs etc.
# Precisa de curl; usa jq para formatar o JSON, se estiver instalado.

set -euo pipefail

IP="192.168.0.104"
PORT=8787
TOKEN_PATH="./appnfc-test-token.txt"
READ_TAG=0
READ_QR=0
LOGS=0
LIMIT=20
OFFSET=0

falha() {
    echo "Erro: $*" >&2
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        -Ip|--ip)                 IP="${2:?falta o valor de $1}"; shift ;;
        -Port|--port)             PORT="${2:?falta o valor de $1}"; shift ;;
        -TokenPath|--token-path)  TOKEN_PATH="${2:?falta o valor de $1}"; shift ;;
        -ReadTag|--read-tag)      READ_TAG=1 ;;
        -ReadQr|--read-qr)        READ_QR=1 ;;
        -Logs|--logs)             LOGS=1 ;;
        -Limit|--limit)           LIMIT="${2:?falta o valor de $1}"; shift ;;
        -Offset|--offset)         OFFSET="${2:?falta o valor de $1}"; shift ;;
        -h|--help)                sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                        falha "parâmetro desconhecido: $1 (use --help)" ;;
    esac
    shift
done

command -v curl >/dev/null || falha "o curl não está instalado."

BASE_URL="http://${IP}:${PORT}"

if [ "$LOGS" -eq 1 ]; then
    [[ "$LIMIT" =~ ^[0-9]+$ && "$OFFSET" =~ ^[0-9]+$ ]] \
        && [ "$LIMIT" -ge 1 ] && [ "$LIMIT" -le 50 ] && [ "$OFFSET" -le 10000 ] \
        || falha "para -Logs, use -Limit entre 1 e 50 e -Offset entre 0 e 10000."
fi

[ -f "$TOKEN_PATH" ] || falha "arquivo de token não encontrado: $TOKEN_PATH"
TOKEN="$(tr -d '[:space:]' < "$TOKEN_PATH")"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "COLE_O_TOKEN_AQUI" ]; then
    falha "preencha o token em $TOKEN_PATH antes de executar o teste."
fi

if command -v uuidgen >/dev/null; then
    CORRELATION_ID="$(uuidgen)"
else
    CORRELATION_ID="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N)"
fi

formatar() {
    if command -v jq >/dev/null; then jq . 2>/dev/null || cat; else cat; echo; fi
}

# chamar MÉTODO CAMINHO TIMEOUT: imprime o corpo; falha se o HTTP não for 2xx.
chamar() {
    local metodo="$1" caminho="$2" timeout="$3" corpo status
    local args=(-sS --max-time "$timeout" -X "$metodo"
                -H "Authorization: Bearer $TOKEN"
                -H "X-Correlation-Id: $CORRELATION_ID"
                -w $'\n%{http_code}')
    if [ "$metodo" = "POST" ]; then
        args+=(-H "Content-Type: application/json" -d '{}')
    fi
    corpo="$(curl "${args[@]}" "$BASE_URL$caminho")" || falha "a chamada a $caminho não respondeu."
    status="${corpo##*$'\n'}"
    corpo="${corpo%$'\n'*}"
    printf '%s' "$corpo" | formatar
    [[ "$status" == 2* ]] || falha "$caminho respondeu HTTP $status."
    RESPOSTA="$corpo"
}

echo "Testando conectividade com $BASE_URL..."
if ! timeout 5 bash -c "exec 3<>/dev/tcp/$IP/$PORT" 2>/dev/null; then
    falha "não foi possível conectar ao celular em ${IP}:${PORT}."
fi
echo "TCP OK"

echo "Testando autenticação em /health..."
chamar GET /health 10

if [ "$READ_TAG" -eq 1 ]; then
    echo "Aproxime a tag NFC do celular agora..."
    chamar POST /v1/nfc/read 40
    if command -v jq >/dev/null; then
        if [ "$(jq '.texts // [] | length' <<< "$RESPOSTA")" -gt 0 ]; then
            echo "Textos NDEF:"; jq -r '.texts[] | "- " + .' <<< "$RESPOSTA"
        fi
        if [ "$(jq '.urls // [] | length' <<< "$RESPOSTA")" -gt 0 ]; then
            echo "URLs NDEF:"; jq -r '.urls[] | "- " + .' <<< "$RESPOSTA"
        fi
    fi
fi

if [ "$READ_QR" -eq 1 ]; then
    echo "Aponte a câmera do celular para um QR Code agora..."
    chamar POST /v1/qr/read 40
fi

if [ "$LOGS" -eq 1 ]; then
    echo "Consultando logs de auditoria (limit=$LIMIT, offset=$OFFSET)..."
    chamar GET "/v1/audit/logs?limit=$LIMIT&offset=$OFFSET" 10
fi

if [ "$READ_TAG" -eq 0 ] && [ "$READ_QR" -eq 0 ] && [ "$LOGS" -eq 0 ]; then
    echo "Health OK. Para testar a tag use -ReadTag; para QR Code use -ReadQr; para logs use -Logs."
fi
