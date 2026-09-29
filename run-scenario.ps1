# Runs one gametest scenario and guarantees it cleans up after itself.
#
# killer560 (2026-09-28): "can you make it so i dont have to manually close out every time the game freezes.
# I have to manually go in and do it and it is very annoying."
#
# A scenario opens a real Minecraft window on his desktop. When one hangs - and several have - Gradle sits
# there waiting forever and the window stays on screen until he kills it by hand. That is my mess appearing
# on his machine, so cleaning it up is my job and it should not depend on me remembering.
#
# This wraps a run in a hard deadline and kills the client if it overruns. It only ever kills a javaw whose
# command line contains this testkit's own path, so a game he is actually playing is never a candidate - the
# same identification the window placer already uses, and for the same reason.

param(
    [string]$Scenario = "",
    [string]$ModUnderTest = "C:/Users/Hunter/killer560s-mod/build/libs/killer560smod-1.1.0-cheat.jar",
    [int]$TimeoutSeconds = 240
)

$here = $PSScriptRoot
$marker = $here.Replace('\', '/')

function Get-TestClients {
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" -ErrorAction SilentlyContinue | Where-Object {
        $cl = $_.CommandLine
        $cl -and ($cl.Replace('\', '/') -like "*$marker*")
    }
}

# Anything left over from a previous run goes first, or it holds file handles the next run needs.
$stale = Get-TestClients
foreach ($p in $stale) {
    Write-Host "Killing a leftover test client (pid $($p.ProcessId))"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

$args = @("runClientGameTest", "-PmodUnderTest=$ModUnderTest", "--console=plain")
if ($Scenario -ne "") { $args += "-Pscenario=$Scenario" }

Write-Host "Running $($Scenario -eq '' ? 'all scenarios' : $Scenario) with a $TimeoutSeconds s deadline"
$proc = Start-Process -FilePath "$here\gradlew.bat" -ArgumentList $args -WorkingDirectory $here -PassThru -NoNewWindow

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
}

if (-not $proc.HasExited) {
    Write-Host "Deadline passed - the run is hung. Cleaning up so nothing is left on screen."
    foreach ($p in Get-TestClients) {
        Write-Host "  killing test client pid $($p.ProcessId)"
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Write-Host "TIMED OUT after $TimeoutSeconds s"
    exit 2
}

# A run that finished normally can still leave a client behind if the harness died badly.
foreach ($p in Get-TestClients) {
    Write-Host "Cleaning up a test client that outlived the run (pid $($p.ProcessId))"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
Write-Host "Run finished with exit code $($proc.ExitCode)"
exit $proc.ExitCode
