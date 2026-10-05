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

#
# Parameters beyond the scenario filter (all optional):
#   -Suite <name>        a named filter from suites.properties instead of -Scenario
#   -Port <n>            this checkout's server port (Hx bridge on n+5); sticks for later runs of this checkout
#   -Window <x,y,w,h>    where to put the client window, or "off"
#   -Extra <args>        anything else for gradle, e.g. -Extra "-Pnogrim","-PseedConfig=C:/x"
#   -ModUnderTest <jar>  default: the newest snapshot in C:/Users/Hunter/killer560s-mod-testkit-jars/*/ (cheat,
#                        for -Minecraft), falling back to the mod's own build/libs. Pass it explicitly when it matters.
#   -Minecraft <ver>     26.1.2 (default) or 26.2. Passes -Pminecraft_version and picks that version's jar; a jar
#                        named for the other version is refused, because loader would only refuse it later.

param(
    [string]$Scenario = "",
    [string]$Suite = "",
    [int]$Port = 0,
    [string]$Window = "",
    [string[]]$Extra = @(),
    [string]$ModUnderTest = "",
    [string]$Minecraft = "26.1.2",
    [int]$TimeoutSeconds = 240
)

$here = $PSScriptRoot

if ($ModUnderTest -eq "") {
    # The old default named a jar in the mod's build/libs, which the mod's own builds overwrite mid-run and which
    # does not exist at all while the mod is being rebuilt. Snapshots under killer560s-mod-testkit-jars do not move.
    $snap = Get-ChildItem -Path "C:/Users/Hunter/killer560s-mod-testkit-jars" -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        ForEach-Object { Get-ChildItem -Path $_.FullName -Filter "killer560smod-*-$Minecraft-cheat.jar" -ErrorAction SilentlyContinue } |
        Select-Object -First 1
    if ($snap) {
        $ModUnderTest = $snap.FullName.Replace('\', '/')
    } else {
        $ModUnderTest = "C:/Users/Hunter/killer560s-mod/build/libs/killer560smod-1.1.0-$Minecraft-cheat.jar"
    }
}
if (-not (Test-Path $ModUnderTest)) {
    Write-Host "Mod under test not found: $ModUnderTest"
    exit 4
}
# The mod's jars carry their Minecraft version in the classifier (-26.1.2-cheat / -26.2-legit) and their
# fabric.mod.json ranges are mutually exclusive, so a mismatch is a client that refuses to start.
$leaf = Split-Path $ModUnderTest -Leaf
if (($leaf -match '-(\d+\.\d+(?:\.\d+)?)-(cheat|legit)\.jar$') -and ($Matches[1] -ne $Minecraft)) {
    Write-Host "Mod under test $leaf is built for Minecraft $($Matches[1]), but this run is -Minecraft $Minecraft"
    exit 4
}
if ($Scenario -ne "" -and $Suite -ne "") {
    Write-Host "Give -Scenario or -Suite, not both"
    exit 4
}
# With a TRAILING SLASH. Without it C:/Users/Hunter/killer560s-mod-testkit is a prefix of the sibling checkouts
# (-pzA, -pzB, -wt/1, ...), so this script's freeze watcher and deadline cleanup also matched - and killed - other
# checkouts' clients mid-run. Found 2026-10-04 with three checkouts running at once.
$marker = $here.Replace('\', '/').TrimEnd('/') + '/'

function Get-TestClients {
    # BOTH java.exe and javaw.exe.
    #
    # The gametest client runs as java.exe - confirmed 2026-09-29, when pid 38312 WAS the client and this
    # function could not see it. Every protection in this script was therefore inert: the freeze watcher never
    # found an unresponsive client and the deadline cleanup killed nothing, while the script still reported
    # success. That is killer560's "it still doesnt close out on freeze it seems", and it explains why the
    # earlier fixes to the watching logic changed nothing - they were watching an empty set.
    #
    # The testkit path stays the discriminator, so a game he is playing is never a candidate; fabric.dli.env=client
    # narrows it further to the gametest client rather than the Gradle daemon that launched it (fabric.addMods did
    # the same job but is absent from a run without -PmodUnderTest).
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object {
        $cl = $_.CommandLine
        $cl -and ($cl.Replace('\', '/') -like "*$marker*") -and ($cl -like '*fabric.dli.env=client*')
    }
}

# Anything left over from a previous run goes first, or it holds file handles the next run needs.
$stale = Get-TestClients
foreach ($p in $stale) {
    Write-Host "Killing a leftover test client (pid $($p.ProcessId))"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

# Not $args - that is an automatic variable in PowerShell and assigning to it is a parse-time surprise.
$gradleArgs = @("runClientGameTest", "-PmodUnderTest=$ModUnderTest", "--console=plain")
# Only passed when it is not the default, so a 26.1.2 run's command line is exactly what it always was.
if ($Minecraft -ne "26.1.2") { $gradleArgs += "-Pminecraft_version=$Minecraft" }
if ($Scenario -ne "") { $gradleArgs += "-Pscenario=$Scenario" }
if ($Suite -ne "") { $gradleArgs += "-Psuite=$Suite" }
if ($Port -gt 0) { $gradleArgs += "-Pport=$Port" }
if ($Window -ne "") { $gradleArgs += "-PtestWindow=$Window" }
$gradleArgs += $Extra

# No ternary: this is Windows PowerShell 5.1, where ?: is a parser error.
$what = $Scenario
if ($Suite -ne "") { $what = "suite $Suite" }
if ($what -eq "") { $what = "all scenarios" }
Write-Host "Running $what with a $TimeoutSeconds s deadline in $here (mod: $ModUnderTest)"
$proc = Start-Process -FilePath "$here\gradlew.bat" -ArgumentList $gradleArgs -WorkingDirectory $here -PassThru -NoNewWindow
# Touch the handle now: without it PowerShell 5.1 reports ExitCode as empty once the process has gone, and a caller
# (run-suite, parallel-suite) cannot tell a failed run from a passed one.
$null = $proc.Handle

# Watch it WHILE it runs, not only at the deadline.
#
# killer560 (2026-09-28): "your sim server still doesnt close automatically whenever it gets stuck and
# frozen." Killing only on the deadline meant a client that froze thirty seconds in still sat on his screen
# for the remaining four minutes. Windows already knows when a window has stopped pumping its message queue -
# that is exactly what "(Not Responding)" in the title bar means - so this asks, and kills as soon as it has
# been true for long enough to not be a passing hitch.
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$hungPolls = 0
$hungLimit = 8          # 8 polls x 2 s = ~16 s unresponsive before it counts as frozen
$killedHung = $false

# Loop while EITHER the launcher is alive OR a test client exists.
#
# Watching only $proc was wrong and is why a run still overran by minutes: gradlew.bat is a batch file, so
# Start-Process gives back the cmd.exe wrapping it, and that can exit while the Gradle daemon carries on with
# the client still to come. The loop then ended, found no client yet because it had not started, and left
# everything running - "automatic" cleanup that cleaned up nothing.
while ((-not $proc.HasExited -or (Get-TestClients)) -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $frozen = $false
    foreach ($c in Get-TestClients) {
        $p = Get-Process -Id $c.ProcessId -ErrorAction SilentlyContinue
        # Responding is only meaningful once there is a window to be unresponsive.
        if ($p -and $p.MainWindowHandle -ne 0 -and -not $p.Responding) { $frozen = $true }
    }
    if ($frozen) {
        $hungPolls++
        if ($hungPolls -eq 1) { Write-Host "Test client has stopped responding - watching it" }
        if ($hungPolls -ge $hungLimit) {
            Write-Host "Test client frozen for ~$($hungLimit * 2) s - closing it so it is not left on screen"
            foreach ($c in Get-TestClients) {
                Stop-Process -Id $c.ProcessId -Force -ErrorAction SilentlyContinue
            }
            $killedHung = $true
            break
        }
    } else {
        $hungPolls = 0
    }
}

if ($killedHung) {
    # Give Gradle a moment to notice its child died, then stop it too rather than leave it waiting.
    Start-Sleep -Seconds 5
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Write-Host "FROZEN - the client was closed automatically"
    exit 3
}

if ((Get-Date) -ge $deadline) {
    Write-Host "Deadline passed - the run is hung. Cleaning up so nothing is left on screen."
    foreach ($p in Get-TestClients) {
        Write-Host "  killing test client pid $($p.ProcessId)"
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    # And the Gradle daemon running this project. Killing only the client left Gradle waiting on a child that
    # was gone, which is how a run still ran for eight minutes against a four-minute deadline.
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -and ($_.CommandLine.Replace('\', '/') -like "*$marker*")
    } | ForEach-Object {
        Write-Host "  killing gradle/java pid $($_.ProcessId)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
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
