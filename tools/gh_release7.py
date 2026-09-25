import os, subprocess, urllib.request, json, time

def token():
    cred = subprocess.run(['git', 'credential', 'fill'],
                          input='protocol=https\nhost=github.com\n\n',
                          capture_output=True, text=True).stdout
    return [l.split('=', 1)[1] for l in cred.splitlines() if l.startswith('password=')][0]

TOK = token()
H = {'Authorization': 'Bearer ' + TOK, 'User-Agent': 'kaya-release', 'Accept': 'application/vnd.github+json'}
API = 'https://api.github.com/repos/kayefande-droid/kaya'

def api(path, data=None):
    req = urllib.request.Request(API + path, headers=H,
                                 data=json.dumps(data).encode() if data else None)
    return json.load(urllib.request.urlopen(req, timeout=60))

rel = None
try:
    rel = api('/releases/tags/v1.1.7')
    print('release exists, id', rel['id'])
except urllib.error.HTTPError as e:
    if e.code != 404:
        raise

body = """Kaya v1.1.7 - the fail-open release: the engine can no longer stall or break your internet.

**Fixed - the "VPN breaks my internet" bugs (architecture-level)**
- **DNS never blocks the tunnel anymore.** Lookups are answered from cache instantly; on a miss the resolver race runs in the background and the answer is delivered to the requesting app directly. A slow upstream now delays one lookup - never all traffic.
- **No more dead domains on resolver failure.** If every resolver fails (bad Wi-Fi, captive portal), Kaya keeps serving the last known-good answer for 30 minutes instead of returning a negative reply. Old-but-working beats correctly-dead.
- **Large uploads/downloads no longer stall.** The TCP terminator advertised packets bigger than the tunnel could carry (MSS 1400 inside an MTU-1280 tunnel); it now matches the MTU exactly, and all client->upstream data is pumped by background threads so a slow upload can't freeze the read loop.
- **Self-healing tunnel watchdog.** If the tunnel interface ever wedges (network switch race, dead fd), Kaya detects it and rebuilds the interface in place - traffic resumes on its own.
- **Kaya's own traffic is excluded from the tunnel** at the OS level, so the engine can never loop into itself.

**Site**
- Fixed game-icon colors (an undefined CSS variable made them dim gray), anchored sections now scroll below the sticky nav, visible keyboard focus rings, and a new honest FAQ: "Can the engine break my internet?"

SHA-256 checksums in SHA256SUMS.txt. The in-app updater verifies them before installing anything.
"""

if rel is None:
    rel = api('/releases', {
        'tag_name': 'v1.1.7',
        'target_commitish': 'main',
        'name': 'v1.1.7 - fail-open engine: DNS and TCP can never stall the tunnel',
        'body': body,
        'draft': False,
        'prerelease': False,
    })
    print('created release id', rel['id'])

for name in ['Kaya.apk', 'Kaya-arm64.apk', 'Kaya-arm32.apk', 'SHA256SUMS.txt']:
    url = rel['assets_url'].split('?')[0] + '?name=' + name
    have = {a['name']: a for a in json.load(urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=60))}
    path = os.path.join('dist', name)
    if name in have:
        print('asset exists, deleting for re-upload:', name)
        dreq = urllib.request.Request(have[name]['url'], headers=H, method='DELETE')
        try:
            urllib.request.urlopen(dreq, timeout=60)
        except Exception as ex:
            print('delete failed (continuing):', ex)
        time.sleep(2)
    up = urllib.request.Request(
        'https://uploads.github.com/repos/kayefande-droid/kaya/releases/%d/assets?name=%s' % (rel['id'], name),
        headers={'Authorization': 'Bearer ' + TOK, 'User-Agent': 'kaya-release',
                 'Content-Type': 'application/octet-stream'},
        data=open(path, 'rb').read(), method='POST')
    for attempt in range(4):
        try:
            a = json.load(urllib.request.urlopen(up, timeout=600))
            print('uploaded', a['name'], a['size'], a['state'])
            break
        except Exception as ex:
            print('retry', attempt, name, ex)
            time.sleep(4)
print('DONE')
