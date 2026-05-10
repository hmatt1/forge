# Stop the Forge Simulation Server
$port = 50051
$conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    Write-Host "Stopping Forge Server (PID: $($conn.OwningProcess))..." -ForegroundColor Yellow
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host "Server stopped." -ForegroundColor Green
} else {
    Write-Host "Forge Server is not running on port $port." -ForegroundColor Gray
}
