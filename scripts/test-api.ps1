[CmdletBinding()]
param(
    [string]$Ip = "192.168.0.104",
    [int]$Port = 8787,
    [string]$TokenPath = ".\appnfc-test-token.txt",
    [switch]$ReadTag,
    [switch]$ReadQr,
    [switch]$Logs,
    [int]$Limit = 20,
    [int]$Offset = 0
)

$ErrorActionPreference = "Stop"
$baseUrl = "http://${Ip}:${Port}"

if ($Logs -and ($Limit -lt 1 -or $Limit -gt 50 -or $Offset -lt 0 -or $Offset -gt 10000)) {
    throw "Para -Logs, use -Limit entre 1 e 50 e -Offset entre 0 e 10000."
}

if (-not (Test-Path -LiteralPath $TokenPath)) {
    throw "Arquivo de token nao encontrado: $TokenPath"
}

$token = (Get-Content -Raw -LiteralPath $TokenPath).Trim()
if ([string]::IsNullOrWhiteSpace($token) -or $token -eq "COLE_O_TOKEN_AQUI") {
    throw "Preencha o token em $TokenPath antes de executar o teste."
}

$headers = @{
    Authorization = "Bearer $token"
    "X-Correlation-Id" = [guid]::NewGuid().ToString()
}

try {
    Write-Host "Testando conectividade com $baseUrl..."
    $connection = Test-NetConnection -ComputerName $Ip -Port $Port -InformationLevel Quiet
    if (-not $connection) {
        throw "Nao foi possivel conectar ao celular em ${Ip}:${Port}."
    }
    Write-Host "TCP OK"

    Write-Host "Testando autenticacao em /health..."
    $health = Invoke-RestMethod `
        -Uri "$baseUrl/health" `
        -Method Get `
        -Headers $headers `
        -TimeoutSec 10
    $health | ConvertTo-Json -Depth 5

    if ($ReadTag) {
        Write-Host "Aproxime a tag NFC do celular agora..."
        $result = Invoke-RestMethod `
            -Uri "$baseUrl/v1/nfc/read" `
            -Method Post `
            -Headers $headers `
            -ContentType "application/json" `
            -Body "{}" `
            -TimeoutSec 40
        $result | ConvertTo-Json -Depth 8
        if ($result.texts) { Write-Host "Textos NDEF:"; $result.texts | ForEach-Object { Write-Host "- $_" } }
        if ($result.urls) { Write-Host "URLs NDEF:"; $result.urls | ForEach-Object { Write-Host "- $_" } }
    }

    if ($ReadQr) {
        Write-Host "Aponte a camera do celular para um QR Code agora..."
        $qrResult = Invoke-RestMethod `
            -Uri "$baseUrl/v1/qr/read" `
            -Method Post `
            -Headers $headers `
            -ContentType "application/json" `
            -Body "{}" `
            -TimeoutSec 40
        $qrResult | ConvertTo-Json -Depth 8
    }

    if ($Logs) {
        Write-Host "Consultando logs de auditoria (limit=$Limit, offset=$Offset)..."
        $logsResult = Invoke-RestMethod `
            -Uri "$baseUrl/v1/audit/logs?limit=$Limit&offset=$Offset" `
            -Method Get `
            -Headers $headers `
            -TimeoutSec 10
        $logsResult | ConvertTo-Json -Depth 8
    }

    if (-not $ReadTag -and -not $ReadQr -and -not $Logs) {
        Write-Host "Health OK. Para testar a tag use -ReadTag; para QR Code use -ReadQr; para logs use -Logs."
    }
}
catch {
    Write-Error $_.Exception.Message
    if ($_.ErrorDetails.Message) {
        Write-Error $_.ErrorDetails.Message
    }
    exit 1
}
finally {
    $token = $null
    $headers = $null
}
