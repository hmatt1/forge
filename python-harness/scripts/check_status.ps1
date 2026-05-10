# AI Simulation System Status Monitor
# Checks if servers are listening and if benchmark scripts are active.

function Get-PortStatus($port, $label) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        Write-Host " [UP]   $label" -NoNewline -ForegroundColor Green
        Write-Host " (Port $port, PID: $($conn.OwningProcess) [$($proc.Name)])" -ForegroundColor Gray
    } else {
        Write-Host " [DOWN] $label" -NoNewline -ForegroundColor Red
        Write-Host " (Port $port is free)" -ForegroundColor Gray
    }
}

function Get-ProcessStatus($pattern, $label) {
    $procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*$pattern*" -and $_.Name -ne "powershell.exe" -and $_.Name -ne "pwsh.exe" }
    if ($procs) {
        Write-Host " [RUN]  $label" -NoNewline -ForegroundColor Cyan
        $pCount = if ($procs.Count) { $procs.Count } else { 1 }
        Write-Host " ($pCount instance(s) active)" -ForegroundColor Gray
    } else {
        Write-Host " [IDLE] $label" -ForegroundColor Gray
    }
}

Write-Host "`n--- Forge AI System Status ---" -ForegroundColor Yellow

Write-Host "`nServers:" -ForegroundColor White
Get-PortStatus 50051 "Forge Simulation Server"
Get-PortStatus 50052 "Python Inference Server"

Write-Host "`nActive Tasks:" -ForegroundColor White
Get-ProcessStatus "simulation_harness.py" "Simulation Harness"
Get-ProcessStatus "run_full_batch.ps1" "Full Batch Orchestrator"
Get-ProcessStatus "run_heuristic_benchmark.ps1" "Heuristic Benchmark Orchestrator"

Write-Host "`n----------------------------`n" -ForegroundColor Yellow
