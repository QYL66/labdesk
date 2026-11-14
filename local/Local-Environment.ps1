$ErrorActionPreference = 'Stop'
$SetupDir = $PSScriptRoot
$RepoDir = Split-Path -Parent $SetupDir
$LogsDir = Join-Path $SetupDir 'logs'
$DockerExe = $env:LABDESK_DOCKER_EXE
if (-not $DockerExe) {
    $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
    $DockerExe = if ($dockerCommand) { $dockerCommand.Source } else {
        'D:\To_Code\DockerDesktop\resources\bin\docker.exe'
    }
}
$EnvFile = Join-Path $SetupDir '.env.local'
$ComposeOptions = @('--env-file', $EnvFile, '-f', (Join-Path $SetupDir 'compose.yaml'))

function Test-LocalPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(300) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Get-ManagedService([string]$Name) {
    $recordPath = Join-Path $LogsDir "$Name.process.json"
    if (-not (Test-Path -LiteralPath $recordPath)) { return }
    $record = Get-Content -Raw -LiteralPath $recordPath | ConvertFrom-Json
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.started) {
        return $process
    }
}

function Invoke-LabDeskCompose {
    & $DockerExe compose @ComposeOptions @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit $LASTEXITCODE)." }
}

function Initialize-LocalCredentials {
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        @(
            'LABDESK_PG_PASSWORD=' + [guid]::NewGuid().ToString('N')
            'LABDESK_REDIS_PASSWORD=' + [guid]::NewGuid().ToString('N')
            'LABDESK_S3_KEY=labdesk-local'
            'LABDESK_S3_SECRET=' + [guid]::NewGuid().ToString('N')
            'LABDESK_MCP_TOKEN=' + [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
        ) | Set-Content -LiteralPath $EnvFile -Encoding ASCII
    }
    $credentials = @{}
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
        if ($line -match '^((?:LABDESK|RAGENT)_(?:PG_PASSWORD|REDIS_PASSWORD|S3_KEY|S3_SECRET)|LABDESK_MCP_TOKEN)=(.+)$') {
            $credentials[$Matches[1]] = $Matches[2]
        }
    }
    foreach ($suffix in @('PG_PASSWORD','REDIS_PASSWORD','S3_KEY','S3_SECRET')) {
        $current = $credentials["LABDESK_$suffix"]
        $legacy = $credentials["RAGENT_$suffix"]
        if (-not $current) { $current = $legacy }
        if (-not $current) { throw "Missing LABDESK_$suffix in the local credentials file." }
        [Environment]::SetEnvironmentVariable("LABDESK_$suffix", $current, 'Process')
    }
    if (-not $credentials['LABDESK_MCP_TOKEN']) { throw 'Missing LABDESK_MCP_TOKEN in the local credentials file.' }
    $env:LABDESK_MCP_TOKEN = $credentials['LABDESK_MCP_TOKEN']

    # Keep persisted S3 URLs and pending RocketMQ transactions usable on pre-migration installations.
    if ($credentials.ContainsKey('RAGENT_PG_PASSWORD')) {
        if (-not $env:LABDESK_KB_BUCKET) { $env:LABDESK_KB_BUCKET = 'ragent-sources' }
        if (-not $env:LABDESK_ASSET_BUCKET) { $env:LABDESK_ASSET_BUCKET = 'ragent-assets' }
        if (-not $env:LABDESK_MQ_PRODUCER_GROUP) { $env:LABDESK_MQ_PRODUCER_GROUP = 'ragent-producer_pg' }
    }
}
