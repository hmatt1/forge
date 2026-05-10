# Stop the Python Inference Server
$port = 50052
$conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    Write-Host "Stopping Inference Server (PID: $($conn.OwningProcess))..." -ForegroundColor Yellow
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host "Server stopped." -ForegroundColor Green
} else {
    Write-Host "Inference Server is not running on port $port." -ForegroundColor Gray
}
