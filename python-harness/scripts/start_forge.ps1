# Start the Forge Simulation Server in the background
$port = 50051
$portActive = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
if ($portActive) {
    Write-Error "Forge Server (Port $port) is already in use by PID $($portActive[0].OwningProcess)."
    exit 1
}

Write-Host "Starting Forge Simulation Server..." -ForegroundColor Yellow
Start-Process mvn.cmd -ArgumentList "exec:java -pl forge-server" -NoNewWindow
Write-Host "Server process launched. Check status with check_status.ps1" -ForegroundColor Gray
