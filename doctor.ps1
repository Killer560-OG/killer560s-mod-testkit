# Checks this machine can run the testkit, and says exactly what to fix. Changes nothing.
#
#   ./doctor.ps1                      # everything
#   ./doctor.ps1 -Port 26700          # also check the ports a -Port 26700 run would use
#   ./doctor.ps1 -ModUnderTest <jar>  # check a specific mod jar instead of the default snapshot
#
# Exit code 0: nothing blocks a run (warnings may still be printed). 1: at least one PROBLEM line to fix first.

param(
    [int]$Port = 0,
    [string]$ModUnderTest = "",
    [string]$Minecraft = "26.1.2"
)

$here = $PSScriptRoot
. (Join-Path $here "tools/testkit-config.ps1")
$problems = 0
$warnings = 0
function Ok([string]$m) { Write-Host "  ok       $m" }
function Warn([string]$m) { Write-Host "  WARNING  $m"; $script:warnings++ }
function Problem([string]$m, [string]$fix) {
    Write-Host "  PROBLEM  $m"
    if ($fix) { $fix -split "`n" | ForEach-Object { Write-Host "           fix: $_" } }
    $script:problems++
}

Write-Host "testkit doctor - $here"
Write-Host ""
Write-Host "System"
if ([Environment]::OSVersion.Platform -eq "Win32NT") { Ok "Windows $([Environment]::OSVersion.Version)" }
else { Problem "not Windows" "the scripts use Windows PowerShell and Win32 window placement; run on Windows 10/11" }
if ($PSVersionTable.PSVersion.Major -ge 5) { Ok "PowerShell $($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))" }
else { Problem "PowerShell $($PSVersionTable.PSVersion) is too old" "use Windows PowerShell 5.1 (built into Windows 10/11)" }
$policy = Get-ExecutionPolicy
if ($policy -in @("Restricted", "AllSigned")) {
    Warn "execution policy is ${policy}: run scripts as  powershell -ExecutionPolicy Bypass -File .\run-scenario.ps1 ...  or  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
} else { Ok "execution policy $policy" }

Write-Host ""
Write-Host "Java (the build and the clients need a JDK 25 or newer on JAVA_HOME)"
$jdkFix = "install a JDK 25+, e.g.  winget install EclipseAdoptium.Temurin.25.JDK  (or https://adoptium.net),`nthen set JAVA_HOME to its folder (System Properties > Environment Variables) and open a new terminal"
if (-not $env:JAVA_HOME) {
    Problem "JAVA_HOME is not set" $jdkFix
} elseif (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Problem "JAVA_HOME=$env:JAVA_HOME has no bin\java.exe" $jdkFix
} else {
    $verLine = (& cmd /c "`"$env:JAVA_HOME\bin\java.exe`" -version 2>&1" | Select-Object -First 1)
    $major = 0
    if ($verLine -match 'version "(\d+)') { $major = [int]$Matches[1] }
    if ($major -ge 25) { Ok "JAVA_HOME $env:JAVA_HOME ($verLine)" }
    else { Problem "JAVA_HOME is Java $major ($verLine); 25 or newer is needed" $jdkFix }
    if (-not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { Problem "JAVA_HOME is a JRE, not a JDK (no javac)" $jdkFix }
    $pathJava = Get-Command java -ErrorAction SilentlyContinue
    if ($pathJava -and -not $pathJava.Source.StartsWith($env:JAVA_HOME, [StringComparison]::OrdinalIgnoreCase)) {
        Ok "(java on PATH is a different one, $($pathJava.Source); Gradle uses JAVA_HOME, so that is fine)"
    }
}

Write-Host ""
Write-Host "Tools"
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) { Ok ("git " + ((& git --version) -replace '^git version ', '')) }
else { Problem "git is not on PATH" "winget install Git.Git  (needed by get-mod.ps1 and the worktrees of run-sharded.ps1)" }
$py = Get-Command python -ErrorAction SilentlyContinue
if ($py -and ((& cmd /c "python --version 2>&1") -match 'Python 3')) { Ok "python (optional: tools/*.py)" }
else { Warn "no python 3 on PATH - only tools/extract-patterns.py and tools/coverage-matrix.py need it" }

Write-Host ""
Write-Host "Configuration (testkit.properties, testkit.local.properties, TESTKIT_* variables)"
$cfg = Get-TestkitConfig $here
foreach ($k in @("jarsDir", "modJarPattern", "modSource", "modRepo", "prismInstances", "roomsInstance", "simInstance",
                 "windowScreen", "windowSlotsDir", "shardsDir", "worktreesDir")) {
    Write-Host ("  {0,-15} {1}   [{2}]" -f $k, $cfg[$k], $cfg.Source[$k])
}
if ($cfg.MainRoot -ne $cfg.Root) { Write-Host "  (this is a git worktree of $($cfg.MainRoot), whose testkit.local.properties is read too)" }

Write-Host ""
Write-Host "Mod under test"
if ($ModUnderTest -eq "") {
    $ModUnderTest = Find-TestkitSnapshotJar $cfg $Minecraft
}
if (-not $ModUnderTest) {
    Problem ("no mod jar: nothing matching " + $cfg.modJarPattern.Replace("{mc}", $Minecraft) + " under $($cfg.jarsDir)/*/") `
        ("./get-mod.ps1   (clones killer560s-mod and builds it into jarsDir)`nor pass -ModUnderTest <your jar> to run-scenario.ps1 (any Fabric mod), or -NoMod")
} elseif (-not (Test-Path $ModUnderTest)) {
    Problem "mod jar not found: $ModUnderTest" ""
} else {
    $info = Get-ModJarInfo $ModUnderTest
    if (-not $info) { Problem "$ModUnderTest has no fabric.mod.json" "pass a Fabric mod jar" }
    else {
        Ok "$($info.Id) $($info.Version) - $ModUnderTest (minecraft '$($info.Minecraft)')"
        $fits = @(@("26.1.2", "26.2") | Where-Object { (Test-McRange $info.Minecraft $_) -ne $false })
        if ($fits.Count -eq 0) { Problem "its minecraft range '$($info.Minecraft)' allows neither 26.1.2 nor 26.2" "build it for 26.1.2 or 26.2" }
        else { Ok "runs on Minecraft: $($fits -join ', ')" }
        if ($info.Id -ne "killer560smod") {
            Ok "not killer560smod: the generic scenarios run, killer560smod's own SKIP (run-suite.ps1 -Suite generic)"
        }
    }
}
if (Test-Path "$($cfg.modSource)/src/main/java/com/killer560/hub") { Ok "killer560s-mod source at $($cfg.modSource)" }
else { Warn "no killer560s-mod checkout at $($cfg.modSource): the logic suite's source checks will SKIP (get-mod.ps1 creates one)" }
if ($cfg.prismInstances -ne "" -and (Test-Path "$($cfg.prismInstances)/$($cfg.roomsInstance)/minecraft/config")) {
    Ok "room captures: $($cfg.prismInstances)/$($cfg.roomsInstance)"
} else {
    Warn "no room captures configured (prismInstances): dungeon sim scenarios that need them SKIP with that reason"
}

Write-Host ""
Write-Host "Monitors (test windows are placed and tiled on windowScreen)"
try {
    Add-Type -AssemblyName System.Windows.Forms
    foreach ($s in [System.Windows.Forms.Screen]::AllScreens) {
        $b = $s.Bounds
        Write-Host ("  {0,-14} {1},{2} {3}x{4}{5}" -f $s.DeviceName, $b.X, $b.Y, $b.Width, $b.Height, $(if ($s.Primary) { "  (primary)" } else { "" }))
    }
} catch { Warn "could not list monitors: $_" }
$scr = Resolve-TestkitScreen $cfg.windowScreen
if ($scr) { Ok ("test windows go to {0},{1} {2}x{3} - {4}; four runs get a quadrant each" -f $scr.X, $scr.Y, $scr.W, $scr.H, $scr.From) }
else { Ok "windowScreen=off: test windows are left where Minecraft opens them" }

Write-Host ""
Write-Host "Ports (a run uses server port P, the Hx bridge on P+5 and an HTTP fake on P+6)"
$listen = @{}
try {
    foreach ($c in (Get-NetTCPConnection -State Listen -ErrorAction Stop)) { $listen[[int]$c.LocalPort] = [int]$c.OwningProcess }
} catch {
    foreach ($line in (& netstat -ano -p tcp)) {
        if ($line -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)') { $listen[[int]$Matches[1]] = [int]$Matches[2] }
    }
}
function Owner([int]$p) {
    $proc = Get-Process -Id $listen[$p] -ErrorAction SilentlyContinue
    if ($proc) { return "$($proc.ProcessName).exe (pid $($listen[$p]))" } else { return "pid $($listen[$p])" }
}
$checks = [ordered]@{}
$propsFile = Join-Path $here "run/testserver/server.properties"
$current = 25565
if (Test-Path $propsFile) {
    $line = Select-String -Path $propsFile -Pattern '^server-port=(\d+)' | Select-Object -First 1
    if ($line) { $current = [int]$line.Matches[0].Groups[1].Value }
}
$checks["this checkout (server-port $current)"] = $current
if ($Port -gt 0) { $checks["-Port $Port"] = $Port }
for ($g = 1; $g -le 3; $g++) { $checks["run-sharded shard $g (BasePort 25900)"] = 25900 + 10 * $g }
for ($n = 1; $n -le 3; $n++) { $checks["parallel-suite worktree $n (BasePort 25700)"] = 25700 + 10 * $n }
foreach ($k in $checks.Keys) {
    $p = $checks[$k]
    $busy = @(@($p, ($p + 5), ($p + 6)) | Where-Object { $listen.ContainsKey($_) })
    if ($busy.Count -eq 0) { Ok "$k : $p, $($p + 5), $($p + 6) free" }
    else {
        $what = ($busy | ForEach-Object { "$_ held by " + (Owner $_) }) -join "; "
        Warn "$k : $what - a test server of your own still running is fine (it is replaced); otherwise pick another -Port / -BasePort"
    }
}
$others = @($listen.Keys | Where-Object { $_ -ge 25500 -and $_ -le 27000 } | Sort-Object)
if ($others.Count -gt 0) {
    Write-Host "  listening between 25500 and 27000 right now (avoid these, and these minus 5/6, as -Port):"
    foreach ($p in $others) { Write-Host "           $p  $(Owner $p)" }
}

Write-Host ""
Write-Host "Disk"
$drive = (Get-Item $here).PSDrive
if ($drive -and $drive.Free) {
    $gb = [math]::Round($drive.Free / 1GB, 1)
    if ($gb -lt 5) { Warn "only $gb GB free on $($drive.Name): - the first build downloads Minecraft and Fabric (~1-2 GB) and every worktree has its own build/" }
    else { Ok "$gb GB free on $($drive.Name):" }
}

Write-Host ""
if ($problems -eq 0) {
    Write-Host "doctor: no problems ($warnings warning(s)). Next: ./run-scenario.ps1 -Scenario smoke   (README, Quick start)"
    exit 0
}
Write-Host "doctor: $problems problem(s) to fix first, $warnings warning(s)."
exit 1
