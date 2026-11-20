# Tmod

Android package: `com.huatiencode` (display name: Tmod). Built for arm64 Android 7+.

The upstream Java namespace stays `com.termux` so existing component class names still resolve. The Android package ID, shared user ID, app data path, and bootstrap prefix are `com.huatiencode`. The icon is an original terminal and ghost design in `art/tmod-icon.svg` and Android vector/PNG resources.

The workflow `.github/workflows/tmod-build.yml` builds an aarch64 bootstrap with `termux-packages` using the new prefix, then builds the APK on GitHub Actions. The build includes apt, Python, and pip in the bootstrap. Download the `Tmod-arm64` artifact from the successful workflow run.

The workflow publishes the rebuilt bootstrap packages as a small APT repository at `https://huatien23.github.io/Tmod/apt`. The bootstrap points to that repository. It supports only the packages built in the workflow; the full official Termux repository is not available for the new prefix. Additional packages require a rebuild and publication to this repository.
