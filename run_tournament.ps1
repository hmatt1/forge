<#
.SYNOPSIS
    Simulates a Magic: The Gathering tournament by running pairings of decks
    using run.ps1 concurrently and saves the results to a CSV file.
    Also saves the full output of each simulation job to a separate log file.

.DESCRIPTION
    Generates all unique pairings from a list of decks based on a naming convention.
    Runs each pairing a specified number of times using .\run.ps1 via PowerShell Jobs.
    Manages concurrency to limit simultaneous runs.
    Parses the output of run.ps1 to determine the winner or specific error conditions.
    Collects results (Deck1, Deck2, Winner) and exports them to a CSV file.
    Saves the raw stdout/stderr from each run.ps1 execution into a log file
    in a dedicated subdirectory.

.NOTES
    Author: Your Name / AI Assistant
    Date: 2025-04-15 (Revised)
    Requires: .\run.ps1 in the same directory or provide the full path.
    Assumes run.ps1 outputs a line containing "[Deck Name] has won!" for the winner,
    or "match cannot start" for a specific startup failure.
#>

# --- Configuration ---
$numberOfDecks = 4
$repetitionsPerMatchup = 2 # <<< ADJUST BACK TO 33 (or keep low for testing)
$maxConcurrentJobs = 4
$deckBaseName = "_WOE_PremierDraft___"
$deckMiddlePattern = "{0:D2}" # {0:D2} formats number with leading zero (01, 02, ...)
$deckExtension = "___WG.dck"
$runScriptPath = ".\run.ps1" # Make sure this path is correct
$csvOutputPath = ".\TournamentResults.csv"
$logDirectory = ".\JobOutputs" # *** NEW: Directory for individual job logs ***

# --- Create Log Directory ---
Write-Host "Ensuring log directory exists: $logDirectory"
try {
    if (-not (Test-Path -Path $logDirectory -PathType Container)) {
        New-Item -Path $logDirectory -ItemType Directory -Force | Out-Null
        Write-Host "Created log directory."
    } else {
        Write-Host "Log directory already exists."
    }
}
catch {
    Write-Error "Failed to create log directory '$logDirectory'. Error: $($_.Exception.Message)"
    exit 1
}
Write-Host "---"


# --- Generate Deck Names ---
$deckNames = @()
for ($i = 1; $i -le $numberOfDecks; $i++) {
    $deckNumberStr = $deckMiddlePattern -f $i
    $deckNames += "$($deckBaseName)$($deckNumberStr)$($deckExtension)"
}

Write-Host "Generated Deck Names:"
$deckNames | ForEach-Object { Write-Host "- $_" }
Write-Host "---"

# --- Generate All Games to Run ---
$allGames = [System.Collections.Generic.List[object]]::new()
Write-Host "Generating matchups..."
for ($i = 0; $i -lt $deckNames.Count; $i++) {
    for ($j = $i + 1; $j -lt $deckNames.Count; $j++) {
        for ($rep = 1; $rep -le $repetitionsPerMatchup; $rep++) {
            $game = [PSCustomObject]@{
                Deck1 = $deckNames[$i]
                Deck2 = $deckNames[$j]
                # You could add Repetition = $rep here if needed for logging
            }
            $allGames.Add($game)
        }
    }
}
$totalGames = $allGames.Count
Write-Host "Total games to simulate: $totalGames ($repetitionsPerMatchup repetitions per unique matchup)"
Write-Host "---"

# --- Initialize Job Info and Result Lists ---
$activeJobInfoList = [System.Collections.Generic.List[PSCustomObject]]::new()
$resultsList = [System.Collections.Generic.List[PSCustomObject]]::new()
$completedGames = 0

# --- Check if run.ps1 exists ---
if (-not (Test-Path $runScriptPath)) {
    Write-Error "Error: The script '$runScriptPath' was not found. Please check the path."
    exit 1
}

# --- Function to Sanitize Filenames ---
# Removes characters invalid in Windows filenames
function Sanitize-FileName {
    param(
        [Parameter(Mandatory=$true)]
        [string]$FileName
    )
    return $FileName -replace '[\\/:*?"<>|]', '_'
}


# --- Main Processing Loop ---
Write-Host "Starting simulations with up to $maxConcurrentJobs concurrent jobs..."

foreach ($game in $allGames) {
    # --- Concurrency Control: Wait if max jobs are running ---
    while ($activeJobInfoList.Count -ge $maxConcurrentJobs) {
        $completedJobInfo = $activeJobInfoList | Select-Object -ExpandProperty Job | Wait-Job -Any -Timeout 1
        if ($completedJobInfo) {
            break
        }
    }

    # --- Process Completed Jobs ---
    $jobInfoToCheck = $activeJobInfoList.ToArray()
    foreach ($jobInfo in $jobInfoToCheck) {
        if ($jobInfo.Job.State -eq [System.Management.Automation.JobState]::Completed -or $jobInfo.Job.State -eq [System.Management.Automation.JobState]::Failed) {
            $completedGames++
            $jobData_Deck1 = $jobInfo.Deck1
            $jobData_Deck2 = $jobInfo.Deck2
            $jobId = $jobInfo.Job.Id

            # Get output AND save it
            $output = Receive-Job -Job $jobInfo.Job -Keep

            # *** NEW: Save output to log file ***
            $safeDeck1 = Sanitize-FileName -FileName $jobData_Deck1
            $safeDeck2 = Sanitize-FileName -FileName $jobData_Deck2
            $logFileName = "Job${jobId}_${safeDeck1}_vs_${safeDeck2}.log"
            $logFilePath = Join-Path -Path $logDirectory -ChildPath $logFileName
            try {
                # Use Set-Content which handles arrays/collections better by default for line breaks
                Set-Content -Path $logFilePath -Value $output -Encoding UTF8 -Force
                Write-Verbose "Saved job output to $logFilePath"
            }
            catch {
                Write-Warning "Failed to write output log '$logFilePath'. Error: $($_.Exception.Message)"
            }
            # *** END NEW ***

            $winner = "Error/Unknown" # Default winner status

            if ($jobInfo.Job.State -eq [System.Management.Automation.JobState]::Completed) {
                $winnerLine = $output | Where-Object { $_ -match 'has won!' } | Select-Object -First 1
                if ($winnerLine)
                {
                    $winner = $winnerLine
                    $match = [regex]::Match($winnerLine, '#(\d+) - WG') # Adjust regex if needed
                    if ($match.Success)
                    {
                        $winner = "$($deckBaseName)$($match.Groups[1].Value)$($deckExtension)"
                    }
                    else
                    {
                        Write-Warning "Could not extract winner ID pattern (#NN - WG) from line: $winnerLine"
                        $winner = "Parsing Error"
                    }
                } elseif ($output -match 'match cannot start') {
                    Write-Warning "Job $jobId : $($jobInfo.Deck1) vs $($jobInfo.Deck2) reported 'match cannot start'. (Log: $logFileName)"
                    $winner = "Match Cannot Start"
                }
                else {
                    Write-Warning "Job $jobId : Completed but no 'has won!' or 'match cannot start' line found in output for $($jobInfo.Deck1) vs $($jobInfo.Deck2). (Log: $logFileName)"
                    $winner = "No Result Line"
                }
            } else { # Job Failed
                Write-Warning "Job $jobId : Failed for $($jobInfo.Deck1) vs $($jobInfo.Deck2). Error: $($jobInfo.Job.Error) (Log: $logFileName)"
                $winner = "Job Failed"
            }

            $resultsList.Add([PSCustomObject]@{
                Deck1  = $jobInfo.Deck1
                Deck2  = $jobInfo.Deck2
                Winner = $winner
                JobId  = $jobId # Optionally add JobId to results CSV
                LogFile = $logFileName # Optionally add LogFile name to results CSV
            })

            Remove-Job -Job $jobInfo.Job
            $activeJobInfoList.Remove($jobInfo)
        }
    } # End foreach ($jobInfo in $jobInfoToCheck)

    # --- Start New Job ---
    $deck1Arg = $game.Deck1
    $deck2Arg = $game.Deck2

    Write-Verbose "Starting job for: $deck1Arg vs $deck2Arg"
    $scriptBlock = {
        param($ScriptPath, $D1, $D2)
        & $ScriptPath -SkipBuild -NumGames 1 -Deck1 $D1 -Deck2 $D2
    }
    $currentJob = Start-Job -ScriptBlock $scriptBlock -ArgumentList $runScriptPath, $deck1Arg, $deck2Arg -Name "Game_${deck1Arg}_vs_${deck2Arg}"

    $currentJobInfo = [PSCustomObject]@{
        Job   = $currentJob
        Deck1 = $deck1Arg
        Deck2 = $deck2Arg
    }
    $activeJobInfoList.Add($currentJobInfo)

    Write-Progress -Activity "Running Tournament Simulations" -Status "Completed $completedGames of $totalGames games. Running $($activeJobInfoList.Count) jobs." -PercentComplete (($completedGames / $totalGames) * 100)

} # End foreach game loop ($game in $allGames)

# --- Wait for and Process Remaining Jobs ---
Write-Host ""
Write-Host "All simulation jobs started. Waiting for remaining $($activeJobInfoList.Count) jobs to complete..."
while ($activeJobInfoList.Count -gt 0) {
    $jobFinished = $activeJobInfoList | Select-Object -ExpandProperty Job | Wait-Job -Any -Timeout 5
    if ($jobFinished) {
        $jobInfoToCheck = $activeJobInfoList.ToArray()
        foreach ($jobInfo in $jobInfoToCheck) {
            if ($jobInfo.Job.State -eq [System.Management.Automation.JobState]::Completed -or $jobInfo.Job.State -eq [System.Management.Automation.JobState]::Failed) {
                $completedGames++
                $jobData_Deck1 = $jobInfo.Deck1
                $jobData_Deck2 = $jobInfo.Deck2
                $jobId = $jobInfo.Job.Id

                # Get output AND save it
                $output = Receive-Job -Job $jobInfo.Job -Keep

                # *** NEW: Save output to log file ***
                $safeDeck1 = Sanitize-FileName -FileName $jobData_Deck1
                $safeDeck2 = Sanitize-FileName -FileName $jobData_Deck2
                $logFileName = "Job${jobId}_${safeDeck1}_vs_${safeDeck2}.log"
                $logFilePath = Join-Path -Path $logDirectory -ChildPath $logFileName
                try {
                    Set-Content -Path $logFilePath -Value $output -Encoding UTF8 -Force
                    Write-Verbose "Saved job output to $logFilePath"
                }
                catch {
                    Write-Warning "Failed to write output log '$logFilePath'. Error: $($_.Exception.Message)"
                }
                # *** END NEW ***

                $winner = "Error/Unknown"

                if ($jobInfo.Job.State -eq [System.Management.Automation.JobState]::Completed) {
                    $winnerLine = $output | Where-Object { $_ -match 'has won!' } | Select-Object -First 1
                    if ($winnerLine)
                    {
                        $winner = $winnerLine
                        $match = [regex]::Match($winnerLine, '#(\d+) - WG') # Adjust regex if needed
                        if ($match.Success)
                        {
                            $winner = "$($deckBaseName)$($match.Groups[1].Value)$($deckExtension)"
                        }
                        else
                        {
                            Write-Warning "Could not extract winner ID pattern (#NN - WG) from line: $winnerLine"
                            $winner = "Parsing Error"
                        }
                    } elseif ($output -match 'match cannot start') {
                        Write-Warning "Job $jobId : $($jobInfo.Deck1) vs $($jobInfo.Deck2) reported 'match cannot start'. (Log: $logFileName)"
                        $winner = "Match Cannot Start"
                    }
                    else {
                        Write-Warning "Job $jobId : Completed but no 'has won!' or 'match cannot start' line found in output for $($jobInfo.Deck1) vs $($jobInfo.Deck2). (Log: $logFileName)"
                        $winner = "No Result Line"
                    }
                } else { # Job Failed
                    Write-Warning "Job $jobId : Failed for $($jobInfo.Deck1) vs $($jobInfo.Deck2). Error: $($jobInfo.Job.Error) (Log: $logFileName)"
                    $winner = "Job Failed"
                }

                $resultsList.Add([PSCustomObject]@{
                    Deck1   = $jobInfo.Deck1
                    Deck2   = $jobInfo.Deck2
                    Winner  = $winner
                    JobId   = $jobId # Optionally add JobId to results CSV
                    LogFile = $logFileName # Optionally add LogFile name to results CSV
                })

                Remove-Job -Job $jobInfo.Job
                $activeJobInfoList.Remove($jobInfo)
                Write-Progress -Activity "Finishing Up" -Status "Completed $completedGames of $totalGames games. Waiting for $($activeJobInfoList.Count) jobs." -PercentComplete (($completedGames / $totalGames) * 100)
            } # End if job completed/failed
        } # End foreach ($jobInfo in $jobInfoToCheck)
    } else { # End if ($jobFinished)
        Write-Host "Still waiting for $($activeJobInfoList.Count) jobs..." -NoNewline
        Start-Sleep -Seconds 2
        Write-Host "`r" -NoNewline
    }
} # End while ($activeJobInfoList.Count -gt 0)

Write-Progress -Activity "Finishing Up" -Completed
Write-Host ""
Write-Host "---"

# --- Export Results to CSV ---
Write-Host "Exporting results to $csvOutputPath..."
try {
    # Note: Added JobId and LogFile to the exported object
    $resultsList | Export-Csv -Path $csvOutputPath -NoTypeInformation -Encoding UTF8 -Force
    Write-Host "Successfully exported $completedGames results to $csvOutputPath"
}
catch {
    Write-Error "Failed to export results to CSV: $($_.Exception.Message)"
}

Write-Host "Individual job outputs saved in '$logDirectory'."
Write-Host "Tournament simulation complete."