# Runs one named suite (suites.properties) in THIS checkout, with run-scenario.ps1's freeze watcher and deadline,
# then prints where the report is and its totals.
#
#   ./run-suite.ps1 -Suite harness
#   ./run-suite.ps1 -Suite hx -Port 25570 -Window "0,0,960,540"
#   ./run-suite.ps1 -Suite demo -Extra "-PnetOnline"
#   ./run-suite.ps1 -Suite harness -Minecraft 26.2                 # 26.2 build and the 26.2 mod jar
#
# Exit code is the run's (0 = every selected scenario and case passed). The report is build/testkit-report/
# summary.md - read the rows, not just the exit code: a row's detail line says which number decided it.

param(
    [Parameter(Mandatory = $true)][string]$Suite,
    [int]$Port = 0,
    [string]$Window = "",
    [string[]]$Extra = @(),
    [string]$ModUnderTest = "",
    [string]$Minecraft = "26.1.2",
    [int]$TimeoutSeconds = 1200
)

$here = $PSScriptRoot
$props = Join-Path $here "suites.properties"
$known = @()
foreach ($line in Get-Content $props) {
    if ($line -match '^\s*([A-Za-z0-9_-]+)\s*=') { $known += $Matches[1] }
}
if ($known -notcontains $Suite) {
    Write-Host ("No suite '" + $Suite + "' in suites.properties. Known: " + ($known -join ", "))
    exit 4
}

$runArgs = @{ Suite = $Suite; TimeoutSeconds = $TimeoutSeconds; Extra = $Extra; Minecraft = $Minecraft }
if ($Port -gt 0) { $runArgs.Port = $Port }
if ($Window -ne "") { $runArgs.Window = $Window }
if ($ModUnderTest -ne "") { $runArgs.ModUnderTest = $ModUnderTest }

$started = Get-Date
& (Join-Path $here "run-scenario.ps1") @runArgs
$code = $LASTEXITCODE

$summary = Join-Path $here "build/testkit-report/summary.md"
# Only this run's report: one left from an earlier run (this one died before Gradle wiped it) would lie.
if ((Test-Path $summary) -and ((Get-Item $summary).LastWriteTime -gt $started)) {
    $totals = Select-String -Path $summary -Pattern '^- totals:' | Select-Object -First 1
    Write-Host ("[run-suite] " + $Suite + ": " + $(if ($totals) { $totals.Line.Substring(2) } else { "no totals line" }))
    Write-Host ("[run-suite] report: " + $summary)
} else {
    Write-Host "[run-suite] no report was written - the run did not reach its end (see the gradle output above)"
}
exit $code
