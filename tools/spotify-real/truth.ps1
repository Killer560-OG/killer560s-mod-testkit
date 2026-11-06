# Testkit 2871 (spotreal-1106): drives the REAL Spotify desktop app through Windows' media session (play, pause, seek,
# next) and records every reading Windows gives, so the case can compare the mod's lyric clock with Spotify's own
# position at every frame. Spotify's position at wall time w is the reading with the latest LastUpdatedTime <= w:
# Position + (w - LastUpdatedTime) while playing. Measured against the real audio (loopback, spotreal-1106) that model
# is within about 70 ms of the sound. Output TSV: "R wall status pos updated title" rows and "A wall action" rows.
# usage: truth.ps1 -Out file.tsv
param([string]$Out)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$type) { $t = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op)); if (-not $t.Wait(3000)) { throw 'timeout' }; $t.Result }
$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
$mgr = Await ($mgrType::RequestAsync()) $mgrType
$s = $null
foreach ($x in $mgr.GetSessions()) { if ([string]$x.SourceAppUserModelId -like '*Spotify*') { $s = $x } }
if ($null -eq $s) { [IO.File]::WriteAllLines($Out, @("E no Spotify session")); exit 2 }
$rows = New-Object System.Collections.Generic.List[string]
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
function Title { try { [string](Await ($s.TryGetMediaPropertiesAsync()) $propsType).Title } catch { '' } }
$title = Title
function Sample([int]$ms) {
  $end = (Now) + $ms; $i = 0
  while ((Now) -lt $end) {
    $tl = $s.GetTimelineProperties(); $pb = $s.GetPlaybackInfo()
    if ($i % 10 -eq 0) { $script:title = Title }
    $rows.Add("R`t$(Now)`t$([int]$pb.PlaybackStatus)`t$([long]$tl.Position.TotalMilliseconds)`t$($tl.LastUpdatedTime.ToUnixTimeMilliseconds())`t$script:title")
    $i++
    Start-Sleep -Milliseconds 20
  }
}
function Act([string]$a) {
  $rows.Add("A`t$(Now)`t$a")
  if ($a -eq 'play') { [void](Await ($s.TryPlayAsync()) ([bool])) }
  elseif ($a -eq 'pause') { [void](Await ($s.TryPauseAsync()) ([bool])) }
  elseif ($a -eq 'next') { [void](Await ($s.TrySkipNextAsync()) ([bool])) }
  elseif ($a -like 'seek:*') { [void](Await ($s.TryChangePlaybackPositionAsync([long]$a.Substring(5) * 10000)) ([bool])) }
}
[void](Await ($s.TryPauseAsync()) ([bool])); Start-Sleep -Milliseconds 600
Act 'seek:30000'; Sample 1500
Act 'play'; Sample 9000
Act 'pause'; Sample 3000
Act 'play'; Sample 7000
Act 'seek:90000'; Sample 7000
Act 'seek:20000'; Sample 6000
Act 'next'; Sample 9000
Act 'pause'; Sample 1500
[IO.File]::WriteAllLines($Out, $rows)
