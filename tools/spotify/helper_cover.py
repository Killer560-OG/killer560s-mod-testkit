"""Runs the helper script's own SaveCover on a thumbnail built from a PNG file (a RandomAccessStreamReference, the same
type Windows hands over for a player's cover), with no real player involved. Usage: cover_test.py <java file> <out dir>
Prints whether SaveCover wrote the 64x64 cover and the error when it did not."""
import base64
import os
import struct
import subprocess
import sys
import zlib

java, outdir = sys.argv[1], sys.argv[2]
src = open(java, encoding='utf-8').read().split('\n')
start = next(i for i, l in enumerate(src) if l.strip().startswith('static final String SCRIPT = """'))
end = next(i for i in range(start + 1, len(src)) if src[i].strip() == '$lastKey = $null')
script = '\n'.join(l[12:] if l.startswith(' ' * 12) else l.lstrip() for l in src[start + 1:end]) + '\n'


def png(w, h, rgb):
    raw = b''.join(b'\x00' + bytes(rgb) * w for _ in range(h))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + \
        chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')


thumb = os.path.join(outdir, 'thumb-160x90.png')
open(thumb, 'wb').write(png(160, 90, (200, 30, 220)))   # a wide picture, as a browser's thumbnail is
cover = os.path.join(outdir, 'cover-out.png')
if os.path.exists(cover):
    os.remove(cover)
script += r'''
$sfType = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$file = Await ($sfType::GetFileFromPathAsync($env:K560_TEST_THUMB)) $sfType
$ref = [Windows.Storage.Streams.RandomAccessStreamReference, Windows.Storage.Streams, ContentType = WindowsRuntime]::CreateFromFile($file)
$props = [pscustomobject]@{ Thumbnail = $ref }
try { $ok = SaveCover $props; "SaveCover returned $ok" } catch { "SaveCover FAILED: " + $_.Exception.Message }
if (Test-Path $coverPath) { Add-Type -AssemblyName System.Drawing; $i = [System.Drawing.Image]::FromFile($coverPath); "cover " + $i.Width + "x" + $i.Height; $i.Dispose() } else { "no cover file" }
'''
env = dict(os.environ)
env['K560_SPOTIFY_PARENT'] = '0'
env['K560_SPOTIFY_COVER'] = os.path.normpath(cover)
env['K560_SPOTIFY_SOURCE'] = '0'
env['K560_SPOTIFY_AUDIO'] = base64.b64encode(b'').decode()
env['K560_TEST_THUMB'] = os.path.normpath(thumb)
enc = base64.b64encode(script.encode('utf-16-le')).decode()
r = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', enc],
                   capture_output=True, env=env, stdin=subprocess.DEVNULL, timeout=60)
print(r.stdout.decode("utf-8", "replace").strip()); print("STDERR:", r.stderr.decode("utf-8", "replace")[-1500:])
