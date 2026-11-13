$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'hotel-local-development-2026' }
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed.' }
[string]$found = docker compose exec -T db psql -U hotel -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='hotel_test'"
if ($found.Trim() -ne '1') { docker compose exec -T db psql -U hotel -d postgres -c 'CREATE DATABASE hotel_test' }
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/hotel_test'
$env:HOTEL_DEMO = 'false'
$env:JOBS_ENABLED = 'false'
& .\mvnw.cmd -B -Pintegration verify
if ($LASTEXITCODE -ne 0) { throw 'Integration verification failed.' }
Write-Output 'Unit, PostgreSQL integration and code-style checks passed. See target/*-reports.'
