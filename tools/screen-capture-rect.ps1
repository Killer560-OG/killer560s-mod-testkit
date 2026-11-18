# Read-only screen capture of one rectangle of the desktop, the way GDI capture tools do it (BitBlt from the screen DC
# with CAPTUREBLT, which includes layered windows), saved as PNG. Used by 1118-sim-streamsafe-map to show what a screen
# recorder sees over the test client's Dungeon Map. Never moves the mouse, types or brings a window forward.
param(
  [Parameter(Mandatory = $true)][int]$X,
  [Parameter(Mandatory = $true)][int]$Y,
  [Parameter(Mandatory = $true)][int]$Width,
  [Parameter(Mandatory = $true)][int]$Height,
  [Parameter(Mandatory = $true)][string]$Out,
  [long]$Game = 0   # the test client's window: points of the rectangle under any other window are counted ("covered N")
)
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class DpiAware {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(long point);
  [DllImport("user32.dll")] public static extern IntPtr GetDC(IntPtr h);
  [DllImport("user32.dll")] public static extern int ReleaseDC(IntPtr h, IntPtr dc);
  [DllImport("gdi32.dll")] public static extern bool BitBlt(IntPtr dst, int x, int y, int w, int h, IntPtr src, int sx, int sy, int rop);
  // BitBlt from the screen DC with SRCCOPY | CAPTUREBLT (0x40CC0020), which includes layered windows: what GDI
  // screen recorders do. (System.Drawing's CopyFromScreen rejects the CaptureBlt flag on this .NET.)
  public static bool Grab(IntPtr dstDc, int x, int y, int w, int h) {
    IntPtr screen = GetDC(IntPtr.Zero);
    try { return BitBlt(dstDc, 0, 0, w, h, screen, x, y, 0x40CC0020); } finally { ReleaseDC(IntPtr.Zero, screen); }
  }
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr h, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr h, uint cmd);
  // 1 when the top window at (x, y) is not the game window or a window it owns (WindowFromPoint skips click-through
  // layered windows, so the overlay itself never counts).
  public static int Covered(int x, int y, long game) {
    IntPtr root = GetAncestor(WindowFromPoint(((long) y << 32) | (uint) x), 2);
    return root.ToInt64() == game || GetWindow(root, 4).ToInt64() == game ? 0 : 1;
  }
}
'@
# Per-monitor aware v2 (-4), so the rectangle is in the same physical pixels the game's window reports.
[void][DpiAware]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))
$covered = 0
if ($Game -ne 0) {
  foreach ($fx in 0.1, 0.5, 0.9) { foreach ($fy in 0.1, 0.5, 0.9) {
    $covered += [DpiAware]::Covered([int]($X + $Width * $fx), [int]($Y + $Height * $fy), $Game)
  } }
}
$bmp = New-Object System.Drawing.Bitmap $Width, $Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [DpiAware]::Grab($hdc, $X, $Y, $Width, $Height)
$g.ReleaseHdc($hdc)
$g.Dispose()
if (-not $ok) { Write-Output "BitBlt failed"; exit 1 }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "captured $Width x $Height at $X,$Y -> $Out; covered $covered of 9"
