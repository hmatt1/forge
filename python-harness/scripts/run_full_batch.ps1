# Full Lifecycle Benchmark Script
# This script starts the Forge Server and Inference Server, runs 100 games, and then shuts everything down.

param (
    [int]$GameCount = 10,
    [string]$Deck1 = "C:\Users\Matt\IdeaProjects\decks\Deck_998.dck",
    [string]$Deck2 = "C:\Users\Matt\IdeaProjects\decks\Deck_999.dck"
)

Write-Host "--- Starting Full Benchmark Lifecycle ($GameCount games) ---" -ForegroundColor Cyan

# 1. Start Forge Simulation Server
Write-Host "[1/4] Starting Forge Server..." -ForegroundColor Yellow
$forgeProcess = Start-Process mvn -ArgumentList "exec:java -pl forge-server" -NoNewWindow -PassThru

# 2. Start Python Inference Server
Write-Host "[2/4] Starting Python Inference Server..." -ForegroundColor Yellow
$inferenceProcess = Start-Process python -ArgumentList "python-harness/inference_server.py" -NoNewWindow -PassThru

# 3. Wait for servers to initialize (Simple wait, could be improved with port polling)
Write-Host "Waiting 15 seconds for engines to warm up..." -ForegroundColor Gray
Start-Sleep -Seconds 15

# 4. Run the simulation harness
Write-Host "[3/4] Executing benchmark games..." -ForegroundColor Yellow
python python-harness/simulation_harness.py --count $GameCount --deck1 $Deck1 --deck2 $Deck2 --ai1 localhost:50052 --ai2 heuristic

# 5. Cleanup
Write-Host "[4/4] Shutting down servers..." -ForegroundColor Yellow

# Kill processes and their children
Stop-Process -Id $forgeProcess.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $inferenceProcess.Id -Force -ErrorAction SilentlyContinue

# Ensure no orphaned java/python processes from these specific commands
# (Optional: might be aggressive if user has other java/python running)
# Get-Process java | Where-Object {$_.CommandLine -like "*forge-server*"} | Stop-Process -Force

Write-Host "--- Benchmark Complete ---" -ForegroundColor Green
