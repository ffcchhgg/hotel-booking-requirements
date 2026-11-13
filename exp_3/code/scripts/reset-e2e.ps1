param([string]$Database = 'hotel_e2e_test')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if ($Database -notmatch '^hotel_[a-z0-9_]+_test$') { throw 'Refusing reset outside an explicitly named isolated test database.' }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'hotel-local-development-2026' }
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U hotel -d $Database -c 'TRUNCATE hotel,app_user RESTART IDENTITY CASCADE'
if ($LASTEXITCODE -ne 0) { throw 'Test fixture reset failed.' }
Write-Output "Reset fixtures in $Database only. Restart its demo server to seed fresh data."
