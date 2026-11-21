#!/usr/bin/env python3
"""Correct package-specific paths in an already built termux-tools deb."""

import subprocess
import shutil
import sys
import tempfile
from pathlib import Path


output = Path(sys.argv[1])
debs = list(output.glob('termux-tools_*.deb'))
if not debs:
    print('No cached termux-tools deb; a full build will generate it')
    sys.exit(0)
assert len(debs) == 1, f'Expected one termux-tools deb, found {len(debs)}'
deb = debs[0]

with tempfile.TemporaryDirectory() as temp:
    package = Path(temp) / 'package'
    subprocess.run(['dpkg-deb', '-R', str(deb), str(package)], check=True)
    changed = []
    for file in package.rglob('*'):
        if not file.is_file() or file.is_symlink():
            continue
        original = file.read_bytes()
        if b'\0' in original or b'com.termux' not in original:
            continue
        fixed = original.replace(b'/data/data/com.termux', b'/data/data/com.huatiencode')
        fixed = fixed.replace(b'com.termux/com.termux.app', b'com.huatiencode/com.termux.app')
        fixed = fixed.replace(b'com.termux.app.reload_style', b'com.huatiencode.app.reload_style')
        fixed = fixed.replace(b'com.termux.service_', b'com.huatiencode.service_')
        fixed = fixed.replace(b'"com.termux"', b'"com.huatiencode"')
        fixed = fixed.replace(b'Termux (com.termux;', b'Termux (com.huatiencode;')
        if fixed != original:
            file.write_bytes(fixed)
            changed.append(file.name)
    assert 'termux-setup-storage' in changed and 'login' in changed and 'preinst' in changed, changed
    setup = next(package.rglob('termux-setup-storage')).read_text()
    login = next(package.rglob('login')).read_text()
    assert '/data/data/com.huatiencode/files/home/storage' in setup
    assert '"com.huatiencode" > /dev/null' in setup
    assert '/data/data/com.huatiencode/files/usr/bin/bash' in login
    for name in ('preinst', 'postinst', 'prerm', 'postrm', 'config'):
        script = package / 'DEBIAN' / name
        if script.exists():
            script.chmod(0o755)
    replacement = Path(temp) / deb.name
    subprocess.run(['dpkg-deb', '-b', str(package), str(replacement)], check=True)
    shutil.copy2(replacement, deb)

print('Fixed cached termux-tools:', ', '.join(changed))
