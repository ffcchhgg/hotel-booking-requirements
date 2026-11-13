param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$pidPath = Join-Path $projectPath ".runtime/server-$Port.pid"
if (-not (Test-Path -LiteralPath $pidPath)) { Write-Output 'No recorded process.'; exit 0 }
$appProcessId = [int](Get-Content -LiteralPath $pidPath)
$appProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$appProcessId"
$expectedJar = Join-Path $projectPath ".runtime/hotel-$Port.jar"
if ($appProcess -and $appProcess.Name -eq 'java.exe' -and $appProcess.CommandLine.Contains($expectedJar)) {
  $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$appProcessId" | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine.Contains($expectedJar) }
  foreach ($child in $children) { Stop-Process -Id $child.ProcessId -Force }
  Stop-Process -Id $appProcessId -Force
  Write-Output "Stopped project server PID $appProcessId. PostgreSQL data is retained."
} elseif ($appProcess) { throw 'PID now belongs to a different process; refusing to stop it.' }
Remove-Item -LiteralPath $pidPath
