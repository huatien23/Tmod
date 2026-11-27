#!/usr/bin/env python3
"""List every current Termux main recipe in bounded build batches."""

import json
import os
import sys
import urllib.request
from pathlib import Path

url = 'https://api.github.com/repos/termux/termux-packages/git/trees/master?recursive=1'
request = urllib.request.Request(url, headers={
    'User-Agent': 'Tmod-full-builder',
    'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
    'Accept': 'application/vnd.github+json',
})
with urllib.request.urlopen(request, timeout=60) as response:
    tree = json.load(response)
if tree.get('truncated'):
    raise RuntimeError('Termux recipe tree was truncated')
available = {entry['path'][9:-9] for entry in tree['tree']
             if entry['path'].startswith('packages/') and entry['path'].endswith('/build.sh')}
if len(sys.argv) > 1 and sys.argv[1] == '--missing':
    recipes = sorted(set(Path('build-audit/missing-recipes.txt').read_text().split()) & available)
elif len(sys.argv) > 2 and sys.argv[1] == '--file':
    recipes = sorted(set(Path(sys.argv[2]).read_text().split()) & available)
else:
    recipes = sorted(available)
print(json.dumps([' '.join(recipes[i:i + 1]) for i in range(0, len(recipes), 1)]))
