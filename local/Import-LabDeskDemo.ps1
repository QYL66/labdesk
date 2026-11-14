param([string]$Username = 'admin')

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:9090/api/ragent'

function Unwrap($result, [string]$action) {
    if ($result.code -ne '0') { throw "$action failed: $($result.message)" }
    return $result.data
}

function Call-Api([string]$method, [string]$path, $body = $null, [hashtable]$headers = @{}) {
    $options = @{ Uri = "$base$path"; Method = $method; Headers = $headers; TimeoutSec = 35 }
    if ($null -ne $body) {
        $options.ContentType = 'application/json; charset=utf-8'
        $options.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress -Depth 10))
    }
    Unwrap (Invoke-RestMethod @options) "$method $path"
}

function Upload-DemoDocument([System.IO.FileInfo]$file, [string]$kbId, [string]$token) {
    $client = [System.Net.Http.HttpClient]::new()
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(35)
        $client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $token) | Out-Null
        $content = [System.Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($file.FullName))
        $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('text/markdown')
        $form.Add($content, 'file', $file.Name)
        $form.Add([System.Net.Http.StringContent]::new('file'), 'sourceType')
        $form.Add([System.Net.Http.StringContent]::new('chunk'), 'processMode')
        $response = $client.PostAsync("$base/knowledge-base/$kbId/docs/upload", $form).GetAwaiter().GetResult()
        try {
            $response.EnsureSuccessStatusCode() | Out-Null
            $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            Unwrap ($raw | ConvertFrom-Json) "Upload $($file.Name)"
        } finally { $response.Dispose() }
    } finally {
        $form.Dispose()
        $client.Dispose()
    }
}

Add-Type -AssemblyName System.Net.Http
$secure = Read-Host 'LabDesk administrator password' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $login = Call-Api 'Post' '/auth/login' @{ username = $Username; password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    $secure.Dispose()
}
if ($login.role -ne 'admin' -or -not $login.token) { throw 'An administrator account is required.' }
$headers = @{ Authorization = $login.token }
$collection = 'labdesk-demo'
$name = 'LabDeskDemo'
$page = Call-Api 'Get' ('/knowledge-base?size=100&name=' + [uri]::EscapeDataString($name)) $null $headers
$kb = @($page.records | Where-Object { $_.collectionName -eq $collection } | Select-Object -First 1)
$kbId = if ($kb.Count) { $kb[0].id } else {
    Call-Api 'Post' '/knowledge-base' @{ name = $name; collectionName = $collection; embeddingModel = 'qwen-emb-8b' } $headers
}
$page = Call-Api 'Get' "/knowledge-base/$kbId/docs?size=100" $null $headers
$demoDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'resources\labdesk-demo'
$pending = @()

foreach ($file in Get-ChildItem -LiteralPath $demoDir -Filter '*.md' -File) {
    $doc = $page.records | Where-Object { $_.docName -ceq $file.Name } | Select-Object -First 1
    if (-not $doc) {
        $doc = Upload-DemoDocument $file $kbId $login.token
    }
    if ($doc.status -eq 'success' -and $doc.chunkCount -gt 0) {
        Write-Host "Already indexed: $($file.Name)"
        continue
    }
    if ($doc.status -ne 'running') {
        Call-Api 'Post' "/knowledge-base/docs/$($doc.id)/chunk" $null $headers | Out-Null
    }
    $pending += $doc.id
}

$deadline = (Get-Date).AddMinutes(4)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
    $pending = @(foreach ($id in $pending) {
        $doc = Call-Api 'Get' "/knowledge-base/docs/$id" $null $headers
        if ($doc.status -eq 'failed') { throw "Indexing failed for document $id. See the document's chunking log." }
        if ($doc.status -eq 'success' -and $doc.chunkCount -gt 0) {
            Write-Host "Indexed: $($doc.docName) ($($doc.chunkCount) chunks)"
        } else { $id }
    })
    if ($pending.Count) { Start-Sleep -Seconds 2 }
}
if ($pending.Count) { throw 'Indexing is still running. Check the console or run this script again later.' }
Write-Host 'LabDesk demo documents are ready.'
