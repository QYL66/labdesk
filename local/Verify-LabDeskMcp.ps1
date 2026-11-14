. (Join-Path $PSScriptRoot 'Local-Environment.ps1')

if (-not (Test-Path -LiteralPath $EnvFile) -or -not (Test-LocalPort 9099)) {
    throw 'Start the LabDesk MCP server before running this check.'
}
Initialize-LocalCredentials
$endpoint = 'http://127.0.0.1:9099/mcp'
$headers = @{ Accept = 'application/json, text/event-stream' }
$init = @{
    jsonrpc = '2.0'; id = 1; method = 'initialize'
    params = @{ protocolVersion = '2025-03-26'; capabilities = @{}; clientInfo = @{ name = 'labdesk-check'; version = '1.0' } }
} | ConvertTo-Json -Depth 6 -Compress

function Assert-LabDesk($condition, [string]$description) {
    if (-not $condition) { throw "FAILED: $description" }
    Write-Host "PASS: $description"
}

function Convert-McpResponse($response) {
    $line = @($response.Content -split "`r?`n" | Where-Object { $_.StartsWith('data:') } | Select-Object -Last 1)
    $raw = if ($line.Count) { $line[0].Substring(5).Trim() } else { $response.Content }
    ($raw | ConvertFrom-Json).result
}

function Send-Mcp([string]$method, [int]$id, $parameters, [hashtable]$requestHeaders = $headers) {
    $body = @{ jsonrpc = '2.0'; method = $method }
    if ($id) { $body.id = $id }
    if ($null -ne $parameters) { $body.params = $parameters }
    Invoke-WebRequest -UseBasicParsing -Uri $endpoint -Method Post -Headers $requestHeaders -ContentType 'application/json' `
        -Body ($body | ConvertTo-Json -Depth 7 -Compress) -TimeoutSec 12
}

try {
    Invoke-WebRequest -UseBasicParsing -Uri $endpoint -Method Post -Headers $headers -ContentType 'application/json' -Body $init -TimeoutSec 12 | Out-Null
    throw 'The MCP endpoint accepted a request without a shared token.'
} catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401) { throw }
    Write-Host 'PASS: missing service token returns 401'
}
$headers['X-LabDesk-Mcp-Token'] = $env:LABDESK_MCP_TOKEN
$session = Invoke-WebRequest -UseBasicParsing -Uri $endpoint -Method Post -Headers $headers -ContentType 'application/json' -Body $init -TimeoutSec 12
$headers['Mcp-Session-Id'] = @($session.Headers['Mcp-Session-Id'])[0]
$headers['MCP-Protocol-Version'] = (Convert-McpResponse $session).protocolVersion
Send-Mcp 'notifications/initialized' 0 $null | Out-Null
$catalog = Convert-McpResponse (Send-Mcp 'tools/list' 2 @{})
$expected = 'current_date','lab_book_equipment','lab_list_availability','lab_list_my_bookings'
Assert-LabDesk (@(Compare-Object $expected @($catalog.tools.name | Sort-Object)).Count -eq 0) 'LabDesk exposes only four expected MCP tools'
Assert-LabDesk (($catalog.tools | Where-Object name -eq 'lab_book_equipment').annotations.readOnlyHint -eq $false) 'booking is a write tool'

function Call-Tool([string]$name, [hashtable]$toolArguments, [string]$user = '') {
    $parameters = @{ name = $name; arguments = $toolArguments }
    if ($user) { $parameters['_meta'] = @{ 'com.nageoffer.ragent/userId' = $user } }
    Convert-McpResponse (Send-Mcp 'tools/call' 3 $parameters)
}

$today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'China Standard Time').Date
$date = $today.AddDays(3).ToString('yyyy-MM-dd')
$user = 'labdesk-check-' + [guid]::NewGuid().ToString('N')
$otherUser = 'labdesk-check-' + [guid]::NewGuid().ToString('N')
$available = Call-Tool 'lab_list_availability' @{ date = $date; equipmentCode = 'SCOPE-01' }
$startTime = [regex]::Match($available.content[0].text, '\b(?:09|1[0-7]):00\b').Value
Assert-LabDesk ($available.content[0].text -match 'SCOPE-01') 'availability lists demo equipment'
Assert-LabDesk ($startTime -ne '') 'demo equipment has a slot available for the test'
$slot = @{ equipmentCode = 'SCOPE-01'; date = $date; startTime = $startTime }
$cleanup = "DELETE FROM labdesk_booking WHERE user_id IN ('$user', '$otherUser');"

try {
    Assert-LabDesk ((Call-Tool 'lab_book_equipment' $slot).isError) 'booking without user metadata is rejected'
    Assert-LabDesk ((Call-Tool 'lab_book_equipment' @{ equipmentCode = 'SCOPE-01'; date = $date; startTime = '17:30' } $user).isError) 'non-hourly slot is rejected'
    Assert-LabDesk ((Call-Tool 'lab_book_equipment' @{ equipmentCode = 'SCOPE-01'; date = $today.AddDays(-1).ToString('yyyy-MM-dd'); startTime = '17:00' } $user).isError) 'past slot is rejected'

    $jobs = @()
    try {
        $gate = [DateTime]::UtcNow.AddSeconds(5)
        foreach ($who in @($user, $otherUser)) {
            $body = @{ jsonrpc = '2.0'; id = $who; method = 'tools/call'; params = @{
                name = 'lab_book_equipment'; arguments = $slot; '_meta' = @{ 'com.nageoffer.ragent/userId' = $who }
            } } | ConvertTo-Json -Depth 8 -Compress
            $jobs += Start-Job -ArgumentList $endpoint,$headers,$body,$who,$gate -ScriptBlock {
                param($url,$h,$request,$caller,$start)
                while ([DateTime]::UtcNow -lt $start) { Start-Sleep -Milliseconds 20 }
                $reply = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $h -ContentType 'application/json' -Method Post -Body $request -TimeoutSec 12
                $data = @($reply.Content -split "`r?`n" | Where-Object { $_.StartsWith('data:') } | Select-Object -Last 1)
                $payload = if ($data.Count) { $data[0].Substring(5).Trim() } else { $reply.Content }
                [pscustomobject]@{ User = $caller; Result = ($payload | ConvertFrom-Json).result }
            }
        }
        $results = @($jobs | Receive-Job -Wait -ErrorAction Stop)
    } finally {
        if ($jobs.Count) { $jobs | Remove-Job -Force }
    }
    $winners = @($results | Where-Object { $_.Result -and $_.Result.isError -eq $false })
    $losers = @($results | Where-Object { $_.Result.isError -eq $true })
    Assert-LabDesk ($winners.Count -eq 1 -and $losers.Count -eq 1) 'concurrent claims produce one booking and one conflict'
    $winner = $winners[0]
    $bookingId = [regex]::Match($winner.Result.content[0].text, 'LD-\d+').Value
    Assert-LabDesk ($bookingId -ne '' -and (Call-Tool 'lab_book_equipment' $slot $winner.User).content[0].text.Contains($bookingId)) 'retry returns the original booking number'
    $updated = Call-Tool 'lab_list_availability' @{ date = $date; equipmentCode = $slot.equipmentCode }
    Assert-LabDesk (-not $updated.isError -and -not $updated.content[0].text.Contains($startTime)) 'the booked slot is no longer available'
    Assert-LabDesk ((Call-Tool 'lab_list_my_bookings' @{} $winner.User).content[0].text.Contains($bookingId)) 'the winner can see their booking'
    Assert-LabDesk (-not (Call-Tool 'lab_list_my_bookings' @{} $losers[0].User).content[0].text.Contains($bookingId)) 'another account cannot list the winner booking'
    $count = Invoke-LabDeskCompose exec -T postgres psql -X -U postgres -d labdesk -Atc "SELECT count(*) FROM labdesk_booking WHERE user_id IN ('$user', '$otherUser');"
    Assert-LabDesk (($count | Select-Object -Last 1) -eq '1') 'exactly one row is persisted in PostgreSQL'
} finally {
    Invoke-LabDeskCompose exec -T postgres psql -X -U postgres -d labdesk -v ON_ERROR_STOP=1 -c $cleanup | Out-Null
}
