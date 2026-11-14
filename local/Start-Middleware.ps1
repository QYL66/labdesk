. (Join-Path $PSScriptRoot 'Local-Environment.ps1')

Initialize-LocalCredentials
Invoke-LabDeskCompose up -d --pull never --wait --wait-timeout 130

$existingTopics = Invoke-LabDeskCompose exec -T rmqbroker sh mqadmin topicList -n rmqnamesrv:9876
foreach ($topic in @('knowledge-document-chunk_topic','knowledge-base-cleanup_topic','message-feedback_topic')) {
    if ($topic -notin $existingTopics) {
        $created = Invoke-LabDeskCompose exec -T rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -b 127.0.0.1:10911 -t $topic
        if (($created -join "`n") -notmatch 'create topic .* success') {
            throw "RocketMQ topic initialization failed for $topic."
        }
    }
}

$s3Ready = $false
for ($attempt = 0; $attempt -lt 35; $attempt++) {
    & curl.exe --silent --fail --max-time 3 --output NUL 'http://127.0.0.1:9000/health'
    if ($LASTEXITCODE -eq 0) { $s3Ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $s3Ready) { throw 'LabDesk RustFS is not ready.' }

$dbArgs = @('exec','-T','postgres','psql','-X','-U','postgres','-d','labdesk','-v','ON_ERROR_STOP=1')
$schemaExists = Invoke-LabDeskCompose @dbArgs -Atc "SELECT CASE WHEN to_regclass('public.t_user') IS NULL THEN 0 ELSE 1 END;"
if (($schemaExists | Select-Object -Last 1) -eq '0') {
    $sqlDir = Join-Path $RepoDir 'resources\database'
    Invoke-LabDeskCompose cp (Join-Path $sqlDir 'schema_pg.sql') 'postgres:/tmp/labdesk-schema.sql'
    Invoke-LabDeskCompose cp (Join-Path $sqlDir 'init_data_pg.sql') 'postgres:/tmp/labdesk-initial-data.sql'
    Invoke-LabDeskCompose cp (Join-Path $sqlDir 'labdesk_seed.sql') 'postgres:/tmp/labdesk-seed.sql'
    Invoke-LabDeskCompose @dbArgs --single-transaction -f /tmp/labdesk-schema.sql -f /tmp/labdesk-initial-data.sql -f /tmp/labdesk-seed.sql | Out-Null
    Invoke-LabDeskCompose exec -T postgres rm /tmp/labdesk-schema.sql /tmp/labdesk-initial-data.sql /tmp/labdesk-seed.sql
}
Invoke-LabDeskCompose @dbArgs -c "SELECT extversion AS pgvector_version FROM pg_extension WHERE extname='vector';"
