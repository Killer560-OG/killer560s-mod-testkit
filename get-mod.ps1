# Gets killer560s-mod and builds the jars the scenarios test, into a snapshot folder under jarsDir.
#
#   ./get-mod.ps1                          # clone (or update its own clone), build 26.1.2 cheat + legit
#   ./get-mod.ps1 -Minecraft both          # 26.1.2 and 26.2
#   ./get-mod.ps1 -Ref some-branch         # a branch, tag or commit instead of the default branch
#   ./get-mod.ps1 -Repo C:/path/to/a/clone # clone from somewhere else (a local path works)
#
# Testing your OWN mod instead? You do not need this script: pass -ModUnderTest <your jar> to run-scenario.ps1, or put
# the jar in <jarsDir>/<any-folder>/ and set modJarPattern in testkit.local.properties (README, "Testing your own mod").
#
# Where things go (testkit.properties / testkit.local.properties, see ./doctor.ps1 for the resolved values):
#   modSource  the checkout. Cloned from modRepo if it does not exist. A checkout this script did NOT clone is never
#              fetched, switched or built unless you pass -BuildExisting (it may be someone's working copy).
#   jarsDir    the jars land in <jarsDir>/<branch>-<commit8>/, which run-scenario.ps1 and run-sharded.ps1 pick by
#              default (newest folder first). Snapshots never move under a run, unlike the mod's build/libs.
#
# How it builds (the mod's own CLAUDE.md): one Gradle run per variant, `gradlew build -PcheatBuild=true|false`, with
# build/classes and the generated BuildVariant source deleted in between - Gradle does not always regenerate
# BuildVariant when only the flag changes, and stale classes break the other variant. Each jar is then checked with
# `javap -constants` on com.killer560.hub.BuildVariant: CHEAT_FEATURES_ENABLED must match the variant asked for.
# Runs with --no-daemon, so nothing is left running afterwards.

param(
    [string]$Dest = "",
    [string]$Repo = "",
    [string]$Ref = "",
    [string]$Minecraft = "26.1.2",          # 26.1.2 | 26.2 | both
    [string[]]$Variants = @("cheat", "legit"),
    [string]$JarsDir = "",
    [switch]$BuildExisting
)

$here = $PSScriptRoot
. (Join-Path $here "tools/testkit-config.ps1")
$cfg = Get-TestkitConfig $here
if ($Dest -eq "") { $Dest = $cfg.modSource }
if ($Repo -eq "") { $Repo = $cfg.modRepo }
if ($JarsDir -eq "") { $JarsDir = $cfg.jarsDir }
$Dest = [IO.Path]::GetFullPath($Dest).Replace('\', '/')
$Variants = @($Variants | ForEach-Object { $_ -split ',' } | Where-Object { $_ -ne '' })

function Fail([string]$m) { Write-Host "[get-mod] $m"; exit 1 }
function Say([string]$m) { Write-Host "[get-mod] $m" }

$versions = switch ($Minecraft) {
    "both" { @("26.1.2", "26.2") }
    "26.1.2" { @("26.1.2") }
    "26.2" { @("26.2") }
    default { Fail "-Minecraft must be 26.1.2, 26.2 or both" }
}
# What the mod needs passed for 26.2 (its CLAUDE.md, "Two Minecraft versions, one source tree").
$mcFlags = @{ "26.1.2" = @(); "26.2" = @("-Pminecraft_version=26.2", "-Pfabric_api_version=0.160.0+26.2", "-Pmodmenu_version=20.0.2") }

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/javap.exe")) {
    Fail "JAVA_HOME must point at a JDK 25 or newer (javap is needed). Run ./doctor.ps1 for how to install one."
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail "git is not on PATH. Run ./doctor.ps1." }

$marker = "$Dest/.git/testkit-get-mod"
if (-not (Test-Path $Dest)) {
    Say "cloning $Repo into $Dest"
    New-Item -ItemType Directory -Force -Path (Split-Path $Dest -Parent) | Out-Null
    & git clone $Repo $Dest
    if ($LASTEXITCODE -ne 0) { Fail "git clone failed" }
    Set-Content -Path $marker -Value "cloned by killer560s-mod-testkit get-mod.ps1 from $Repo" -Encoding ASCII
    if ($Ref -ne "") {
        & git -C $Dest checkout --detach $Ref
        if ($LASTEXITCODE -ne 0) { Fail "git checkout $Ref failed" }
    }
} elseif (Test-Path $marker) {
    $dirty = & git -C $Dest status --porcelain --untracked-files=no
    if ($dirty) { Fail "$Dest has uncommitted changes; refusing to move it. Commit or discard them, or pass -Dest <new folder>." }
    Say "updating its own clone $Dest"
    & git -C $Dest fetch --tags origin
    if ($LASTEXITCODE -ne 0) { Fail "git fetch failed" }
    $target = $Ref
    if ($target -eq "") { $target = "origin/HEAD" }
    elseif (& git -C $Dest rev-parse --verify --quiet "origin/$Ref") { $target = "origin/$Ref" }
    & git -C $Dest checkout --detach $target
    if ($LASTEXITCODE -ne 0) { Fail "git checkout $target failed" }
} elseif ($BuildExisting) {
    Say "building the existing checkout $Dest as it is (-BuildExisting: no fetch, no checkout)"
} else {
    Fail ("$Dest exists and was not cloned by this script, so it is left alone (it may be a working copy). " +
          "Pass -BuildExisting to build it as it is, or -Dest <empty folder> for a fresh clone.")
}
if (-not (Test-Path "$Dest/gradlew.bat")) { Fail "$Dest is not a Gradle project (no gradlew.bat)" }

$sha = (& git -C $Dest rev-parse --short=8 HEAD).Trim()
$label = $Ref
if ($label -eq "") {
    $label = (& git -C $Dest rev-parse --abbrev-ref HEAD).Trim()
    if ($label -eq "HEAD") {
        $head = (& git -C $Dest rev-parse --abbrev-ref origin/HEAD 2>$null)
        if ($head) { $label = ([string]$head).Trim() -replace '^origin/', '' } else { $label = "rev" }
    }
}
$label = ($label -replace '[^A-Za-z0-9._-]', '_')
$snap = "$JarsDir/$label-$sha"
New-Item -ItemType Directory -Force -Path $snap | Out-Null
Say "commit $sha; jars go to $snap"

$built = @()
foreach ($mc in $versions) {
    foreach ($variant in $Variants) {
        $cheat = ($variant -eq "cheat")
        if (-not $cheat -and $variant -ne "legit") { Fail "unknown variant '$variant' (cheat or legit)" }
        # Between variants: the generated BuildVariant and the compiled classes, or the other variant leaks through.
        foreach ($d in @("$Dest/build/generated/sources/buildVariant", "$Dest/build/classes")) {
            if (Test-Path $d) { Remove-Item -Recurse -Force $d }
        }
        $gradleArgs = @("build", "-PcheatBuild=$($cheat.ToString().ToLower())", "--no-daemon", "--console=plain") + $mcFlags[$mc]
        Say "building Minecraft $mc ${variant}: gradlew $($gradleArgs -join ' ')"
        $p = Start-Process -FilePath "$Dest/gradlew.bat" -ArgumentList $gradleArgs -WorkingDirectory $Dest -NoNewWindow -PassThru
        $null = $p.Handle
        $p.WaitForExit()
        if ($p.ExitCode -ne 0) { Fail "the $mc $variant build failed (exit $($p.ExitCode)); see the Gradle output above" }
        $jar = Get-ChildItem -Path "$Dest/build/libs" -Filter "*-$mc-$variant.jar" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $jar) { Fail "the build passed but build/libs has no *-$mc-$variant.jar" }
        $javap = & "$env:JAVA_HOME/bin/javap.exe" -constants -cp $jar.FullName com.killer560.hub.BuildVariant 2>&1 | Out-String
        $want = "CHEAT_FEATURES_ENABLED = $($cheat.ToString().ToLower())"
        if ($javap -notmatch [regex]::Escape($want)) {
            Fail "$($jar.Name) is not a $variant build: javap does not show '$want'`n$javap"
        }
        Copy-Item -Force $jar.FullName "$snap/$($jar.Name)"
        $built += "$snap/$($jar.Name)"
        Say "OK $($jar.Name) ($want)"
    }
}
# The folder's time is what "newest snapshot" sorts on.
(Get-Item $snap).LastWriteTime = Get-Date
Say "done. Built:"
$built | ForEach-Object { Write-Host "  $_" }
Say "run-scenario.ps1 and run-sharded.ps1 now default to these (newest folder under $JarsDir)."
exit 0
