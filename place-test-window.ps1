# Moves the gametest client's window onto a chosen monitor, so a test run does not pop up over whatever
# the person is actually playing on their main monitor.
#
# Started detached by build.gradle before runClientGameTest, because the window does not exist yet at that
# point and Minecraft offers no way to ask for a position. It polls for the client, moves it once, and exits.
#
# It identifies the right window by the PROCESS COMMAND LINE containing this project's directory, not by
# window title: the title is the same "Minecraft*" as any other instance, and moving the wrong one would
# drag a real game off-screen.
#
# Without -X/-Y/-Width/-Height it takes the whole screen named by windowScreen in testkit.properties ("auto": the first
# monitor that is not the primary one, else the primary one) - see tools/testkit-config.ps1.
param(
  [int]$X = 0,
  [int]$Y = 0,
  [int]$Width = 0,
  [int]$Height = 0,
  # build.gradle passes this checkout's root; it is anchored with a TRAILING SLASH below. The old default, the
  # bare word 'killer560s-mod-testkit', matched every sibling checkout too, so starting a run in one checkout
  # "cleared the leftover client" of another one mid-run and moved its window (pzB moved pzA's pid 54312,
  # 2026-10-04).
  [string]$Marker = '',
  [int]$TimeoutSeconds = 180
)

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class WinPlace {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll", SetLastError=true)] public static extern int GetWindowLong(IntPtr h, int i);
  [DllImport("user32.dll", SetLastError=true)] public static extern int SetWindowLong(IntPtr h, int i, int v);
  public struct RECT { public int Left, Top, Right, Bottom; }
}
'@

if (-not $PSBoundParameters.ContainsKey('X') -or $Width -le 0 -or $Height -le 0) {
  . (Join-Path $PSScriptRoot 'tools/testkit-config.ps1')
  $screen = Resolve-TestkitScreen (Get-TestkitConfig $PSScriptRoot).windowScreen
  if ($null -eq $screen) { exit 0 }   # windowScreen=off
  $X = $screen.X; $Y = $screen.Y; $Width = $screen.W; $Height = $screen.H
}

# Logged to a file, because this runs detached and its console output goes nowhere - and "the placer ran"
# is not the same claim as "the window moved".
if ($Marker -eq '') { $Marker = $PSScriptRoot }
$Marker = $Marker.Replace('\', '/').TrimEnd('/') + '/'
function Test-OurClient($cl) {
  # Slashes normalised (the client's command line carries backslash paths), the marker anchored by its trailing
  # slash, and only the gametest client itself, never a Gradle daemon.
  return $cl -and ($cl.Replace('\', '/') -like ("*" + $Marker + "*")) -and ($cl -like '*fabric.dli.env=client*')
}

$log = Join-Path $PSScriptRoot 'build/place-test-window.log'
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
function Say($m) { Write-Output $m; Add-Content -Path $log -Value ((Get-Date -Format 'HH:mm:ss') + '  ' + $m) }
Say ("watching for a window with marker '" + $Marker + "' -> " + $X + "," + $Y + " " + $Width + "x" + $Height)

# Before watching for a new window, clear any client left over from a previous run. A gametest client that
# outlived its gradle invocation keeps the run directory's jars open, and the NEXT run then dies in
# deleteGameTestRunDir with an IOException that looks nothing like its real cause. That cost two runs before it
# was understood, so it is handled here rather than left as a thing to remember.
$stale = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { Test-OurClient $_.CommandLine }
foreach ($x in $stale) {
  try { Stop-Process -Id $x.ProcessId -Force -ErrorAction Stop; Say ("closed a leftover client, pid " + $x.ProcessId) } catch {}
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$moved = $false

while (-not $moved -and (Get-Date) -lt $deadline) {
  $candidates = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
      Where-Object { Test-OurClient $_.CommandLine }
  foreach ($c in $candidates) {
    $proc = Get-Process -Id $c.ProcessId -ErrorAction SilentlyContinue
    if (-not $proc -or $proc.MainWindowHandle -eq 0) { continue }
    # Borderless: strip the caption, thick frame and borders so it covers the monitor edge to edge with no
    # title bar, rather than sitting on it as a window. GWL_STYLE = -16; WS_CAPTION|WS_THICKFRAME|WS_BORDER|
    # WS_DLGFRAME|WS_SYSMENU = 0x00CC0000 | 0x00040000 | 0x00800000.
    $h = $proc.MainWindowHandle
    $style = [WinPlace]::GetWindowLong($h, -16)
    $stripped = $style -band (-bnot (0x00C00000 -bor 0x00040000 -bor 0x00800000 -bor 0x00080000))
    [WinPlace]::SetWindowLong($h, -16, $stripped) | Out-Null
    # SWP_NOZORDER (0x4) | SWP_NOACTIVATE (0x10) | SWP_FRAMECHANGED (0x20): apply the new style, but do not
    # raise it or steal focus from whatever is being played on the main monitor.
    [WinPlace]::SetWindowPos($h, [IntPtr]::Zero, $X, $Y, $Width, $Height, 0x34) | Out-Null
    Start-Sleep -Milliseconds 400
    $r = New-Object WinPlace+RECT
    [WinPlace]::GetWindowRect($h, [ref]$r) | Out-Null
    Say ("borderless: moved pid " + $c.ProcessId + " to " + $r.Left + "," + $r.Top + " " + ($r.Right - $r.Left) + "x" + ($r.Bottom - $r.Top))
    $moved = $true
    break
  }
  if (-not $moved) { Start-Sleep -Milliseconds 500 }
}

if (-not $moved) { Say "gametest window never appeared within the timeout - nothing moved" }
