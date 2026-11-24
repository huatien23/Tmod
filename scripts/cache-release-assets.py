#!/usr/bin/env python3
"""Cache release package locations for the Termux dependency downloader."""
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

headers = {'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
           'Accept': 'application/vnd.github+json', 'User-Agent': 'Tmod-builder'}
assets = {}
for page in range(1, 20):
    url = f'https://api.github.com/repos/huatien23/Tmod/releases?per_page=100&page={page}'
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=90) as response:
                releases = json.load(response)
            break
        except Exception:
            if attempt == 4:
                raise
            time.sleep(5 * (attempt + 1))
    for release in releases:
        if not release['tag_name'].startswith(('tmod-main-', 'tmod-batch-', 'tmod-retry-')):
            continue
        for asset in release['assets']:
            if asset['name'].endswith('.deb'):
                digest = asset.get('digest') or ''
                assets.setdefault(asset['name'], [asset['browser_download_url'],
                                                   digest.removeprefix('sha256:') or 'SKIP_CHECKSUM'])
    if len(releases) < 100:
        break
Path(sys.argv[1], 'tmod-release-assets.json').write_text(json.dumps(assets))
print(f'Cached {len(assets)} release package locations')
