#!/usr/bin/env python3
"""Publish newly rebuilt aarch64 debs as flat APT repositories in Releases."""

import email.utils
import gzip
import hashlib
import http.client
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


OWNER = 'huatien23'
REPO = 'Tmod'
API = f'https://api.github.com/repos/{OWNER}/{REPO}'
PAGES_PACKAGES = 'https://huatien23.github.io/Tmod/apt/dists/stable/main/binary-aarch64/Packages.gz'
TOKEN = os.environ['GITHUB_TOKEN']
HEADERS = {
    'Authorization': f'Bearer {TOKEN}',
    'Accept': 'application/vnd.github+json',
    'User-Agent': 'Tmod-package-publisher',
}


def api(path, method='GET', payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    headers = dict(HEADERS)
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(API + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read()
            return json.loads(data) if data else None
    except urllib.error.HTTPError as error:
        if error.code == 404 and method == 'GET':
            return None
        raise


def upload_asset(release_id, name, data):
    connection = http.client.HTTPSConnection('uploads.github.com', timeout=180)
    path = f'/repos/{OWNER}/{REPO}/releases/{release_id}/assets?name={urllib.parse.quote(name)}'
    connection.putrequest('POST', path)
    for key, value in HEADERS.items():
        connection.putheader(key, value)
    connection.putheader('Content-Type', 'application/octet-stream')
    connection.putheader('Content-Length', str(data.stat().st_size if isinstance(data, Path) else len(data)))
    connection.endheaders()
    if isinstance(data, Path):
        with data.open('rb') as stream:
            while chunk := stream.read(1024 * 1024):
                connection.send(chunk)
    else:
        connection.send(data)
    response = connection.getresponse()
    body = response.read()
    if response.status != 201:
        raise RuntimeError(f'Upload {name}: HTTP {response.status}: {body[:400]!r}')
    return json.loads(body)


def release_for(shard):
    tag = f'tmod-main-{shard:03d}'
    release = api('/releases/tags/' + tag)
    if release is None:
        release = api('/releases', 'POST', {
            'tag_name': tag,
            'target_commitish': 'main',
            'name': f'Tmod arm64 package repository {shard:03d}',
            'body': 'Flat APT repository for packages rebuilt for com.huatiencode.',
            'draft': False,
            'prerelease': True,
        })
    assets = assets_for(release['id'])
    return release, assets


def assets_for(release_id):
    assets = {}
    page = 1
    while True:
        batch = api(f'/releases/{release_id}/assets?per_page=100&page={page}')
        assets.update((asset['name'], asset) for asset in batch)
        if len(batch) < 100:
            return assets
        page += 1


def stanzas(index):
    result = {}
    for stanza in index.strip().split('\n\n'):
        if not stanza:
            continue
        filename = next(line[10:] for line in stanza.splitlines() if line.startswith('Filename: '))
        result[filename] = stanza
    return result


def package_stanza(path):
    control = subprocess.check_output(['dpkg-deb', '-f', str(path)], text=True).strip()
    hashes = {name: constructor() for name, constructor in (
        ('MD5sum', hashlib.md5), ('SHA1', hashlib.sha1), ('SHA256', hashlib.sha256))}
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            for digest in hashes.values():
                digest.update(chunk)
    fields = {
        'Filename': path.name,
        'Size': str(path.stat().st_size),
        **{name: digest.hexdigest() for name, digest in hashes.items()},
    }
    return control + '\n' + '\n'.join(f'{key}: {value}' for key, value in fields.items())


def release_index(index):
    plain = ('\n\n'.join(index[name] for name in sorted(index)) + '\n\n').encode()
    compressed = gzip.compress(plain, mtime=0)
    release = (
        'Origin: Tmod\n'
        'Suite: stable\n'
        'Codename: stable\n'
        'Architectures: aarch64 all\n'
        f'Date: {email.utils.formatdate(usegmt=True)}\n'
    )
    for algorithm in ('MD5Sum', 'SHA256'):
        release += algorithm + ':\n'
        for name, data in (('Packages', plain), ('Packages.gz', compressed)):
            digest = hashlib.md5(data).hexdigest() if algorithm == 'MD5Sum' else hashlib.sha256(data).hexdigest()
            release += f' {digest} {len(data):16d} {name}\n'
    return {'Packages': plain, 'Packages.gz': compressed, 'Release': release.encode()}


def main():
    output = Path(sys.argv[1])
    with urllib.request.urlopen(PAGES_PACKAGES, timeout=30) as response:
        existing = stanzas(gzip.decompress(response.read()).decode())
    shards = []
    published = {Path(filename).name for filename in existing}
    for shard_number in range(1000):
        release = api(f'/releases/tags/tmod-main-{shard_number:03d}')
        if release is None:
            break
        assets = assets_for(release['id'])
        index = stanzas(urllib.request.urlopen(assets['Packages']['browser_download_url'], timeout=30).read().decode()) if 'Packages' in assets else {}
        repaired = False
        for name in assets:
            path = output / name
            if name.endswith('.deb') and name not in index and path.is_file():
                index[name] = package_stanza(path)
                repaired = True
        if repaired:
            publish_index(release['id'], assets, index)
            print(f'Repaired index for {release["tag_name"]}')
        published.update(index)
        shards.append((release, assets, index))
    candidates = [path for path in sorted(output.glob('*.deb'))
                  if path.name not in published and not path.name.startswith(('dpkg-perl_', 'dpkg-scanpackages_'))]
    if not candidates:
        print('No new debs to publish')
        return
    print('New debs:', len(candidates))
    shard = len(shards) - 1 if shards and len(shards[-1][1]) < 900 else len(shards)
    if shard < len(shards):
        release, assets, index = shards[shard]
    else:
        release, assets = release_for(shard)
        index = {}
    changed = False
    for path in candidates:
        if path.name in assets:
            continue
        if len(assets) >= 900:
            if changed:
                publish_index(release['id'], assets, index)
            shard += 1
            release, assets = release_for(shard)
            index = stanzas(urllib.request.urlopen(assets['Packages']['browser_download_url'], timeout=30).read().decode()) if 'Packages' in assets else {}
            changed = False
        asset = upload_asset(release['id'], path.name, path)
        assets[path.name] = asset
        index[path.name] = package_stanza(path)
        changed = True
        print(f'Uploaded {path.name} to {release["tag_name"]}')
    if changed:
        publish_index(release['id'], assets, index)


def publish_index(release_id, assets, index):
    for name, data in release_index(index).items():
        if name in assets:
            api(f'/releases/assets/{assets[name]["id"]}', 'DELETE')
        upload_asset(release_id, name, data)
    print(f'Published index with {len(index)} packages in release {release_id}')


if __name__ == '__main__':
    main()
