param([int]$Port = 8081, [string]$Database = 'hotel_e2e_test')
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectPath
if ($Database -notmatch '^hotel_[a-z0-9_]+_test$') { throw 'Restart acceptance is restricted to an isolated test database.' }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'hotel-local-development-2026' }
$sql = @'
SELECT json_build_object(
 'orders',(SELECT count(*) FROM booking),
 'payments',(SELECT count(*) FROM money_entry),
 'nights',(SELECT count(*) FROM booking_night),
 'orderDigest',(SELECT md5(string_agg(id||':'||status||':'||amount||':'||version||':'||check_in||':'||check_out,',' ORDER BY id)) FROM booking),
 'moneyDigest',(SELECT md5(string_agg(id||':'||booking_id||':'||kind||':'||amount||':'||reason,',' ORDER BY id)) FROM money_entry),
 'nightDigest',(SELECT md5(string_agg(booking_id||':'||stay_date||':'||unit_price||':'||rooms||':'||allocated,',' ORDER BY booking_id,stay_date)) FROM booking_night));
'@
$before = docker compose exec -T db psql -v ON_ERROR_STOP=1 -U hotel -d $Database -tAc $sql
if ($LASTEXITCODE -ne 0) { throw 'Cannot capture database state.' }
& "$PSScriptRoot/stop.ps1" -Port $Port
& "$PSScriptRoot/start.ps1" -Port $Port -Database $Database -SkipBuild
$after = docker compose exec -T db psql -v ON_ERROR_STOP=1 -U hotel -d $Database -tAc $sql
if ($LASTEXITCODE -ne 0) { throw 'Cannot capture state after restart.' }
$passed = $before.Trim() -eq $after.Trim()
$record = [ordered]@{ generatedAt = [DateTime]::UtcNow.ToString('o'); database = $Database; action = 'Stop and restart Java process; retain PostgreSQL persistent volume'; passed = $passed; before = ($before | ConvertFrom-Json); after = ($after | ConvertFrom-Json) }
$record | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 '../evidence/restart-results.json'
if (-not $passed) { throw 'State changed during restart. Inspect pending timeout tasks and evidence.' }
Write-Output 'Order, payment and nightly inventory counts and content digests match after process restart.'
