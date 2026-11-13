param([int]$Port = 8080, [string]$Database = 'hotel', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectPath
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'hotel-local-development-2026' }
if ($Database -notmatch '^hotel(_[a-z0-9]+)*$') { throw 'Database must be a hotel-prefixed local database name.' }
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed. Start Docker Desktop first.' }
if ($Database -ne 'hotel') {
  [string]$found = docker compose exec -T db psql -U hotel -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$Database'"
  if ($found.Trim() -ne '1') { docker compose exec -T db psql -U hotel -d postgres -c "CREATE DATABASE $Database" }
}
if (-not $SkipBuild) {
  & .\mvnw.cmd -B verify
  if ($LASTEXITCODE -ne 0) { throw 'Build or unit checks failed.' }
}
New-Item -ItemType Directory -Path '.runtime' -Force | Out-Null
$listen = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listen) { throw "Port $Port is already in use. Stop the existing service or choose -Port." }
$env:DB_URL = "jdbc:postgresql://127.0.0.1:55432/$Database"
$env:HOTEL_DEMO = 'true'
$env:PORT = [string]$Port
$jarPath = Join-Path $projectPath ".runtime/hotel-$Port.jar"
Copy-Item -LiteralPath 'target/hotel-booking.jar' -Destination $jarPath -Force
$javaSettings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
$javaDirectory = [regex]::Match($javaSettings, 'java\.home\s*=\s*([^\r\n]+)').Groups[1].Value.Trim()
if (-not $javaDirectory) { throw 'Cannot resolve the actual JDK executable.' }
$javaPath = Join-Path $javaDirectory 'bin/java.exe'
$process = Start-Process -FilePath $javaPath -ArgumentList '-jar', ('"' + $jarPath + '"') -WorkingDirectory $projectPath -WindowStyle Hidden -RedirectStandardOutput ".runtime/server-$Port.log" -RedirectStandardError ".runtime/server-$Port-error.log" -PassThru
$process.Id | Set-Content ".runtime/server-$Port.pid"
for ($attempt = 0; $attempt -lt 45; $attempt++) {
  Start-Sleep -Seconds 1
  try {
    $health = Invoke-RestMethod "http://127.0.0.1:$Port/api/health"
    $hotels = Invoke-RestMethod "http://127.0.0.1:$Port/api/catalog/hotels"
    if ($health.status -eq 'UP' -and $hotels.Count -gt 0) {
      Write-Output "Ready: http://127.0.0.1:$Port | database=$Database | PID=$($process.Id)"
      exit 0
    }
  } catch { if ($process.HasExited) { throw "Server exited. See .runtime/server-$Port.log" } }
}
throw "Server did not become ready within 45 seconds. Inspect .runtime/server-$Port.log"
