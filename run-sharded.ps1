# Runs ONE scenario filter faster by splitting it across several clients, each with its own dedicated test server.
#
#   ./run-sharded.ps1 -Scenario "smoke,-ui-,93-solve,96-ar" -Shards 3
#   ./run-sharded.ps1 -Suite hx,solve -Shards 3                       # suites.properties names, same as run-suite
#   ./run-sharded.ps1 -Scenario "smoke,proof,-ui-,-sim-,93-solve,96-ar,62-argrim,-menu-,-logic-" -Shards 3 -Minecraft both
#   ./run-sharded.ps1 -Scenario ... -Shards 3 -PlanOnly               # show the split, run nothing
#
# A Minecraft client can be on one server only, so "several dummy servers" means N clients, each with its own
# dedicated server (and Hx bridge port) in its own git worktree - Gradle locks a project dir, and run/testserver and
# build/ are per checkout. Worktree <WorktreeRoot>/<mc>-<k> for shard k of a version, created with `git worktree add
# --detach` from THIS checkout's HEAD (committed work only; the script says so) and moved to HEAD on later runs.
#
# How the split works
#   1. List mode: one client start (~20 s, no server, no world) with -PlistScenarios makes the HARNESS say which
#      names the filter selects, so the sharder never re-implements the matching. Each name comes with its UNIT:
#      one test class's runTest (a Session and its cases, the UI group that shares one singleplayer world, the 96-ar
#      cases that share one room, 02-seed build+load...). A unit is never split.
#   2. Units are balanced (longest first onto the least loaded shard) by each name's last measured seconds, kept in
#      <WorktreeRoot>/durations.json and refreshed from every shard's report; a name never measured gets a default.
#   3. Each shard runs run-scenario.ps1 with a filter of exactly its names (a Session case subset is written
#      "<session>:,<case>,..." - see CLAUDE.md), so its report rows are those of a normal run.
#   4. Reports and logs of every shard are copied to <OutDir> (default build/sharded-<timestamp>/), with ONE merged
#      summary.md / summary.json: a table per version, which shard ran each name, wall time vs the sum of shard times.
#      A selected name no shard reported is a MISSING row and fails the run. Exit code 0 only if every shard exited 0.
#
# Limits and ports
#   -Max caps clients running at once (machine guidance: at most 3, fewer if anything else is running). -Minecraft
#   both queues both versions' shards under that cap, longest first. Shard g (1-based, across versions) gets server
#   port BasePort + 10g and Hx port + 5; the default BasePort 25900 stays clear of 25565-25599 and 25700-25850.
#   Each client claims a screen slot through run-scenario.ps1 (a quadrant, or a finer grid past 4 live runs). Test clients stay muted: nothing here
#   passes -PtestVolume (build.gradle defaults to 0).
#   First use of a worktree builds it (a few minutes, once). Run with -PlanOnly after a first sharded run to warm them.

param(
    [string]$Scenario = "",
    [string[]]$Suite = @(),
    [int]$Shards = 3,
    [string]$Minecraft = "26.1.2",          # 26.1.2 | 26.2 | both
    [string]$ModUnderTest = "",             # one version only; for both use -Jar261 / -Jar262
    [string]$Jar261 = "",
    [string]$Jar262 = "",
    [string]$JarsDir = "C:/Users/Hunter/killer560s-mod-testkit-jars",
    [int]$Max = 3,
    [int]$BasePort = 25900,
    [string[]]$Extra = @(),
    [int]$TimeoutSeconds = 0,               # per shard; 0 = from the estimate (2.5x + 600 s, at least 900)
    [string]$WorktreeRoot = "C:/Users/Hunter/killer560s-mod-testkit-shards",
    [string]$OutDir = "",
    [string]$SeedDurations = "",            # a summary.json (e.g. an unsharded run's report) to take measured seconds from
    [switch]$SplitLarge,                    # also split a plain-scenario unit bigger than a fair share (see below)
    [switch]$PlanOnly
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ($OutDir -eq "") { $OutDir = (Join-Path $here "build/sharded-$stamp") }
$OutDir = $OutDir.Replace('\', '/')
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$runLog = "$OutDir/run-sharded.log"

function Say([string]$text) {
    $line = "[sharded " + (Get-Date -Format "HH:mm:ss") + "] " + $text
    Write-Host $line
    Add-Content -Path $runLog -Value $line -Encoding UTF8
}

# ---- inputs ------------------------------------------------------------------------------------------------------

$parts = @()
if ($Scenario -ne "") { $parts += @($Scenario -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }) }
if ($Suite.Count -gt 0) {
    $suiteProps = @{}
    foreach ($line in Get-Content (Join-Path $here "suites.properties")) {
        if ($line -match '^\s*([A-Za-z0-9_-]+)\s*=\s*(.*)$') { $suiteProps[$Matches[1]] = $Matches[2].Trim() }
    }
    foreach ($s in @($Suite | ForEach-Object { $_ -split ',' } | Where-Object { $_ -ne '' })) {
        if (-not $suiteProps.ContainsKey($s)) {
            Write-Host ("No suite '" + $s + "' in suites.properties. Known: " + (($suiteProps.Keys | Sort-Object) -join ", "))
            exit 4
        }
        $parts += @($suiteProps[$s] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    }
}
if ($parts.Count -eq 0) {
    Write-Host "Give -Scenario <filter> and/or -Suite <names>"
    exit 4
}
$filter = ($parts | Select-Object -Unique) -join ','
if ($Shards -lt 1) { Write-Host "-Shards must be at least 1"; exit 4 }
if ($Max -gt 3) { Say "NOTE: -Max $Max exceeds the machine guidance of 3 clients at once" }
if ($Extra -match 'testVolume') { Say "WARNING: -Extra passes -PtestVolume; test clients are muted by default and should stay that way unless asked" }

$versions = @()
switch ($Minecraft) {
    "both" { $versions = @("26.1.2", "26.2") }
    "26.1.2" { $versions = @("26.1.2") }
    "26.2" { $versions = @("26.2") }
    default { Write-Host "-Minecraft must be 26.1.2, 26.2 or both"; exit 4 }
}
if ($ModUnderTest -ne "" -and $versions.Count -gt 1) {
    Write-Host "-ModUnderTest names one jar; with -Minecraft both give -Jar261 and -Jar262"
    exit 4
}

# Literal Windows-style paths with forward slashes: cygpath has returned empty paths before.
function Resolve-Jar([string]$ver) {
    $given = ""
    if ($ModUnderTest -ne "") { $given = $ModUnderTest }
    elseif ($ver -eq "26.1.2") { $given = $Jar261 }
    else { $given = $Jar262 }
    if ($given -ne "") {
        if (-not (Test-Path $given)) { throw "mod jar not found: $given" }
        return (Get-Item $given).FullName.Replace('\', '/')
    }
    # Newest snapshot holding that version's cheat jar, resolved ONCE so every shard tests the same file.
    $snap = Get-ChildItem -Path $JarsDir -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        ForEach-Object { Get-ChildItem -Path $_.FullName -Filter "killer560smod-*-$ver-cheat.jar" -ErrorAction SilentlyContinue } |
        Select-Object -First 1
    if (-not $snap) { throw "no killer560smod-*-$ver-cheat.jar under $JarsDir; pass -Jar261/-Jar262/-ModUnderTest" }
    return $snap.FullName.Replace('\', '/')
}
$jars = @{}
foreach ($v in $versions) {
    $jars[$v] = Resolve-Jar $v
    if ($jars[$v] -notmatch ("-" + [regex]::Escape($v) + "-(cheat|legit)\.jar$")) {
        throw ("jar " + $jars[$v] + " is not named for Minecraft $v")
    }
}

$head = (& git -C $here rev-parse HEAD).Trim()
$dirty = & git -C $here status --porcelain --untracked-files=no
if ($dirty) { Say "NOTE: this checkout has uncommitted changes; worktrees run the COMMITTED HEAD $head only" }
Say "filter: $filter"
Say "shards per version: $Shards, versions: $($versions -join ', '), max clients at once: $Max, base port $BasePort, out $OutDir"
foreach ($v in $versions) { Say "mod jar $($v): $($jars[$v])" }

# ---- worktrees ---------------------------------------------------------------------------------------------------

function Prepare-Worktree([string]$path) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
        # Through cmd so git's progress line on stderr is not turned into a PowerShell error record.
        cmd /c "git -C `"$here`" worktree add --detach `"$path`" $head 2>&1" | Out-Null
    } else {
        $wtDirty = & git -C $path status --porcelain --untracked-files=no
        if ($wtDirty) { throw "worktree $path has uncommitted changes; refusing to move it. Commit or discard them first." }
        cmd /c "git -C `"$path`" checkout --detach $head 2>&1" | Out-Null
    }
    if (-not (Test-Path "$path/run-scenario.ps1")) { throw "worktree $path is not a testkit checkout" }
}

$q = { param($v) "'" + ([string]$v).Replace("'", "''") + "'" }

# One run-scenario.ps1 in a worktree, hidden, output to a log. -Command with single-quoted values: -File would bind
# only the first element of a string[] (see CLAUDE.md).
function Start-Run([string]$wt, [string]$ver, [string]$flt, [string]$jar, [int]$port, [string]$window,
                   [int]$timeout, [string[]]$extraArgs, [string]$log) {
    $cmd = "& " + (& $q "$wt/run-scenario.ps1") + " -Scenario " + (& $q $flt) + " -ModUnderTest " + (& $q $jar) +
           " -TimeoutSeconds $timeout -Window " + (& $q $window)
    if ($port -gt 0) { $cmd += " -Port $port" }
    if ($ver -ne "26.1.2") { $cmd += " -Minecraft " + (& $q $ver) }
    if ($extraArgs.Count -gt 0) { $cmd += " -Extra " + (($extraArgs | ForEach-Object { & $q $_ }) -join ",") }
    $cmd += "; exit `$LASTEXITCODE"
    New-Item -ItemType Directory -Force -Path (Split-Path $log -Parent) | Out-Null
    $p = Start-Process -FilePath "powershell" -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass",
        "-Command", "`"$cmd`"") -WorkingDirectory $wt -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $null = $p.Handle
    return $p
}

# ---- step 1: list mode -------------------------------------------------------------------------------------------

$durFile = "$WorktreeRoot/durations.json"
$dur = @{}
if (Test-Path $durFile) {
    $o = (Get-Content -Encoding UTF8 $durFile | Out-String) | ConvertFrom-Json
    foreach ($pr in $o.PSObject.Properties) { $dur[$pr.Name] = [double]$pr.Value }
}

# Seconds per name from report rows. A RAN row is closed when its test CLASS ends, so every RAN row of one class
# carries the class's whole duration; rows of one class with identical seconds share it instead.
function Add-Durations($cases) {
    $cases = @($cases)
    foreach ($c in $cases) {
        if ($c.status -ne 'RAN' -and $c.seconds -gt 0 -and $c.status -ne 'FAIL') { $dur[$c.name] = [double]$c.seconds }
    }
    foreach ($grp in ($cases | Where-Object { $_.status -eq 'RAN' -and $_.seconds -gt 0 } | Group-Object { [Math]::Round([double]$_.seconds, 1) })) {
        foreach ($c in $grp.Group) { $dur[$c.name] = [double]$c.seconds / $grp.Count }
    }
}

function Default-Seconds([string]$name) {
    if ($name -match '-ui-') { return 20 }
    if ($name -match '-logic-') { return 5 }
    if ($name -match '-solve-') { return 70 }
    if ($name -match '^96-ar') { return 60 }
    return 90
}
function Seconds-Of([string]$name, [double]$fallback) {
    if ($dur.ContainsKey($name) -and $dur[$name] -gt 0) { return $dur[$name] }
    if ($fallback -gt 0) { return $fallback }
    return (Default-Seconds $name)
}

if ($SeedDurations -ne "") {
    $so = (Get-Content -Encoding UTF8 $SeedDurations | Out-String) | ConvertFrom-Json
    Add-Durations $so.cases
    Say "seeded durations from $SeedDurations"
}

function Read-List([string]$file) {
    $rows = @()
    foreach ($line in (Get-Content -Encoding UTF8 $file)) {
        if ($line.StartsWith('#') -or $line.Trim() -eq '') { continue }
        $f = $line -split "`t"
        $parent = ""
        if ($f.Count -gt 4) { $parent = $f[4] }
        $rows += [pscustomobject]@{ Name = $f[0]; State = $f[1]; Unit = [int]$f[2]; Kind = $f[3]; Parent = $parent }
    }
    return $rows
}

$plans = @{}      # version -> @{ Units; Shards; All }
$listStart = Get-Date
foreach ($v in $versions) {
    $wt1 = "$WorktreeRoot/$v-1"
    Prepare-Worktree $wt1
    $listFile = "$OutDir/list-$v.tsv"
    Say "listing the scenarios for $v (one client start, no server) in $wt1"
    $lp = Start-Run $wt1 $v $filter $jars[$v] 0 "off" 600 @("-PlistScenarios=$listFile") "$OutDir/list-$v.log"
    $lp.WaitForExit()
    $null = $lp.Handle
    if ($lp.ExitCode -ne 0 -or -not (Test-Path $listFile)) {
        Say "list mode failed for $v (exit $($lp.ExitCode)); see $OutDir/list-$v.log"
        exit 5
    }
    $rows = Read-List $listFile
    $selected = @($rows | Where-Object { $_.State -ne 'no' })
    if ($selected.Count -eq 0) { Say "the filter selects nothing on $v"; exit 4 }

    # Units: one per test-class run that has anything selected.
    $units = @()
    foreach ($g in ($rows | Group-Object Unit)) {
        $sel = @($g.Group | Where-Object { $_.State -ne 'no' })
        if ($sel.Count -eq 0) { continue }
        $frag = @(); $est = 0.0; $names = @()
        $wholeSessions = @($sel | Where-Object { $_.Kind -eq 'session' -and $_.State -eq 'all' } | ForEach-Object { $_.Name })
        foreach ($r in $sel) {
            $names += $r.Name
            if ($r.Kind -eq 'session') {
                if ($r.State -eq 'all') {
                    $frag += $r.Name
                    $est += (Seconds-Of $r.Name 360)
                } else {
                    # Only some cases: "<session>:" selects the session without selecting every case.
                    $frag += ($r.Name + ":")
                    $est += 70
                }
            } elseif ($r.Kind -eq 'case') {
                if ($wholeSessions -notcontains $r.Parent) {
                    $frag += $r.Name
                    $est += (Seconds-Of $r.Name 15)
                }
            } else {
                $frag += $r.Name
                $est += (Seconds-Of $r.Name 0)
            }
        }
        $units += [pscustomobject]@{ Id = [int]$g.Name; Frag = $frag; Est = $est; Names = $names }
    }

    # -SplitLarge: one class of independent-by-name scenarios (96-ar) bigger than a fair share would set the wall time
    # alone. Cut it into contiguous chunks (order kept); each chunk pays the class's setup again (~40 s). Never a
    # Session or the UI group's world cases: only units made of plain scenario rows with no session in them.
    if ($SplitLarge -and $units.Count -gt 0) {
        $total = 0.0; foreach ($u in $units) { $total += $u.Est }
        $target = $total / $Shards
        $split = @()
        foreach ($u in $units) {
            $plain = (@($rows | Where-Object { $_.Unit -eq $u.Id -and $_.State -ne 'no' -and $_.Kind -ne 'scenario' }).Count -eq 0)
            if ($plain -and $u.Names.Count -gt 1 -and $u.Est -gt $target * 1.15 -and $u.Id -ne -1 -and -not ($u.Frag -match '-ui-')) {
                $c = [Math]::Min($Shards, [Math]::Min($u.Names.Count, [int][Math]::Ceiling($u.Est / $target)))
                $per = [int][Math]::Ceiling($u.Names.Count / $c)
                for ($i = 0; $i -lt $u.Names.Count; $i += $per) {
                    $sub = @($u.Names[$i..([Math]::Min($i + $per, $u.Names.Count) - 1)])
                    $split += [pscustomobject]@{ Id = $u.Id; Frag = $sub; Names = $sub; Est = ($u.Est * $sub.Count / $u.Names.Count + 40) }
                }
                Say "split unit $($u.Id) ($($u.Names[0])...) of $($u.Names.Count) names, est $([int]$u.Est) s, into $c chunks"
            } else { $split += $u }
        }
        $units = $split
    }

    # Longest first onto the least loaded shard.
    $n = [Math]::Min($Shards, $units.Count)
    $bins = @()
    for ($k = 0; $k -lt $n; $k++) { $bins += [pscustomobject]@{ K = $k + 1; Est = 0.0; Units = @() } }
    foreach ($u in ($units | Sort-Object @{ Expression = { $_.Est }; Descending = $true }, Id)) {
        $bin = $bins | Sort-Object Est, K | Select-Object -First 1
        $bin.Units += $u
        $bin.Est += $u.Est
    }
    $shardList = @()
    foreach ($bin in $bins) {
        $us = @($bin.Units | Sort-Object Id)
        $frag = @($us | ForEach-Object { $_.Frag })
        $names = @($us | ForEach-Object { $_.Names })
        $shardList += [pscustomobject]@{
            Version = $v; K = $bin.K; Est = $bin.Est; Filter = ($frag -join ','); Frag = $frag
            Names = $names; Units = $us.Count; Worktree = "$WorktreeRoot/$v-$($bin.K)"
        }
    }
    # A fragment is matched by substring, so check it selects nothing beyond what the shard was given.
    foreach ($s in $shardList) {
        foreach ($frag in $s.Frag) {
            if ($frag.EndsWith(':')) { continue }
            foreach ($r in $rows) {
                if ($r.Name.Contains($frag) -and ($s.Names -notcontains $r.Name)) {
                    Say "WARNING: shard $($s.K) part '$frag' also matches '$($r.Name)' (state $($r.State)), which is not in this shard"
                }
            }
        }
    }
    $plans[$v] = [pscustomobject]@{ Shards = $shardList; Rows = $rows; Selected = $selected; UnitCount = $units.Count }
}
$listSeconds = ((Get-Date) - $listStart).TotalSeconds

# ---- the plan ----------------------------------------------------------------------------------------------------

$g = 0
foreach ($v in $versions) {
    $pl = $plans[$v]
    Say ("$v : " + $pl.Selected.Count + " selected name(s) in " + $pl.UnitCount + " unit(s), split into " + $pl.Shards.Count + " shard(s)")
    foreach ($s in $pl.Shards) {
        $g++
        $s | Add-Member -NotePropertyName G -NotePropertyValue $g
        $s | Add-Member -NotePropertyName Port -NotePropertyValue ($BasePort + 10 * $g)
        $f = $s.Filter
        if ($f.Length -gt 110) { $f = $f.Substring(0, 107) + "..." }
        Say ("  shard $($s.K) [g$g] port $($s.Port)/hx $($s.Port + 5): $($s.Units) unit(s), $($s.Names.Count) name(s), est $([int]$s.Est) s :: $f")
    }
}
if ($PlanOnly) {
    Say "plan only; nothing run. Lists: $OutDir/list-*.tsv"
    exit 0
}

# ---- step 2: run the shards --------------------------------------------------------------------------------------

$jobs = @()
foreach ($v in $versions) { foreach ($s in $plans[$v].Shards) { $jobs += $s } }
$queue = New-Object System.Collections.Queue
foreach ($j in ($jobs | Sort-Object @{ Expression = { $_.Est }; Descending = $true }, G)) { $queue.Enqueue($j) }
$running = @{}      # G -> job
$slots = @{}        # slot index -> G
foreach ($j in $jobs) { Prepare-Worktree $j.Worktree }

$wallStart = Get-Date
try {
    while ($queue.Count -gt 0 -or $running.Count -gt 0) {
        while ($queue.Count -gt 0 -and $running.Count -lt $Max) {
            $j = $queue.Dequeue()
            $slot = 0
            while ($slots.ContainsKey($slot)) { $slot++ }
            $slots[$slot] = $j.G
            $to = $TimeoutSeconds
            if ($to -le 0) { $to = [Math]::Max(900, [int](2.5 * $j.Est + 600)) }
            $log = "$($j.Worktree)/build/parallel-suite.log"
            Remove-Item -Force -ErrorAction SilentlyContinue "$($j.Worktree)/build/testkit-report/summary.json"
            $p = Start-Run $j.Worktree $j.Version $j.Filter $jars[$j.Version] $j.Port "auto" $to $Extra $log
            $j | Add-Member -NotePropertyName Proc -NotePropertyValue $p -Force
            $j | Add-Member -NotePropertyName Slot -NotePropertyValue $slot -Force
            $j | Add-Member -NotePropertyName Started -NotePropertyValue (Get-Date) -Force
            $running[$j.G] = $j
            Say ("started $($j.Version) shard $($j.K) in $($j.Worktree) on port $($j.Port) (pid $($p.Id), deadline $to s)")
        }
        Start-Sleep -Seconds 5
        foreach ($key in @($running.Keys)) {
            $j = $running[$key]
            if ($j.Proc.HasExited) {
                $j | Add-Member -NotePropertyName Exit -NotePropertyValue $j.Proc.ExitCode -Force
                $j | Add-Member -NotePropertyName Ended -NotePropertyValue (Get-Date) -Force
                $running.Remove($key)
                $slots.Remove($j.Slot)
                Say ("finished $($j.Version) shard $($j.K) with exit code $($j.Exit) after " + [int]($j.Ended - $j.Started).TotalSeconds + " s")
            }
        }
    }
} finally {
    # Only processes THIS script started (and their children); never a blanket kill.
    foreach ($j in $running.Values) {
        if ($j.Proc -and -not $j.Proc.HasExited) {
            Say "stopping shard $($j.K) (pid $($j.Proc.Id)) and its children"
            cmd /c "taskkill /T /F /PID $($j.Proc.Id) >nul 2>&1" | Out-Null
        }
    }
}
$wallSeconds = ((Get-Date) - $wallStart).TotalSeconds

# ---- step 3: collect and merge -----------------------------------------------------------------------------------

$allRows = @{}      # version -> rows
$sumShard = 0.0
$failedShards = 0
foreach ($v in $versions) { $allRows[$v] = @() }
foreach ($j in $jobs) {
    $dest = "$OutDir/$($j.Version)/shard$($j.K)"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $b = "$($j.Worktree)/build"
    if (Test-Path "$b/testkit-report") { Copy-Item -Recurse -Force "$b/testkit-report" "$dest/report" }
    foreach ($pair in @(@("$b/parallel-suite.log", "gradle.log"), @("$b/parallel-suite.log.err", "gradle.err.log"),
            @("$b/testserver-console.log", "server-console.log"), @("$b/run/clientGameTest/logs/latest.log", "client-latest.log"))) {
        if (Test-Path $pair[0]) { Copy-Item -Force $pair[0] "$dest/$($pair[1])" }
    }
    Set-Content -Path "$dest/filter.txt" -Value $j.Filter -Encoding UTF8
    $secs = ($j.Ended - $j.Started).TotalSeconds
    $sumShard += $secs
    if ($j.Exit -ne 0) { $failedShards++ }
    $sj = "$dest/report/summary.json"
    if (Test-Path $sj) {
        $o = (Get-Content -Encoding UTF8 $sj | Out-String) | ConvertFrom-Json
        foreach ($c in @($o.cases)) {
            $allRows[$j.Version] += [pscustomobject]@{ Name = $c.name; Status = $c.status; Seconds = [double]$c.seconds
                Shard = $j.K; Detail = [string]$c.detail }
        }
        Add-Durations $o.cases
    }
}
$jsonDur = New-Object System.Collections.Specialized.OrderedDictionary
foreach ($k in ($dur.Keys | Sort-Object)) { $jsonDur[$k] = [Math]::Round($dur[$k], 1) }
[IO.File]::WriteAllText($durFile, ($jsonDur | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# sharded testkit run")
$md.Add("")
$md.Add("- filter: ``$filter``")
$md.Add("- when: $stamp, testkit HEAD $head")
$md.Add("- shards per version: $Shards, versions: $($versions -join ', '), max clients at once: $Max")
$failTotal = 0
$jsonOut = @{ filter = $filter; head = $head; versions = @{} }
foreach ($v in $versions) {
    $rows = @($allRows[$v])
    # Every selected name must have a row somewhere (a name run twice is flagged too).
    $missing = @()
    foreach ($s in $plans[$v].Shards) {
        foreach ($n in $s.Names) {
            $hit = @($rows | Where-Object { $_.Name -eq $n -or $_.Name.StartsWith($n + "#") })
            if ($hit.Count -eq 0) { $missing += [pscustomobject]@{ Name = $n; Status = "MISSING"; Seconds = 0.0; Shard = $s.K; Detail = "selected, but no shard reported a row for it" } }
        }
    }
    $rows = @($rows + $missing)
    $pass = @($rows | Where-Object { $_.Status -eq 'PASS' -or $_.Status -eq 'FLAGGED' }).Count
    $fail = @($rows | Where-Object { $_.Status -eq 'FAIL' -or $_.Status -eq 'MISSING' }).Count
    $other = $rows.Count - $pass - $fail
    $failTotal += $fail
    $vShards = @($jobs | Where-Object { $_.Version -eq $v })
    $vSum = 0.0; foreach ($j in $vShards) { $vSum += ($j.Ended - $j.Started).TotalSeconds }
    $vWall = (($vShards | ForEach-Object { $_.Ended } | Measure-Object -Maximum).Maximum - ($vShards | ForEach-Object { $_.Started } | Measure-Object -Minimum).Minimum).TotalSeconds
    $md.Add("")
    $md.Add("## Minecraft $v")
    $md.Add("")
    $md.Add("- mod jar: ``$($jars[$v])``")
    $md.Add("- totals: $pass passed, $fail failed, $other other (RAN/SKIPPED...), of $($rows.Count) rows")
    $md.Add("- shards: " + (($vShards | ForEach-Object { "#$($_.K) exit $($_.Exit) $([int]($_.Ended - $_.Started).TotalSeconds) s (est $([int]$_.Est) s, port $($_.Port))" }) -join "; "))
    $md.Add("- wall time for this version: $([int]$vWall) s; sum of its shard times: $([int]$vSum) s")
    $md.Add("")
    $md.Add("| name | status | s | shard | detail |")
    $md.Add("|---|---|---|---|---|")
    foreach ($r in ($rows | Sort-Object Shard, Name)) {
        $d = ($r.Detail -replace '\|', '\|' -replace "[\r\n]+", ' ')
        $md.Add("| $($r.Name) | $($r.Status) | " + ([string]::Format([Globalization.CultureInfo]::InvariantCulture, "{0:0.0}", $r.Seconds)) + " | $($r.Shard) | $d |")
    }
    $jsonOut.versions[$v] = @{ passed = $pass; failed = $fail; other = $other; wallSeconds = [int]$vWall; sumShardSeconds = [int]$vSum
        rows = @($rows | ForEach-Object { @{ name = $_.Name; status = $_.Status; seconds = $_.Seconds; shard = $_.Shard; detail = $_.Detail } }) }
}
$speed = 0.0
if ($wallSeconds -gt 0) { $speed = $sumShard / $wallSeconds }
$md.Add("")
$md.Add("## Timing")
$md.Add("")
$md.Add("- list phase (before the shards): $([int]$listSeconds) s")
$md.Add("- wall time of the shards: $([int]$wallSeconds) s; sum of shard times: $([int]$sumShard) s (the serial cost of these shards, " + ([string]::Format([Globalization.CultureInfo]::InvariantCulture, "{0:0.00}", $speed)) + "x)")
$md.Add("- shards that exited non-zero: $failedShards; failed or missing rows: $failTotal")
$jsonOut.listSeconds = [int]$listSeconds; $jsonOut.wallSeconds = [int]$wallSeconds; $jsonOut.sumShardSeconds = [int]$sumShard
$jsonOut.failedShards = $failedShards; $jsonOut.failedRows = $failTotal
[IO.File]::WriteAllLines("$OutDir/summary.md", $md, (New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText("$OutDir/summary.json", ($jsonOut | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))

Write-Host ""
$md | ForEach-Object { Write-Host $_ }
Say "merged report: $OutDir/summary.md"
if ($failedShards -eq 0 -and $failTotal -eq 0) { exit 0 }
exit 1
