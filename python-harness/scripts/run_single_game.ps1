# Single Game Lifecycle Script
# This script starts the Forge Server and Inference Server, runs exactly 1 game, and then shuts everything down.
# Useful for quick verification or debugging.

param (
    [string]$Deck1 = "C:\Users\Matt\IdeaProjects\decks\Deck_998.dck",
    [string]$Deck2 = "C:\Users\Matt\IdeaProjects\decks\Deck_999.dck"
)

$GameCount = 1

Write-Host "--- Starting Single Game Lifecycle ---" -ForegroundColor Cyan

# 0. Check for orphaned servers
Write-Host "Checking for port availability..." -ForegroundColor Gray
$ports = @(50051, 50052)
foreach ($port in $ports) {
    $portActive = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($portActive) {
        Write-Error "Port $port is already in use by PID $($portActive[0].OwningProcess). Please kill it before starting."
        exit 1
    }
}

# 1. Start Forge Simulation Server
Write-Host "[1/4] Starting Forge Server..." -ForegroundColor Yellow
$forgeProcess = Start-Process mvn.cmd -ArgumentList "exec:java -pl forge-server" -NoNewWindow -PassThru

# 2. Start Python Inference Server
Write-Host "[2/4] Starting Python Inference Server..." -ForegroundColor Yellow
$inferenceProcess = Start-Process python -ArgumentList "python-harness/inference_server.py" -NoNewWindow -PassThru

# 3. Wait for servers to initialize via port polling
Write-Host "Waiting for Forge Server (50051) to listen..." -ForegroundColor Gray
while (-not (Get-NetTCPConnection -LocalPort 50051 -ErrorAction SilentlyContinue)) {
    Start-Sleep -Seconds 1
}
Write-Host "Forge Server is UP." -ForegroundColor Gray

Write-Host "Waiting for Inference Server (50052) to listen..." -ForegroundColor Gray
while (-not (Get-NetTCPConnection -LocalPort 50052 -ErrorAction SilentlyContinue)) {
    Start-Sleep -Seconds 1
}
Write-Host "Inference Server is UP." -ForegroundColor Gray

# 4. Run the simulation harness
Write-Host "[3/4] Executing single game..." -ForegroundColor Yellow
$harnessProcess = Start-Process python -ArgumentList "python-harness/simulation_harness.py --count $GameCount --deck1 `"$Deck1`" --deck2 `"$Deck2`" --ai1 localhost:50052 --ai2 heuristic" -NoNewWindow -PassThru -Wait

# 5. Cleanup
Write-Host "[4/4] Shutting down servers..." -ForegroundColor Yellow

# Kill entire process trees to ensure Java exits
Stop-Process -Id $forgeProcess.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $inferenceProcess.Id -Force -ErrorAction SilentlyContinue

# Ensure the specific Java process on 50051 is gone
$javaConn = Get-NetTCPConnection -LocalPort 50051 -ErrorAction SilentlyContinue
if ($javaConn) {
    Stop-Process -Id $javaConn.OwningProcess -Force -ErrorAction SilentlyContinue
}

Write-Host "--- Single Game Complete ---" -ForegroundColor Green
