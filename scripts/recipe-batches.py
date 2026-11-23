#!/usr/bin/env python3
"""List every current Termux main recipe in bounded build batches."""

import json
import os
import urllib.request

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
recipes = sorted({entry['path'][9:-9] for entry in tree['tree']
                  if entry['path'].startswith('packages/') and entry['path'].endswith('/build.sh')})
print(json.dumps([' '.join(recipes[i:i + 10]) for i in range(0, len(recipes), 10)]))
