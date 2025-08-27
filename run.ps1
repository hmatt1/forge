#!/usr/bin/env pwsh
# Script to run Forge simulations using Maven to handle all dependencies

param(
    [Parameter(Mandatory=$false)]
    [string]$Deck1 = "Deck_998.dck",

    [Parameter(Mandatory=$false)]
    [string]$Deck2 = "Deck_999.dck",

    [Parameter(Mandatory=$false)]
    [int]$NumGames = 1,

    [Parameter(Mandatory=$false)]
    [string]$Memory = "768m",

    [Parameter(Mandatory=$false)]
    [switch]$SkipBuild = $false
)

# Project root directory (where the script is located)
$projectRoot = (Get-Location).Path

# --- JVM Arguments ---
# Define arguments for the Java Virtual Machine.
# -Xms: Initial heap size
# -XX:+UseParallelGC: Use the parallel garbage collector for better performance
# -Dsun.java2d.xrender=false: May improve 2D rendering compatibility/performance on some systems
# --add-opens java.base/java.util=ALL-UNNAMED: Necessary for reflection used by some libraries
$jvmArgs = "-Xms$Memory -XX:+UseParallelGC -Dsun.java2d.xrender=false --add-opens java.base/java.util=ALL-UNNAMED -Dcheckstyle.skip=true" # Note: These JVM args are defined but not explicitly used in the mvn exec:java command below.
# If needed, they could be passed via -Dexec.jvmArgs="..." or MAVEN_OPTS environment variable.

# --- Simulation Details ---
Write-Host "Running Forge simulation:" -ForegroundColor Cyan
Write-Host "Deck 1: $Deck1" -ForegroundColor Cyan
Write-Host "Deck 2: $Deck2" -ForegroundColor Cyan
Write-Host "Number of games: $NumGames" -ForegroundColor Cyan
Write-Host "--------------------------------------------------------------" -ForegroundColor Cyan

# --- Build Project (Optional) ---
# Build the entire project using Maven if the -SkipBuild flag is not present.
if (-not $SkipBuild) {
    Write-Host "Building the project with Maven..." -ForegroundColor Cyan

    # Navigate to the root of the project
    Push-Location $projectRoot
    try {
        # Execute Maven clean and package goals. -DskipTests skips running unit tests.
        mvn clean package -DskipTests
        # Check if the Maven command was successful
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Maven build failed with exit code $LASTEXITCODE" -ForegroundColor Red
            # Exit the script if the build fails
            exit $LASTEXITCODE
        }
        Write-Host "Maven build completed successfully." -ForegroundColor Green
    }
    catch {
        Write-Host "An error occurred during the Maven build: $($_.Exception.Message)" -ForegroundColor Red
        exit 1 # Exit with a generic error code
    }
    finally {
        # Return to the original directory
        Pop-Location
    }
} else {
    Write-Host "Skipping Maven build step." -ForegroundColor Yellow
}

# --- Run Simulation ---
Write-Host "Navigating to forge-gui-desktop module to run simulation..." -ForegroundColor Cyan
# Navigate to the specific Maven module that contains the main class
Push-Location "$projectRoot\forge-gui-desktop"
try {
    # Construct the absolute path to the local settings file located in the project root's .mvn directory
    $localSettingsPath = Join-Path $projectRoot ".mvn\local-settings.xml"
    $mavenCommand = "mvn"
    $mavenArgs = @(
        "exec:java",
        "-Dexec.mainClass=forge.view.Main",
        "-Dexec.args=""sim -d '$Deck1' '$Deck2' -n $NumGames""",
        "-Dexec.cleanupDaemonThreads=false",
        "-Dcheckstyle.skip=true"
    )

    # Check if the intended local settings file exists and prepend the -s flag if it does
    if (Test-Path $localSettingsPath) {
        Write-Host "Using project-specific settings file: $localSettingsPath" -ForegroundColor Gray
        # Add the -s flag with the absolute path to the settings file
        $mavenArgs = @("-s", "`"$localSettingsPath`"") + $mavenArgs # Add -s flag at the beginning
    } else {
        # If the specific local settings file isn't found, run without -s
        # Maven will then use its default behavior (checking global ~/.m2/settings.xml)
        Write-Host "Project-specific settings file not found at '$localSettingsPath'. Using default Maven settings resolution." -ForegroundColor Yellow
    }

    Write-Host "Executing: $mavenCommand $($mavenArgs -join ' ')" -ForegroundColor Cyan
    # Execute Maven with the constructed arguments
    & $mavenCommand $mavenArgs

    # Check the exit code of the Maven command
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Simulation failed with exit code $LASTEXITCODE" -ForegroundColor Red
    } else {
        Write-Host "Maven execution completed." -ForegroundColor Green
    }
}
catch {
    Write-Host "An error occurred during simulation execution: $($_.Exception.Message)" -ForegroundColor Red
    # Optionally, re-throw the exception or handle it
}
finally {
    # Return to the previous directory
    Pop-Location
}