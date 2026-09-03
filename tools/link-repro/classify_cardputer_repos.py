# Classify Cardputer catalog repos by display/input library via the GitHub API (needs `gh` auth).
# Input: https://api.launcherhub.net/giveMeTheList (saved as launcher_list.json next to this file).
# Output: cardputer_classified.json {repo: {status, license, stars, pio, ino, libs, files}}; resumable.
import json, subprocess, re, time, os, sys, base64
HERE = os.path.dirname(os.path.abspath(__file__))
items = json.load(open(os.path.join(HERE, 'launcher_list.json')))
def gh(it):
    m = re.search(r'https?://github\.com/([\w.-]+)/([\w.-]+)', it.get('github') or '')
    return f"{m.group(1)}/{m.group(2).removesuffix('.git')}" if m else None
repos = sorted({gh(it) for it in items if it.get('category') == 'cardputer' and gh(it)})
out_path = os.path.join(HERE, 'cardputer_classified.json')
done = json.load(open(out_path)) if os.path.exists(out_path) else {}
def api(path):
    r = subprocess.run(['gh', 'api', path], capture_output=True, text=True, timeout=60)
    return r.stdout if r.returncode == 0 else None
LIBS = ['M5Cardputer', 'M5Unified', 'M5GFX', 'LovyanGFX', 'TFT_eSPI', 'Adafruit_GFX', 'lvgl', 'M5Stack', 'M5StickCPlus']
def classify(repo):
    info = api(f'repos/{repo}')
    if not info: return {'status': 'missing'}
    meta = json.loads(info); branch = meta.get('default_branch', 'main')
    tree = api(f'repos/{repo}/git/trees/{branch}?recursive=1')
    if not tree: return {'status': 'no-tree', 'license': (meta.get('license') or {}).get('spdx_id')}
    paths = [t['path'] for t in json.loads(tree).get('tree', []) if t['type'] == 'blob']
    pio = [p for p in paths if p.endswith('platformio.ini')]; inos = [p for p in paths if p.endswith('.ino')]
    srcs = [p for p in paths if re.search(r'\.(ino|cpp|h|hpp)$', p)][:40]
    blob = ''
    for p in (pio[:1] + inos[:2] + [s for s in srcs if 'main' in s.lower()][:2]):
        c = api(f'repos/{repo}/contents/{p}?ref={branch}')
        if c:
            try: blob += base64.b64decode(json.loads(c).get('content', '')).decode(errors='replace')
            except Exception: pass
    libs = [k for k in LIBS if re.search(r'\b' + re.escape(k) + r'\b', blob)]
    return {'status': 'ok', 'license': (meta.get('license') or {}).get('spdx_id'), 'stars': meta.get('stargazers_count'),
            'pio': bool(pio), 'ino': bool(inos), 'libs': libs, 'files': len(paths)}
for i, repo in enumerate(repos):
    if repo in done: continue
    try: done[repo] = classify(repo)
    except Exception as e: done[repo] = {'status': 'error', 'err': str(e)[:80]}
    json.dump(done, open(out_path, 'w'))
    print(i, repo, done[repo], flush=True); time.sleep(0.4)
print('DONE', len(done))
