#!/usr/bin/env python3
"""Make termux-packages build for Tmod without optional foot terminfo."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
props = root / 'scripts/properties.sh'
s = props.read_text()
old = 'TERMUX_APP__PACKAGE_NAME="com.termux"'
assert s.count(old) == 1, 'termux-packages property changed upstream'
props.write_text(s.replace(old, 'TERMUX_APP__PACKAGE_NAME="com.huatiencode"'))

ncurses = root / 'packages/ncurses/build.sh'
lines = ncurses.read_text().splitlines(keepends=True)
out = []
removed_sources = 0
skip_terminfo = False
removed_terminfo = False
for line in lines:
    if 'x11-packages/foot/build.sh' in line:
        assert line.rstrip().endswith('")'), 'foot source array changed upstream'
        out[-1] = out[-1].rstrip('\n') + ')\n'
        removed_sources += 1
        continue
    if 'Upstream instructions for building foot\'s terminfo' in line:
        skip_terminfo = True
        removed_terminfo = True
    if skip_terminfo:
        if 'tic -x -e foot,foot-direct' in line:
            skip_terminfo = False
        continue
    out.append(line)
assert removed_sources == 3 and removed_terminfo and not skip_terminfo, 'ncurses recipe changed upstream'
ncurses.write_text(''.join(out))

docbook = root / 'packages/docbook-xml/build.sh'
s = docbook.read_text()
for version, filename in (
    ('4.1.2', 'docbkx412.zip'),
    ('4.2', 'docbook-xml-4.2.zip'),
    ('4.3', 'docbook-xml-4.3.zip'),
    ('4.4', 'docbook-xml-4.4.zip'),
    ('4.5', 'docbook-xml-4.5.zip'),
):
    old = f'https://archive.docbook.org/xml/{version}/{filename}'
    new = f'https://bos.us.distfiles.macports.org/docbook-xml/{filename}'
    assert s.count(old) == 1, f'docbook source changed upstream: {filename}'
    s = s.replace(old, new)
docbook.write_text(s)
print('Patched package ID, optional foot terminfo, and DocBook mirror')
