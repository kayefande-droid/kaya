import os, subprocess, urllib.request, urllib.error, json, time

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

def upload_assets(rel, distdir, names):
    for name in names:
        url = rel['assets_url'].split('?')[0] + '?name=' + name
        have = {a['name']: a for a in json.load(urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=60))}
        path = os.path.join(distdir, name)
        if name in have:
            print('  deleting existing', name)
            dreq = urllib.request.Request(have[name]['url'], headers=H, method='DELETE')
            try:
                urllib.request.urlopen(dreq, timeout=60)
            except Exception as ex:
                print('  delete failed (continuing):', ex)
            time.sleep(2)
        up = urllib.request.Request(
            'https://uploads.github.com/repos/kayefande-droid/kaya/releases/%d/assets?name=%s' % (rel['id'], name),
            headers={'Authorization': 'Bearer ' + TOK, 'User-Agent': 'kaya-release',
                     'Content-Type': 'application/octet-stream'},
            data=open(path, 'rb').read(), method='POST')
        for attempt in range(4):
            try:
                a = json.load(urllib.request.urlopen(up, timeout=600))
                print('  uploaded', a['name'], a['size'], a['state'])
                break
            except Exception as ex:
                print('  retry', attempt, name, ex)
                time.sleep(4)

NAMES = ['Kaya.apk', 'Kaya-arm64.apk', 'Kaya-arm32.apk', 'SHA256SUMS.txt']

body = """Kaya v1.1.9 - games can always connect.

**Fixed - "no connection" error in games while Kaya is armed**
The engine's tunnel no longer routes your traffic. In v1.1.8 and earlier, arming Kaya captured ALL of the device's traffic (full-tunnel); games whose anti-cheat or netcode refuses tunneled devices then failed to start with a "no connection" error on both WiFi and mobile data. v1.1.9 switches to **DNS-only routing**: the tunnel carries name lookups and nothing else, so every game connection goes straight out over your normal WiFi/mobile-data interface - exactly as if Kaya were off.
- Instant fallback for DNS-over-TCP/TLS (ports 53/853): Android's Private-DNS probes get an immediate refusal instead of a slow stall, so lookups resolve in milliseconds via Kaya's native UDP DNS engine.
- Launch crash hardened: notification-channel creation is now fail-open (some wedged system states could crash every app start).
- Correct versionCode numbering restored (1.1.9 = 2015).

SHA-256 checksums in SHA256SUMS.txt. The in-app updater verifies them before installing anything.
"""

rel = None
try:
    rel = api('/releases/tags/v1.1.9')
    print('v1.1.9 exists, id', rel['id'])
except urllib.error.HTTPError as e:
    if e.code != 404:
        raise

if rel is None:
    rel = api('/releases', {
        'tag_name': 'v1.1.9',
        'target_commitish': 'main',
        'name': 'v1.1.9 - DNS-only routing: games always connect',
        'body': body,
        'draft': False,
        'prerelease': False,
    })
    print('created v1.1.9 release id', rel['id'])

upload_assets(rel, 'dist', NAMES)
print('DONE')
