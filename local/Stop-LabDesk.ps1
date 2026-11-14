. (Join-Path $PSScriptRoot 'Local-Environment.ps1')

foreach ($name in @('frontend','labdesk','mcp-server')) {
    $process = Get-ManagedService $name
    if ($process) {
        Stop-Process -Id $process.Id
        Wait-Process -Id $process.Id -Timeout 15 -ErrorAction SilentlyContinue
        Write-Host "Stopped $name."
    }
    $record = Join-Path $LogsDir "$name.process.json"
    if (Test-Path -LiteralPath $record) { Remove-Item -LiteralPath $record }
}
if (Test-Path -LiteralPath $EnvFile) {
    Initialize-LocalCredentials
    Invoke-LabDeskCompose stop
}
