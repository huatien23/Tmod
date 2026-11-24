#!/usr/bin/env python3
"""Make termux-packages build and resolve dependencies for Tmod."""
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
props = root / 'scripts/properties.sh'
s = props.read_text()
old = 'TERMUX_APP__PACKAGE_NAME="com.termux"'
assert s.count(old) == 1, 'termux-packages property changed upstream'
props.write_text(s.replace(old, 'TERMUX_APP__PACKAGE_NAME="com.huatiencode"'))

s = props.read_text()
for old, new in (
    ('TERMUX_REPO_APP__PACKAGE_NAME="com.termux"', 'TERMUX_REPO_APP__PACKAGE_NAME="com.huatiencode"'),
    ('TERMUX_REPO_APP__DATA_DIR="/data/data/com.termux"', 'TERMUX_REPO_APP__DATA_DIR="/data/data/com.huatiencode"'),
    ('TERMUX_REPO__CORE_DIR="/data/data/com.termux/termux/core"', 'TERMUX_REPO__CORE_DIR="/data/data/com.huatiencode/termux/core"'),
    ('TERMUX_REPO__APPS_DIR="/data/data/com.termux/termux/app"', 'TERMUX_REPO__APPS_DIR="/data/data/com.huatiencode/termux/app"'),
    ('TERMUX_REPO__ROOTFS="/data/data/com.termux/files"', 'TERMUX_REPO__ROOTFS="/data/data/com.huatiencode/files"'),
    ('TERMUX_REPO__HOME="/data/data/com.termux/files/home"', 'TERMUX_REPO__HOME="/data/data/com.huatiencode/files/home"'),
    ('TERMUX_REPO__PREFIX="/data/data/com.termux/files/usr"', 'TERMUX_REPO__PREFIX="/data/data/com.huatiencode/files/usr"'),
):
    assert s.count(old) == 1, f'repository property changed upstream: {old}'
    s = s.replace(old, new)
props.write_text(s)

repo_json = root / 'repo.json'
repos = json.loads(repo_json.read_text())
assert repos['packages']['url'].startswith('https://'), 'main repository URL changed upstream'
repos['packages']['url'] = 'https://huatien23.github.io/Tmod/apt'
repo_json.write_text(json.dumps(repos, indent=2) + '\n')

# The Tmod bootstrap repository is trusted by apt but does not publish a
# Release.gpg file.  The build helper otherwise retries for several minutes
# and fails before it can reuse the packages already published there.
repo_helper = root / 'scripts/build/termux_get_repo_files.sh'
s = repo_helper.read_text()
old = '&& termux_download "${RELEASE_FILE_SIG_URL}" "${RELEASE_FILE}.gpg" SKIP_CHECKSUM; then'
new = '''&& { [[ "${TERMUX_REPO_URL[$idx]}" == "https://huatien23.github.io/Tmod/apt" ]] ||
                        termux_download "${RELEASE_FILE_SIG_URL}" "${RELEASE_FILE}.gpg" SKIP_CHECKSUM; }; then'''
assert s.count(old) == 1, 'repository signature download changed upstream'
s = s.replace(old, new)
old = 'if ! gpg --verify "${RELEASE_FILE}.gpg" "${RELEASE_FILE}"; then'
new = 'if [[ "${TERMUX_REPO_URL[$idx]}" != "https://huatien23.github.io/Tmod/apt" ]] && ! gpg --verify "${RELEASE_FILE}.gpg" "${RELEASE_FILE}"; then'
assert s.count(old) == 1, 'repository signature check changed upstream'
repo_helper.write_text(s.replace(old, new))

mpg123 = root / 'packages/libmpg123/build.sh'
s = mpg123.read_text()
old = 'TERMUX_PKG_BUILD_DEPENDS="pulseaudio"'
assert s.count(old) == 1, 'libmpg123 optional audio dependency changed upstream'
mpg123.write_text(s.replace(old, 'TERMUX_PKG_BUILD_DEPENDS=""'))

# buildorder.py folds subpackage dependencies into the parent. mpg123's
# runtime dependency on pulseaudio pulls libsndfile, which depends on
# libmpg123, so the first build can never download a copy of itself.
player = root / 'packages/libmpg123/mpg123.subpackage.sh'
s = player.read_text()
old = 'TERMUX_SUBPKG_DEPENDS="pulseaudio"'
assert s.count(old) == 1, 'mpg123 subpackage dependency changed upstream'
player.write_text(s.replace(old, 'TERMUX_SUBPKG_DEPENDS=""'))

# Published debs do not ship libtool archives. Packages rebuilt from source
# still ask libtool for files such as libX11.la.
configure = root / 'scripts/build/configure/termux_step_configure.sh'
s = configure.read_text()
old = '''termux_step_configure() {
	[ "$TERMUX_PKG_METAPACKAGE" = "true" ] && return
'''
new = r'''termux_tmod_synthesize_libtool_archives() {
	[[ -d "${TERMUX_PREFIX}/lib" ]] || return 0
	local la so base name dir
	while IFS= read -r la; do
		sed -i "s/^dependency_libs=.*/dependency_libs=''/" "$la"
	done < <(find "${TERMUX_PREFIX}/lib" -name '*.la' -type f)
	# Prefer the linker name libfoo.so over a versioned soname.
	while IFS= read -r so; do
		base=$(basename "$so")
		[[ "$base" == *.so ]] || continue
		dir=$(dirname "$so")
		la="${dir}/${base%.so}.la"
		[[ -e "$la" ]] && continue
		cat > "$la" << EOF
# Synthesized by Tmod. The published package does not ship this archive.
dlname='${base}'
library_names='${base}'
old_library=''
inherited_linker_flags=''
dependency_libs=''
weak_library_names=''
current=0
age=0
revision=0
installed=yes
shouldnotlink=no
dlopen=''
dlpreopen=''
libdir='${dir}'
EOF
	done < <(find "${TERMUX_PREFIX}/lib" -name 'lib*.so' \( -type f -o -type l \))
}

termux_step_configure() {
	[ "$TERMUX_PKG_METAPACKAGE" = "true" ] && return
	termux_tmod_synthesize_libtool_archives
'''
assert s.count(old) == 1, 'configure step changed upstream'
configure.write_text(s.replace(old, new, 1))

# The main Pages repository contains only the bootstrap packages.  Extra
# dependencies are downloaded directly from the rebuilt Release assets.
downloader = root / 'scripts/build/termux_download_deb_pac.sh'
s = downloader.read_text()
old = '\t\t[[ ! -L "${cache_file}" ]] || rm -f "${cache_file}"'
new = '''\t\tif [[ -f "$TERMUX_SCRIPTDIR/tmod-release-assets.json" ]]; then
\t\t\tlocal release_url release_hash
\t\t\tread -r release_url release_hash < <(python3 "$TERMUX_SCRIPTDIR/scripts/tmod-release-lookup.py" "$TERMUX_SCRIPTDIR/tmod-release-assets.json" "$PKG_FILE") || :
\t\t\tif [[ -n "$release_url" ]]; then
\t\t\t\ttermux_download "$release_url" "$cache_file" "$release_hash"
\t\t\t\treturn $?
\t\t\tfi
\t\tfi
''' + old
assert s.count(old) == 1, 'dependency downloader changed upstream'
downloader.write_text(s.replace(old, new))
(root / 'scripts/tmod-release-lookup.py').write_text('''import json
import sys
with open(sys.argv[1]) as stream:
    asset = json.load(stream).get(sys.argv[2])
if asset:
    print(*asset)
''')

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
