#!/usr/bin/env python3
"""Dispatch another package build for recipes that failed in a completed run."""

import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path

TOKEN = os.environ['CONTINUE_TOKEN']
RUN_ID = os.environ['RUN_ID']
REPO = os.environ.get('GITHUB_REPOSITORY', 'huatien23/Tmod')
API = 'https://api.github.com'
HEADERS = {
    'Authorization': 'Bearer ' + TOKEN,
    'Accept': 'application/vnd.github+json',
    'User-Agent': 'tmod-continue',
    'Content-Type': 'application/json',
}
STATE = Path('build-audit/continue-last.txt')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def request(url, method='GET', payload=None, headers=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers=headers or HEADERS, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        if error.code in (301, 302, 303, 307, 308) and error.headers.get('Location'):
            return error.code, error.headers.get('Location').encode()
        raise


def failed_from_logs():
    status, body = request(f'{API}/repos/{REPO}/actions/runs/{RUN_ID}/jobs?per_page=100')
    if status != 200:
        raise RuntimeError(f'job list returned {status}')
    names = set()
    for job in json.loads(body).get('jobs', []):
        if not job['name'].startswith('build'):
            continue
        code, location = request(f"{API}/repos/{REPO}/actions/jobs/{job['id']}/logs")
        if code not in (301, 302, 303, 307, 308):
            print('log unavailable', job['id'], code)
            continue
        log_request = urllib.request.Request(location.decode(), headers={'User-Agent': 'tmod-continue'})
        with urllib.request.urlopen(log_request, timeout=180) as response:
            for raw in response:
                line = re.sub(r'^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s*', '', raw.decode('utf-8', 'replace').strip())
                match = re.fullmatch(r'Failed recipe: ([A-Za-z0-9+._-]+)', line)
                if match:
                    names.add(match.group(1))
    return names


def dispatch_apk():
    status, body = request(
        f'{API}/repos/{REPO}/actions/workflows/tmod-build.yml/dispatches',
        'POST',
        {'ref': 'main'},
    )
    print('apk', status, body[:80])
    if status != 204:
        raise SystemExit(f'apk dispatch returned {status}')


def main():
    failed = sorted(failed_from_logs())
    print('failed', len(failed))
    if not failed:
        print('package batch has no failed recipes')
        dispatch_apk()
        return
    previous = set(STATE.read_text().split()) if STATE.is_file() else set()
    if previous == set(failed):
        print('same failures as the last round, stopping')
        dispatch_apk()
        return
    remote = 'https://x-access-token:${CONTINUE_TOKEN}@github.com/huatien23/Tmod.git'
    if os.system(f'git fetch "{remote}" main && git checkout -B main FETCH_HEAD') != 0:
        raise SystemExit('could not update main')
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text('\n'.join(failed) + '\n')
    if os.system('git add build-audit/continue-last.txt && git -c user.name=tmod-continue -c user.email=tmod-continue@localhost commit -m "Record recipes still failing"') != 0:
        raise SystemExit('could not record the failure list')
    push = os.system(f'git push "{remote}" HEAD:main')
    if push != 0:
        raise SystemExit('could not record the failure list, not dispatching another identical run')
    status, body = request(
        f'{API}/repos/{REPO}/actions/workflows/tmod-full-packages.yml/dispatches',
        'POST',
        {'ref': 'main', 'inputs': {'selection': 'missing', 'recipes': ' '.join(failed)}},
    )
    print('dispatch', status, body[:120])
    if status != 204:
        raise SystemExit(f'dispatch returned {status}')


if __name__ == '__main__':
    main()
