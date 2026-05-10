# Start the Python Inference Server in the background
$port = 50052
$portActive = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
if ($portActive) {
    Write-Error "Inference Server (Port $port) is already in use by PID $($portActive[0].OwningProcess)."
    exit 1
}

Write-Host "Starting Python Inference Server..." -ForegroundColor Yellow
Start-Process python -ArgumentList "python-harness/inference_server.py" -NoNewWindow
Write-Host "Inference process launched. Check status with check_status.ps1" -ForegroundColor Gray
