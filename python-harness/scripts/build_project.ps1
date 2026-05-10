# PowerShell script to run a clean build for the entire Forge project.
# This script ensures that all JARs are updated with the latest changes.

$scriptPath = $MyInvocation.MyCommand.Path
$scriptsDir = Split-Path $scriptPath
$projectRoot = Resolve-Path "$scriptsDir\..\.."

Write-Host "--- Starting Clean Build ---" -ForegroundColor Cyan
Write-Host "Project Root: $projectRoot" -ForegroundColor Gray

# Switch to project root
Push-Location $projectRoot

try {
    # Run Maven clean install skipping tests for speed
    # install ensures that sibling modules pick up changes even if not perfectly reactor-linked
    mvn clean install -DskipTests
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "`nBuild Successful!" -ForegroundColor Green
    } else {
        Write-Host "`nBuild Failed with exit code $LASTEXITCODE" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
