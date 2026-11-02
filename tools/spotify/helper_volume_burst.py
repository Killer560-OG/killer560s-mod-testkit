"""Runs the mod's media-session helper script outside the game: extracts SCRIPT and AUDIO_SOURCE from
SpotifyMediaSession.java, starts powershell with them, sends volume commands only (never a transport command, which
would reach a real player on this PC), and prints what came back. Usage: run_helper.py <java file> <cover path> <mode>"""
import base64
import os
import subprocess
import sys
import time
import threading

java, cover, mode = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(java, encoding='utf-8').read().split('\n')


def block(name):
    start = next(i for i, l in enumerate(src) if l.strip().startswith('static final String ' + name + ' = """'))
    end = next(i for i in range(start + 1, len(src)) if src[i].strip() == '""";')
    lines = src[start + 1:end]
    return '\n'.join(l[12:] if l.startswith(' ' * 12) else l.lstrip() for l in lines) + '\n'


script = block('SCRIPT')
audio = block('AUDIO_SOURCE')
env = dict(os.environ)
env['K560_SPOTIFY_PARENT'] = '0'
env['K560_SPOTIFY_COVER'] = cover
env['K560_SPOTIFY_SOURCE'] = mode
env['K560_SPOTIFY_AUDIO'] = base64.b64encode(audio.encode('utf-8')).decode()
enc = base64.b64encode(script.encode('utf-16-le')).decode()
p = subprocess.Popen(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                      '-EncodedCommand', enc], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, env=env)
out = []


def reader():
    for raw in p.stdout:
        out.append((time.time(), raw.decode('utf-8', 'replace').rstrip('\r\n')))


t = threading.Thread(target=reader, daemon=True)
t.start()
time.sleep(4)
p.stdin.write(b'volq\n')
p.stdin.flush()
time.sleep(3)
t0 = time.time()
burst = ''.join('vol %d\n' % v for v in range(100, 49, -1))   # a slider dragged from 100 down to 50: 51 commands
p.stdin.write(burst.encode())
p.stdin.flush()
time.sleep(3)
p.stdin.close()
p.wait(timeout=20)
err = p.stderr.read().decode('utf-8', 'replace')
readings = [l for _, l in out if l.startswith('S\t') or l == 'N' or l == 'E']
results = [(ts - t0, l) for ts, l in out if l.startswith('C\t')]
skipped = [l for _, l in out if l.startswith('K\t')]
print('readings:', len(readings), 'first:', (readings[0].split('\t')[0] if readings else None),
      'fields:', (len(readings[-1].split('\t')) if readings else 0),
      'source app:', (readings[-1].split('\t')[11] if readings and len(readings[-1].split('\t')) > 11 else None),
      'cover seq:', (readings[-1].split('\t')[7] if readings and len(readings[-1].split('\t')) > 7 else None))
print('E lines:', sum(1 for l in readings if l == 'E'))
print('command results:', len(results))
for dt, l in results:
    print('  %+.2fs %s' % (dt, l.replace('\t', ' | ')))
print('volume commands skipped:', len(skipped), 'last skipped:', skipped[-1].replace('\t', ' ') if skipped else None)
print('cover file:', os.path.exists(cover), os.path.getsize(cover) if os.path.exists(cover) else 0)
print("reading kinds:", sorted(set(l.split("\t")[0] for _, l in out if not l.startswith(("K", "C")))))
