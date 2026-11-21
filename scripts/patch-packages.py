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

termux_tools = root / 'packages/termux-tools/build.sh'
s = termux_tools.read_text()
old = 'termux_step_pre_configure() {\n\tautoreconf -vfi\n}'
new = 'termux_step_pre_configure() {\n\texport TERMUX_APP_PACKAGE="$TERMUX_APP__PACKAGE_NAME"\n\tautoreconf -vfi\n}'
assert s.count(old) == 1, 'termux-tools recipe changed upstream'
termux_tools.write_text(s.replace(old, new))

bootstraps = root / 'scripts/build-bootstraps.sh'
s = bootstraps.read_text()
old = 'PACKAGES+=("bzip2")'
assert s.count(old) == 1, 'bootstrap package list changed upstream'
s = s.replace(old, 'PACKAGES+=("libbz2")')
old = 'mv -f "${BOOTSTRAP_TMPDIR}/bootstrap-${1}.zip" "$TERMUX_PACKAGES_DIRECTORY/"'
assert s.count(old) == 1, 'bootstrap archive destination changed upstream'
s = s.replace(old, 'mv -f "${BOOTSTRAP_TMPDIR}/bootstrap-${1}.zip" "$TERMUX_BUILT_DEBS_DIRECTORY/"')
old = '''\t\tfor package_name in "${PACKAGES[@]}"; do
\t\t\tset +e
\t\t\tbuild_package "$TERMUX_ARCH" "$package_name" || return $?
\t\t\tset -e
\t\tdone'''
new = '''\t\tif [[ -s "$TERMUX_BUILT_DEBS_DIRECTORY/bootstrap-${TERMUX_ARCH}.zip" ]]; then
\t\t\t# Cached packages are patched before this script runs.
\t\t\techo "[*] Repackaging cached bootstrap with fixed termux-tools"
\t\telse
\t\t\tfor package_name in "${PACKAGES[@]}"; do
\t\t\t\tset +e
\t\t\t\tbuild_package "$TERMUX_ARCH" "$package_name" || return $?
\t\t\t\tset -e
\t\t\tdone
\t\tfi'''
assert s.count(old) == 1, 'bootstrap build loop changed upstream'
s = s.replace(old, new)
old = '\t\tif [[ " ${EXTRACTED_PACKAGES[*]} " == *" $current_package_name "* ]]; then'
new = '''\t\t# These repository-generation tools need clang, which is not shipped.
\t\tif [[ "$current_package_name" == "dpkg-perl" || "$current_package_name" == "dpkg-scanpackages" ]]; then
\t\t\techo "[*] Skipping build-only package '$deb'..."
\t\t\tcontinue
\t\tfi

''' + old
assert s.count(old) == 1, 'bootstrap package extraction changed upstream'
s = s.replace(old, new)
bootstraps.write_text(s)
print('Patched package ID, optional sources, and bootstrap package list')
