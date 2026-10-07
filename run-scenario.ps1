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
#   -Window <x,y,w,h>    where to put the client window, "off", or "auto"/empty (the default): claim a screen slot -
#                        a quadrant of the configured screen (windowScreen in testkit.properties: auto = the second
#                        monitor, else the primary one), or a finer grid while more than 4 runs are live
#   -Extra <args>        anything else for gradle, e.g. -Extra "-Pnogrim","-PseedConfig=C:/x"
#   -ModUnderTest <jar>  any Fabric mod jar. Default: the newest snapshot folder under jarsDir holding a jar that
#                        matches modJarPattern (testkit.properties; get-mod.ps1 fills it), falling back to the
#                        modSource checkout's build/libs. Pass it explicitly when it matters.
#   -Minecraft <ver>     26.1.2 (default) or 26.2. Passes -Pminecraft_version and picks that version's jar. When it is
#                        not given and -ModUnderTest is, it is read from the jar's fabric.mod.json (depends.minecraft);
#                        a jar whose range excludes the run's version is refused, because loader would only refuse it later.
#   -NoMod               run WITHOUT any mod (no -PmodUnderTest): a baseline, e.g. for 65-join-fingerprint.

param(
    [string]$Scenario = "",
    [string]$Suite = "",
    [int]$Port = 0,
    [string]$Window = "",
    [string[]]$Extra = @(),
    [string]$ModUnderTest = "",
    [string]$Minecraft = "26.1.2",
    [int]$TimeoutSeconds = 240,
    [switch]$NoMod
)

$here = $PSScriptRoot
. (Join-Path $here "tools/testkit-config.ps1")
$cfg = Get-TestkitConfig $here

if ($NoMod) {
    $ModUnderTest = ""
} elseif ($ModUnderTest -eq "") {
    # The old default named a jar in the mod's build/libs, which the mod's own builds overwrite mid-run and which
    # does not exist at all while the mod is being rebuilt. Snapshots under jarsDir do not move.
    $ModUnderTest = Find-TestkitSnapshotJar $cfg $Minecraft
    if (-not $ModUnderTest) {
        $built = Get-ChildItem -Path "$($cfg.modSource)/build/libs" -Filter ($cfg.modJarPattern.Replace("{mc}", $Minecraft)) -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($built) { $ModUnderTest = $built.FullName.Replace('\', '/') }
    }
    if (-not $ModUnderTest) {
        Write-Host ("No mod jar for Minecraft $Minecraft : nothing matching " + $cfg.modJarPattern.Replace("{mc}", $Minecraft) +
            " under $($cfg.jarsDir)/*/ or $($cfg.modSource)/build/libs.")
        Write-Host "Run ./get-mod.ps1 to build killer560s-mod into jarsDir, pass -ModUnderTest <your jar>, or -NoMod."
        exit 4
    }
}
if ((-not $NoMod) -and (-not (Test-Path $ModUnderTest))) {
    Write-Host "Mod under test not found: $ModUnderTest"
    exit 4
}
$modInfo = $null
if (-not $NoMod) {
    $modInfo = Get-ModJarInfo $ModUnderTest
    if (-not $modInfo) {
        Write-Host "Mod under test $ModUnderTest has no readable fabric.mod.json - is it a Fabric mod jar?"
        exit 4
    }
    # Any mod: the Minecraft version follows the jar unless -Minecraft was given. Both versions in range (">=26.1")
    # keeps the default.
    if (-not $PSBoundParameters.ContainsKey('Minecraft')) {
        $fits = @(@("26.1.2", "26.2") | Where-Object { (Test-McRange $modInfo.Minecraft $_) -eq $true })
        if ($fits.Count -eq 1) { $Minecraft = $fits[0] }
    }
    if ((Test-McRange $modInfo.Minecraft $Minecraft) -eq $false) {
        Write-Host "Mod under test $($modInfo.Id) declares minecraft '$($modInfo.Minecraft)', which excludes this run's -Minecraft $Minecraft"
        exit 4
    }
}
# killer560s-mod's jars carry their Minecraft version in the classifier (-26.1.2-cheat / -26.2-legit) and their
# fabric.mod.json ranges are mutually exclusive, so a mismatch is a client that refuses to start.
$leaf = ""
if (-not $NoMod) { $leaf = Split-Path $ModUnderTest -Leaf }
if (($leaf -match '-(\d+\.\d+(?:\.\d+)?)-(cheat|legit)\.jar$') -and ($Matches[1] -ne $Minecraft)) {
    Write-Host "Mod under test $leaf is built for Minecraft $($Matches[1]), but this run is -Minecraft $Minecraft"
    exit 4
}
if ($Scenario -ne "" -and $Suite -ne "") {
    Write-Host "Give -Scenario or -Suite, not both"
    exit 4
}
# With a TRAILING SLASH. Without it a checkout path like .../killer560s-mod-testkit is a prefix of the sibling checkouts
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

# Screen slots. killer560 (2026-10-07): "I really like how it is split into those 4 quadrants with one test in each
# quadrant so I can see all of them at once. If you are ever running more than 4 at once then resize them."
# Every run without an explicit -Window claims a slot file in one directory shared by every checkout and worktree
# (the file holds this script's pid; a dead pid's slot is free again). Up to 4 live runs use the screen's quarters
# by slot number; more than that, every live run moves to a grid that fits them all, and back as they end.
# The directory and the screen come from testkit.properties (windowSlotsDir, windowScreen).
$slotDir = $cfg.windowSlotsDir
$mySlot = -1
$screen = Resolve-TestkitScreen $cfg.windowScreen
if ($null -eq $screen -and ($Window -eq "" -or $Window -eq "auto")) { $Window = "off" }

function Get-LiveSlots {
    $live = @()
    foreach ($f in Get-ChildItem -Path $slotDir -Filter "slot-*.txt" -ErrorAction SilentlyContinue) {
        $owner = 0
        try { $owner = [int]((Get-Content -Path $f.FullName -ErrorAction Stop | Select-Object -First 1)) } catch {}
        if ($owner -gt 0 -and (Get-Process -Id $owner -ErrorAction SilentlyContinue)) {
            $live += [int]($f.BaseName.Substring(5))
        }
    }
    return @($live | Sort-Object)
}

function Get-SlotTile([int]$slot) {
    $live = @(Get-LiveSlots)
    if ($live -notcontains $slot) { $live = @($live + $slot | Sort-Object) }
    $n = $live.Count
    if ($n -le 4 -and ($live | Measure-Object -Maximum).Maximum -lt 4) {
        $col = $slot % 2
        $row = [math]::Floor($slot / 2)
        $w = [int]($screen.W / 2)
        $h = [int]($screen.H / 2)
    } else {
        $cols = [int][math]::Ceiling([math]::Sqrt($n))
        $rows = [int][math]::Ceiling($n / $cols)
        $idx = [array]::IndexOf($live, $slot)
        $col = $idx % $cols
        $row = [math]::Floor($idx / $cols)
        $w = [int]($screen.W / $cols)
        $h = [int]($screen.H / $rows)
    }
    return "$($screen.X + $col * $w),$($screen.Y + $row * $h),$w,$h"
}

if ($Window -eq "" -or $Window -eq "auto") {
    New-Item -ItemType Directory -Force -Path $slotDir | Out-Null
    for ($i = 0; $i -lt 16 -and $mySlot -lt 0; $i++) {
        $f = "$slotDir/slot-$i.txt"
        if (Test-Path $f) {
            if (@(Get-LiveSlots) -contains $i) { continue }
            Remove-Item -Path $f -Force -ErrorAction SilentlyContinue
        }
        try {
            # CreateNew is atomic: two runs starting together cannot both take the same slot.
            $fs = [System.IO.File]::Open($f, 'CreateNew', 'Write')
            $bytes = [System.Text.Encoding]::ASCII.GetBytes("$PID")
            $fs.Write($bytes, 0, $bytes.Length)
            $fs.Close()
            $mySlot = $i
        } catch {}
    }
    if ($mySlot -ge 0) {
        $Window = Get-SlotTile $mySlot
        Write-Host "Window slot $mySlot -> $Window"
    } else {
        $Window = ""
    }
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class WinSlot {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  public struct RECT { public int Left, Top, Right, Bottom; }
}
'@

# Keeps this run's client on its tile as other runs start and finish: compares the window's real rectangle with the
# tile this slot should have now, and moves it when they differ (no focus change, no raise).
function Sync-SlotWindow {
    if ($mySlot -lt 0) { return }
    $t = (Get-SlotTile $mySlot).Split(",") | ForEach-Object { [int]$_ }
    foreach ($c in Get-TestClients) {
        $p = Get-Process -Id $c.ProcessId -ErrorAction SilentlyContinue
        if (-not $p -or $p.MainWindowHandle -eq 0) { continue }
        $r = New-Object WinSlot+RECT
        [WinSlot]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
        if ([math]::Abs($r.Left - $t[0]) -gt 2 -or [math]::Abs($r.Top - $t[1]) -gt 2 -or
                [math]::Abs(($r.Right - $r.Left) - $t[2]) -gt 2 -or [math]::Abs(($r.Bottom - $r.Top) - $t[3]) -gt 2) {
            # SWP_NOZORDER | SWP_NOACTIVATE
            [WinSlot]::SetWindowPos($p.MainWindowHandle, [IntPtr]::Zero, $t[0], $t[1], $t[2], $t[3], 0x14) | Out-Null
        }
    }
}

function Release-Slot {
    if ($mySlot -lt 0) { return }
    $f = "$slotDir/slot-$mySlot.txt"
    $owner = ""
    try { $owner = (Get-Content -Path $f -ErrorAction Stop | Select-Object -First 1) } catch {}
    if ($owner -eq "$PID") { Remove-Item -Path $f -Force -ErrorAction SilentlyContinue }
}

# Anything left over from a previous run goes first, or it holds file handles the next run needs.
$stale = Get-TestClients
foreach ($p in $stale) {
    Write-Host "Killing a leftover test client (pid $($p.ProcessId))"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

# Not $args - that is an automatic variable in PowerShell and assigning to it is a parse-time surprise.
$gradleArgs = @("runClientGameTest", "--console=plain")
if (-not $NoMod) { $gradleArgs += "-PmodUnderTest=$ModUnderTest" }
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
$modLabel = "none (-NoMod)"
if ($modInfo) { $modLabel = "$($modInfo.Id) $($modInfo.Version) from $ModUnderTest" }
Write-Host "Running $what on Minecraft $Minecraft with a $TimeoutSeconds s deadline in $here (mod: $modLabel)"
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
    Sync-SlotWindow
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
    Release-Slot
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
    Release-Slot
    exit 2
}

# A run that finished normally can still leave a client behind if the harness died badly.
foreach ($p in Get-TestClients) {
    Write-Host "Cleaning up a test client that outlived the run (pid $($p.ProcessId))"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
Write-Host "Run finished with exit code $($proc.ExitCode)"
Release-Slot
exit $proc.ExitCode
