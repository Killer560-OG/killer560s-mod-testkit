# Read-only screen capture of one rectangle of the desktop, the way GDI capture tools do it (BitBlt from the screen DC
# with CAPTUREBLT, which includes layered windows), saved as PNG. Used by 1118-sim-streamsafe-map to show what a screen
# recorder sees over the test client's Dungeon Map. Never moves the mouse, types or brings a window forward.
param(
  [Parameter(Mandatory = $true)][int]$X,
  [Parameter(Mandatory = $true)][int]$Y,
  [Parameter(Mandatory = $true)][int]$Width,
  [Parameter(Mandatory = $true)][int]$Height,
  [Parameter(Mandatory = $true)][string]$Out
)
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class DpiAware {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
}
'@
# Per-monitor aware v2 (-4), so the rectangle is in the same physical pixels the game's window reports.
[void][DpiAware]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))
$bmp = New-Object System.Drawing.Bitmap $Width, $Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$op = [System.Drawing.CopyPixelOperation]::SourceCopy -bor [System.Drawing.CopyPixelOperation]::CaptureBlt
$g.CopyFromScreen($X, $Y, 0, 0, (New-Object System.Drawing.Size $Width, $Height), $op)
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "captured $Width x $Height at $X,$Y -> $Out"
