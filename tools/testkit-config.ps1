# Machine configuration for every script in this repo. Dot-source it:   . (Join-Path $PSScriptRoot "tools/testkit-config.ps1")
#
# One lookup, in this order (first non-empty value wins):
#   1. the script's own parameter (each script handles that itself)
#   2. an environment variable TESTKIT_<KEY> (jarsDir -> TESTKIT_JARS_DIR)
#   3. testkit.local.properties in THIS checkout (gitignored, per machine)
#   4. testkit.local.properties in the MAIN checkout, when this is a git worktree (shard and suite worktrees have no
#      untracked files of their own, so they read the main checkout's)
#   5. testkit.properties (committed, generic)
#   6. the built-in default below
# build.gradle implements the same lookup for the values the gametest client needs; keep the two in step.

function Get-TestkitMainCheckout([string]$root) {
    # A worktree's .git is a FILE: "gitdir: <main>/.git/worktrees/<name>", and that folder's commondir names <main>/.git.
    $git = Join-Path $root ".git"
    if (Test-Path -LiteralPath $git -PathType Leaf) {
        $line = Get-Content -LiteralPath $git -TotalCount 1
        if ($line -match '^gitdir:\s*(.+)$') {
            $gd = $Matches[1].Trim()
            if (-not [IO.Path]::IsPathRooted($gd)) { $gd = Join-Path $root $gd }
            $cd = Join-Path $gd "commondir"
            if (Test-Path -LiteralPath $cd) {
                $common = [IO.Path]::GetFullPath((Join-Path $gd ((Get-Content -LiteralPath $cd -TotalCount 1).Trim())))
                return (Split-Path $common -Parent)
            }
        }
    }
    return $root
}

function Read-TestkitProperties([string]$file) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $file)) { return $map }
    foreach ($line in [IO.File]::ReadAllLines($file, [Text.Encoding]::UTF8)) {
        $t = $line.Trim()
        if ($t -eq "" -or $t.StartsWith("#") -or $t.StartsWith("!")) { continue }
        $eq = $t.IndexOf("=")
        if ($eq -le 0) { continue }
        $map[$t.Substring(0, $eq).Trim()] = $t.Substring($eq + 1).Trim()
    }
    return $map
}

function Get-TestkitEnvName([string]$key) {
    return "TESTKIT_" + ([regex]::Replace($key, '([a-z0-9])([A-Z])', '$1_$2')).ToUpperInvariant()
}

# Returns a hashtable of resolved values (forward-slash paths) plus .Source[key] saying where each came from.
function Get-TestkitConfig([string]$root) {
    $root = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    $main = Get-TestkitMainCheckout $root
    $parent = Split-Path $main -Parent
    $name = Split-Path $main -Leaf
    $layers = @(
        @{ Name = "testkit.local.properties"; Map = (Read-TestkitProperties (Join-Path $root "testkit.local.properties")) }
    )
    if ($main -ne $root) {
        $layers += @{ Name = "main checkout testkit.local.properties"; Map = (Read-TestkitProperties (Join-Path $main "testkit.local.properties")) }
    }
    $layers += @{ Name = "testkit.properties"; Map = (Read-TestkitProperties (Join-Path $root "testkit.properties")) }

    $defaults = [ordered]@{
        jarsDir        = (Join-Path $parent "$name-jars")
        modSource      = (Join-Path $parent "killer560s-mod")
        modRepo        = "https://github.com/Killer560-OG/killer560s-mod.git"
        modJarPattern  = "killer560smod-*-{mc}-cheat.jar"
        prismInstances = ""
        roomsInstance  = "26.1.2 (Mod Only Test)"
        simInstance    = "Map Logger"
        windowScreen   = "auto"
        windowSlotsDir = ""
        shardsDir      = (Join-Path $parent "$name-shards")
        worktreesDir   = (Join-Path $parent "$name-wt")
    }
    $cfg = @{ Root = $root.Replace('\', '/'); MainRoot = $main.Replace('\', '/'); Source = @{} }
    foreach ($key in $defaults.Keys) {
        $value = $null
        $from = "default"
        $envValue = [Environment]::GetEnvironmentVariable((Get-TestkitEnvName $key))
        if ($envValue) {
            $value = $envValue; $from = "env " + (Get-TestkitEnvName $key)
        } else {
            foreach ($layer in $layers) {
                if ($layer.Map.ContainsKey($key) -and $layer.Map[$key] -ne "") { $value = $layer.Map[$key]; $from = $layer.Name; break }
            }
        }
        if ($null -eq $value) { $value = $defaults[$key] }
        if ($key -match 'Dir$|Source$|Instances$' -and $value -ne "") {
            if (-not [IO.Path]::IsPathRooted($value)) { $value = Join-Path $main $value }
            $value = [IO.Path]::GetFullPath($value).TrimEnd('\', '/').Replace('\', '/')
        }
        $cfg[$key] = $value
        $cfg.Source[$key] = $from
    }
    if ($cfg.windowSlotsDir -eq "") {
        $cfg.windowSlotsDir = $cfg.jarsDir + "/.window-slots"
        $cfg.Source.windowSlotsDir = "default (jarsDir/.window-slots)"
    }
    return $cfg
}

# The newest snapshot jar for one Minecraft version: <jarsDir>/<folder>/<modJarPattern with {mc}>. $null when none.
function Find-TestkitSnapshotJar($cfg, [string]$mc) {
    $pattern = $cfg.modJarPattern.Replace("{mc}", $mc)
    $hit = Get-ChildItem -LiteralPath $cfg.jarsDir -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Filter $pattern -ErrorAction SilentlyContinue } |
        Select-Object -First 1
    if ($hit) { return $hit.FullName.Replace('\', '/') }
    return $null
}

# id, version, name and the minecraft range of a mod jar, from its fabric.mod.json. $null when it has none.
function Get-ModJarInfo([string]$jar) {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
        $zip = [IO.Compression.ZipFile]::OpenRead($jar)
        try {
            $entry = $zip.GetEntry("fabric.mod.json")
            if (-not $entry) { return $null }
            $reader = New-Object IO.StreamReader($entry.Open(), [Text.Encoding]::UTF8)
            $text = $reader.ReadToEnd()
            $reader.Close()
        } finally { $zip.Dispose() }
        # Strip a BOM and control characters some jars carry; fabric.mod.json allows raw newlines in strings.
        $o = ($text.TrimStart([char]0xFEFF) -replace "[\x00-\x08\x0B\x0C\x0E-\x1F]", " ") | ConvertFrom-Json
        $mc = ""
        if ($o.depends -and ($o.depends.PSObject.Properties.Name -contains "minecraft")) {
            $mc = @($o.depends.minecraft) -join " || "
        }
        return @{ Id = [string]$o.id; Version = [string]$o.version; Name = [string]$o.name; Minecraft = $mc }
    } catch {
        return $null
    }
}

# Does Minecraft version $ver satisfy a fabric.mod.json range ("~26.1", ">=26.1 <26.2", "26.1.x", "*", a || b)?
# $null when the range cannot be read (the caller then keeps its default).
function Test-McRange([string]$range, [string]$ver) {
    if ($range -eq "") { return $null }
    function Split-Ver([string]$v) { return @($v.Split('.') | ForEach-Object { if ($_ -match '^\d+$') { [int]$_ } else { -1 } }) }
    function Cmp-Ver($a, $b) {
        for ($i = 0; $i -lt [Math]::Max($a.Count, $b.Count); $i++) {
            $x = 0; $y = 0
            if ($i -lt $a.Count) { $x = $a[$i] }
            if ($i -lt $b.Count) { $y = $b[$i] }
            if ($x -lt 0 -or $y -lt 0) { return 0 }   # a wildcard component matches anything
            if ($x -ne $y) { return [Math]::Sign($x - $y) }
        }
        return 0
    }
    $v = Split-Ver $ver
    $parsedAny = $false
    foreach ($alt in ($range -split '\|\|')) {
        $ok = $true
        $parts = @($alt.Trim() -split '\s+' | Where-Object { $_ -ne "" })
        if ($parts.Count -eq 0) { continue }
        foreach ($p in $parts) {
            if ($p -eq "*") { $parsedAny = $true; continue }
            if ($p -notmatch '^(>=|<=|>|<|=|~|\^)?(\d+(?:\.(?:\d+|x|X|\*))*)(?:[-+].*)?$') { return $null }
            $parsedAny = $true
            $op = $Matches[1]; $r = Split-Ver $Matches[2]
            $c = Cmp-Ver $v $r
            switch ($op) {
                ">=" { if ($c -lt 0) { $ok = $false } }
                "<=" { if ($c -gt 0) { $ok = $false } }
                ">"  { if ($c -le 0) { $ok = $false } }
                "<"  { if ($c -ge 0) { $ok = $false } }
                "~"  {
                    # ~X.Y[.Z]: >= it, same X.Y
                    if ($c -lt 0) { $ok = $false }
                    elseif ($v.Count -lt 2 -or $r.Count -lt 2 -or $v[0] -ne $r[0] -or $v[1] -ne $r[1]) { $ok = $false }
                }
                "^"  { if ($c -lt 0 -or $v[0] -ne $r[0]) { $ok = $false } }
                default {
                    # exact, with "26.1" matching 26.1 only and "26.1.x" matching any 26.1.*
                    $exact = ($r.Count -eq $v.Count) -or ($r -contains -1)
                    if ($c -ne 0 -or -not $exact) { $ok = $false }
                }
            }
        }
        if ($ok) { return $true }
    }
    if (-not $parsedAny) { return $null }
    return $false
}

# The monitor rectangle test windows tile: "x,y,w,h" given explicitly, or for "auto" the first monitor that is NOT the
# primary one (leftmost first), else the primary monitor's working area. $null for "off".
function Resolve-TestkitScreen([string]$spec) {
    if ($spec -eq "off") { return $null }
    if ($spec -match '^\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*$') {
        return @{ X = [int]$Matches[1]; Y = [int]$Matches[2]; W = [int]$Matches[3]; H = [int]$Matches[4]; From = "configured" }
    }
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        $screens = @([System.Windows.Forms.Screen]::AllScreens)
        $other = @($screens | Where-Object { -not $_.Primary } | Sort-Object { $_.Bounds.X })
        if ($other.Count -gt 0) {
            $b = $other[0].Bounds
            return @{ X = $b.X; Y = $b.Y; W = $b.Width; H = $b.Height; From = "auto: second monitor $($other[0].DeviceName)" }
        }
        $w = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
        return @{ X = $w.X; Y = $w.Y; W = $w.Width; H = $w.Height; From = "auto: primary monitor (no second one)" }
    } catch {
        return @{ X = 0; Y = 0; W = 1280; H = 720; From = "auto: monitor detection failed ($_)" }
    }
}
