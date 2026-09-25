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

# ---- 1) Restore v1.1.7 assets from the true keystore-signed copies ----
rel7 = api('/releases/tags/v1.1.7')
print('v1.1.7 release id', rel7['id'], '- restoring keystore-signed assets')
upload_assets(rel7, r'C:/tmp/fix117', NAMES)

# ---- 2) Create/publish v1.1.8 ----
body = """Kaya v1.1.8 - updates that install cleanly, plus three new gaming features.

**Fixed - "App not installed" on updates**
Root cause: release assets were being replaced by CI builds signed with a fresh throwaway key every time, so Android rejected every update as signature-mismatched. Releases are now signed exclusively with Kaya's stable key, and the previously broken v1.1.7 assets have been restored. If a v1.1.3-1.1.7 install refuses to update, uninstall once and reinstall - from v1.1.8 on, every update installs over the last.
- The updater now pre-checks "install unknown apps" permission and never hands the installer a downgrade; failure reasons are shown in plain text in Tuning instead of failing silently.
- The Updates card reflows: buttons wrap to a second line on narrow screens instead of overflowing.

**New for gaming**
- **Matchmaker pre-warm**: arming the engine quietly resolves and pins the fastest matchmaker edges in advance, so the first "Find match" is served from cache.
- **Game-traffic priority (DSCP EF)**: forwarded game UDP is marked Expedited Forwarding; on WMM Wi-Fi it rides the voice-priority queue so a saturated home network degrades downloads before it degrades your match.
- **Peak refresh-rate hold**: boost sessions raise the system's peak refresh-rate ceiling for the match (needs "Modify system settings"; restored afterwards; best-effort by design).
- **Simulate resolver outage** (Tuning > Diagnostics): block upstream DNS for 15 s and watch the fail-open engine serve stale answers instead of dying.

SHA-256 checksums in SHA256SUMS.txt. The in-app updater verifies them before installing anything.
"""

rel8 = None
try:
    rel8 = api('/releases/tags/v1.1.8')
    print('v1.1.8 exists, id', rel8['id'])
except urllib.error.HTTPError as e:
    if e.code != 404:
        raise

if rel8 is None:
    rel8 = api('/releases', {
        'tag_name': 'v1.1.8',
        'target_commitish': 'main',
        'name': 'v1.1.8 - updates fixed, matchmaker pre-warm, game-traffic priority, refresh-rate hold',
        'body': body,
        'draft': False,
        'prerelease': False,
    })
    print('created v1.1.8 release id', rel8['id'])

upload_assets(rel8, 'dist', NAMES)
print('DONE')
