param([switch]$McpOnly, [switch]$NoBrowser)

. (Join-Path $PSScriptRoot 'Local-Environment.ps1')

function Read-LocalSecret([string]$Name, [string]$Prompt) {
    if ([Environment]::GetEnvironmentVariable($Name, 'Process')) { return }
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer).Trim()
        if (-not $key) { throw 'A value is required.' }
        [Environment]::SetEnvironmentVariable($Name, $key, 'Process')
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}

function Start-ManagedService([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory, [int]$Port, [string]$HttpPath = '/') {
    $recordPath = Join-Path $LogsDir "$Name.process.json"
    $tracked = Get-ManagedService $Name
    $stdout = Join-Path $LogsDir "$Name.log"
    $stderr = Join-Path $LogsDir "$Name.err"
    $process = $tracked
    if (-not $process) {
        if (Test-LocalPort $Port) { throw "Port $Port is already in use. See $Name." }
        $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        @{ id = $process.Id; started = $process.StartTime.ToUniversalTime().Ticks.ToString() } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $recordPath -Encoding ASCII
    }
    $url = "http://127.0.0.1:$Port$HttpPath"
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        $process.Refresh()
        if ($process.HasExited) { break }
        if (Test-LocalPort $Port) {
            $status = & curl.exe --silent --max-time 2 --output NUL --write-out '%{http_code}' $url
            if ($LASTEXITCODE -eq 0 -and $status -ne '000') {
                Write-Host "$Name is listening on $Port."
                return
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not start. See $stdout and $stderr. Stop LabDesk before retrying."
}

$java = (Get-Command java.exe).Source
$node = (Get-Command node.exe).Source
$mcpJar = Join-Path $RepoDir 'mcp-server\target\mcp-server-0.0.1-SNAPSHOT.jar'
$appJar = Join-Path $RepoDir 'bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar'
$frontend = Join-Path $RepoDir 'frontend'
$vite = Join-Path $frontend 'node_modules\vite\bin\vite.js'
$viteConfig = Join-Path $SetupDir 'vite-local.mjs'
foreach ($artifact in @($mcpJar, $appJar, $vite)) {
    if (-not (Test-Path -LiteralPath $artifact)) { throw "Missing build artifact: $artifact" }
}
New-Item -ItemType Directory -Path $LogsDir -Force | Out-Null

& $DockerExe info --format '{{.OSType}}' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop and wait for the Linux engine, then run this script again.' }
& (Join-Path $SetupDir 'Start-Middleware.ps1')
Initialize-LocalCredentials

Start-ManagedService 'mcp-server' $java @('-Dfile.encoding=UTF-8','-Xmx512m','-jar',('"' + $mcpJar + '"'),'--spring.profiles.active=labdesk') $RepoDir 9099 '/mcp'
$status = & curl.exe --silent --max-time 3 --output NUL --write-out '%{http_code}' 'http://127.0.0.1:9099/mcp'
if ($LASTEXITCODE -ne 0 -or $status -ne '401') { throw 'The LabDesk MCP access filter did not respond as expected.' }
if ($McpOnly) { return }

if (-not (Get-ManagedService 'labdesk')) {
    try {
        $needsPassword = Invoke-LabDeskCompose exec -T postgres psql -X -U postgres -d labdesk -Atc "SELECT CASE WHEN password ~ '^pbkdf2-sha256-v1:[a-f0-9]{32}:[a-f0-9]{64}$' THEN 0 ELSE 1 END FROM t_user WHERE username = 'admin' AND deleted = 0;"
        if (($needsPassword | Select-Object -Last 1) -ne '0') {
            Read-LocalSecret 'LABDESK_ADMIN_PASSWORD' 'Set the LabDesk admin password (at least 12 characters)'
        }
        Read-LocalSecret 'DEEPSEEK_API_KEY' 'DeepSeek API key'
        Read-LocalSecret 'BAILIAN_API_KEY' 'Bailian API key'
        $config = '"--spring.config.additional-location=' + (Join-Path $SetupDir 'application-local.yaml') + '"'
        Start-ManagedService 'labdesk' $java @('-Dfile.encoding=UTF-8','-Xmx1536m','-jar',('"' + $appJar + '"'),$config) $RepoDir 9090 '/api/ragent'
    } finally {
        [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', $null, 'Process')
        [Environment]::SetEnvironmentVariable('BAILIAN_API_KEY', $null, 'Process')
        [Environment]::SetEnvironmentVariable('LABDESK_ADMIN_PASSWORD', $null, 'Process')
    }
}
Start-ManagedService 'frontend' $node @(('"' + $vite + '"'),'--config',('"' + $viteConfig + '"')) $frontend 15173

$url = 'http://127.0.0.1:15173/'
Write-Host "LabDesk is ready: $url"
if (-not $NoBrowser) { Start-Process $url }
