$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'hotel-local-development-2026' }
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed.' }
$exists = docker compose exec -T db psql -v ON_ERROR_STOP=1 -U hotel -d postgres -tAc "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname='hotel_test')"
if ($LASTEXITCODE -ne 0 -or $exists -notin @('t', 'f')) { throw 'Cannot check the integration database.' }
if ($exists -eq 'f') {
  docker compose exec -T db psql -v ON_ERROR_STOP=1 -U hotel -d postgres -c 'CREATE DATABASE hotel_test'
  if ($LASTEXITCODE -ne 0) { throw 'Cannot create the integration database.' }
}
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/hotel_test'
$env:HOTEL_DEMO = 'false'
$env:JOBS_ENABLED = 'false'
& .\mvnw.cmd -B -Pintegration verify
if ($LASTEXITCODE -ne 0) { throw 'Integration verification failed.' }
Write-Output 'Unit, PostgreSQL integration and code-style checks passed. See target/*-reports.'
