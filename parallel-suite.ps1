# Runs several suites at once, each in its own git worktree with its own server port, and waits for them all.
#
#   ./parallel-suite.ps1 -Suites hx,menu -Max 2
#   ./parallel-suite.ps1 -Suites harness -BasePort 25565          # one worktree on 25575
#
# Why worktrees: Gradle locks the project directory, so two runs need two checkouts. Worktree n lives at
# C:/Users/Hunter/killer560s-mod-testkit-wt/<n> (n = 1, 2, ...), created with `git worktree add --detach` from THIS
# checkout's HEAD (committed work only - uncommitted changes here are NOT in the worktrees; the script says so), and
# moved to HEAD again on later runs. Each one has its own build/, run/testserver/ and .gradle/; the GrimAC jar is
# shared through the Gradle user home.
#
# Ports: suite n gets server port BasePort + 10n and Hx port BasePort + 10n + 5. The default BasePort 25700 keeps
# clear of 25565 (this checkout), the work-package ports 25570-25620 AND their Hx ports (25575-25625): the coverage
# plan's 25565+10n formula would put worktree 1's server on WP2's Hx port. Ports in use by other checkouts right now
# (e.g. 25591/25601) are not checked - pick a BasePort that avoids them.
#
# Windows: tiled in quarters of the left monitor (960x540 each) so concurrent clients do not stack.
# -Max caps concurrent runs (machine-wide guidance: at most 3 clients at once, fewer when others are running).
#
# Output: each suite's log at <worktree>/build/parallel-suite.log, its report at <worktree>/build/testkit-report/,
# and a table at the end. Exit code 0 only if every suite exited 0.

param(
    [Parameter(Mandatory = $true)][string[]]$Suites,
    [int]$Max = 3,
    [int]$BasePort = 25700,
    [string]$ModUnderTest = "",
    [string]$Minecraft = "26.1.2",
    [string[]]$Extra = @(),
    [int]$TimeoutSeconds = 1800,
    [string]$WorktreeRoot = "C:/Users/Hunter/killer560s-mod-testkit-wt"
)

$here = $PSScriptRoot
$Suites = @($Suites | ForEach-Object { $_ -split ',' } | Where-Object { $_ -ne '' })
$head = (& git -C $here rev-parse HEAD).Trim()
$dirty = & git -C $here status --porcelain --untracked-files=no
if ($dirty) {
    Write-Host "[parallel] NOTE: this checkout has uncommitted changes; worktrees run the COMMITTED HEAD $head only"
}

$tiles = @("-1920,361,960,540", "-960,361,960,540", "-1920,901,960,540", "-960,901,960,540")

function Prepare-Worktree([int]$n) {
    $path = "$WorktreeRoot/$n"
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Force -Path $WorktreeRoot | Out-Null
        # Through cmd so git's progress line on stderr is not turned into a PowerShell error record.
        cmd /c "git -C `"$here`" worktree add --detach `"$path`" $head 2>&1" | Out-Host
    } else {
        $wtDirty = & git -C $path status --porcelain --untracked-files=no
        if ($wtDirty) {
            throw "worktree $path has uncommitted changes; refusing to move it. Commit or discard them first."
        }
        cmd /c "git -C `"$path`" checkout --detach $head 2>&1" | Out-Host
    }
    return $path
}

$jobs = @()
$queue = New-Object System.Collections.Queue
for ($i = 0; $i -lt $Suites.Count; $i++) { $queue.Enqueue($i) }
$running = @{}
$results = @{}

while ($queue.Count -gt 0 -or $running.Count -gt 0) {
    while ($queue.Count -gt 0 -and $running.Count -lt $Max) {
        $i = $queue.Dequeue()
        $n = $i + 1
        $suite = $Suites[$i]
        $path = Prepare-Worktree $n
        $port = $BasePort + 10 * $n
        $log = "$path/build/parallel-suite.log"
        New-Item -ItemType Directory -Force -Path "$path/build" | Out-Null
        # -Command, not -File: -File binds every argument as a plain string, so a string[] like -Extra only ever got
        # its first element. Single-quoted values, with any ' doubled, survive -Command's parsing unchanged.
        $q = { param($v) "'" + ([string]$v).Replace("'", "''") + "'" }
        $cmd = "& " + (& $q "$path/run-suite.ps1") + " -Suite " + (& $q $suite) + " -Port $port -Window " +
               (& $q $tiles[$i % $tiles.Count]) + " -TimeoutSeconds $TimeoutSeconds"
        if ($ModUnderTest -ne "") { $cmd += " -ModUnderTest " + (& $q $ModUnderTest) }
        if ($Minecraft -ne "26.1.2") { $cmd += " -Minecraft " + (& $q $Minecraft) }
        if ($Extra.Count -gt 0) { $cmd += " -Extra " + (($Extra | ForEach-Object { & $q $_ }) -join ",") }
        $cmd += "; exit `$LASTEXITCODE"
        $p = Start-Process -FilePath "powershell" -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass",
            "-Command", "`"$cmd`"") -WorkingDirectory $path -PassThru `
            -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        $null = $p.Handle
        $running[$i] = $p
        Write-Host ("[parallel] started " + $suite + " in " + $path + " on port " + $port + " (hx " + ($port + 5) + "), pid " + $p.Id)
    }
    Start-Sleep -Seconds 5
    foreach ($i in @($running.Keys)) {
        $p = $running[$i]
        if ($p.HasExited) {
            $results[$i] = $p.ExitCode
            $running.Remove($i)
            Write-Host ("[parallel] " + $Suites[$i] + " finished with exit code " + $p.ExitCode)
        }
    }
}

Write-Host ""
Write-Host "[parallel] suite | worktree | port | exit | totals"
$worst = 0
for ($i = 0; $i -lt $Suites.Count; $i++) {
    $n = $i + 1
    $path = "$WorktreeRoot/$n"
    $summary = "$path/build/testkit-report/summary.md"
    $totals = "no report"
    if (Test-Path $summary) {
        $t = Select-String -Path $summary -Pattern '^- totals:' | Select-Object -First 1
        if ($t) { $totals = $t.Line.Substring(2) }
    }
    $code = $results[$i]
    if ($code -ne 0) { $worst = 1 }
    Write-Host ("[parallel] " + $Suites[$i] + " | " + $path + " | " + ($BasePort + 10 * $n) + " | " + $code + " | " + $totals)
}
exit $worst
