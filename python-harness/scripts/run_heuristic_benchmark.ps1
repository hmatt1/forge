# Heuristic Baseline Benchmark Script
# This script starts the Forge Server, runs 100 games of Heuristic vs Heuristic AI, and then shuts down.

param (
    [int]$GameCount = 100,
    [string]$Deck1 = "C:\Users\Matt\IdeaProjects\decks\Deck_998.dck",
    [string]$Deck2 = "C:\Users\Matt\IdeaProjects\decks\Deck_999.dck"
)

Write-Host "--- Starting Heuristic Baseline Lifecycle ($GameCount games) ---" -ForegroundColor Cyan

# 1. Start Forge Simulation Server
Write-Host "[1/3] Starting Forge Server..." -ForegroundColor Yellow
$forgeProcess = Start-Process mvn.cmd -ArgumentList "exec:java -pl forge-server" -NoNewWindow -PassThru

# 2. Wait for server to initialize
Write-Host "Waiting 10 seconds for engine to warm up..." -ForegroundColor Gray
Start-Sleep -Seconds 10

# 3. Run the simulation harness (Heuristic vs Heuristic)
Write-Host "[2/3] Executing heuristic benchmark games..." -ForegroundColor Yellow
$harnessProcess = Start-Process python -ArgumentList "python-harness/simulation_harness.py --count $GameCount --deck1 `"$Deck1`" --deck2 `"$Deck2`" --ai1 heuristic --ai2 heuristic" -NoNewWindow -PassThru -Wait

# 4. Cleanup
Write-Host "[3/3] Shutting down Forge server..." -ForegroundColor Yellow
Stop-Process -Id $forgeProcess.Id -Force -ErrorAction SilentlyContinue

Write-Host "--- Benchmark Complete ---" -ForegroundColor Green
