# Bàn giao build Tmod cho Grok

## Mục tiêu

Build bản Termux tên **Tmod**, Android package `com.huatiencode`, icon ma/đầu lâu nhỏ và terminal. Người dùng chỉ muốn nhận APK cuối khi kho gói arm64 tương đương kho chính Termux và `apt` cài gói bình thường. Mọi file dự án và kết quả đặt trong `/sdcard/Download/HUATIEN/Tmod` hoặc thư mục con. Không đọc hay sửa `grok4.6.md` và `~/.grok/`.

Repo: `https://github.com/huatien23/Tmod`, nhánh `main`. Mã nguồn Android, patch build, workflow và kho APT đều ở repo này.

## Quyền GitHub

Token **không nằm trong file này**. Kiểm tra trong phiên Grok:

```sh
test -n "$GH_TOKEN" && echo 'GH_TOKEN available' || echo 'GH_TOKEN missing'
```

Nếu thiếu, cần người dùng cung cấp token cho phiên Grok bằng kênh riêng. Đừng in token ra log, chat, commit hoặc file. Repo công khai vẫn đọc được khi chưa có token; `git push` và dispatch GitHub Actions cần quyền ghi. Workflow dùng `${{ github.token }}` do GitHub cấp trên runner.

## Trạng thái lúc bàn giao (2026-11-24)

- Kho Pages core: `https://huatien23.github.io/Tmod/apt`.
- Các gói thêm nằm trong GitHub Releases với tag `tmod-main-*`, `tmod-batch-*`, `tmod-retry-*`. Index APT mỗi release là `Packages`, `Packages.gz`, `Release`.
- Lần đối chiếu gần nhất: kho chính Termux 3.037 tên gói, Tmod 2.730 tên, **309 tên còn thiếu**. Kết quả sẽ thay đổi khi job đang chạy xuất bản thêm.
- Workflow build toàn bộ: run `45179717557` (đã thử lại nhiều lần; có công thức lỗi dù job có thể báo success).
- Workflow chạy bù 229 công thức: run `45332429366`.
- Workflow chạy bù sau khi sửa tải phụ thuộc từ Releases: run `45373326658`.
- Job riêng xây `libmpg123`: run `45391621459`. Nó cần để phá vòng phụ thuộc liên quan `pulseaudio`/`libmpg123`; patch hiện ở `scripts/patch-packages.py`.
- Job APK chưa đủ gói đã được hủy theo yêu cầu. Chỉ chạy `.github/workflows/tmod-build.yml` sau khi kiểm tra kho đầy đủ và phụ thuộc cài được.

## Vấn đề đã phát hiện

1. GitHub Actions `success` **không chứng minh mọi recipe thành công**: trong `.github/workflows/tmod-full-packages.yml`, bước `Build recipes` ghi `Failed recipe: NAME` và tiếp tục. Phải đọc log, lập danh sách các recipe lỗi, sửa rồi build lại.
2. Một số gói vướng vòng phụ thuộc `libmpg123`; trước đây builder chỉ tìm phụ thuộc trong Pages. `scripts/cache-release-assets.py` và patch downloader trong `scripts/patch-packages.py` hiện lấy thêm gói từ Releases. Kiểm tra `libmpg123` build và có `.deb` trong release trước khi chạy lại các gói phụ thuộc.
3. Có lỗi khác ngoài phụ thuộc, ví dụ checksum mã nguồn `libsrt`, `libv4l`, và libtool báo `libX11.la` không hợp lệ. Cần lấy log cụ thể và sửa từng nhóm nguyên nhân; đừng chỉ dispatch lại toàn bộ nếu chưa sửa.
4. Publisher `scripts/publish-package-batch.py` đã thêm retry API; nếu job xuất bản lỗi, cache hiện được lưu bằng `if: always()` trong workflow mới. Các run cũ có thể mất cache và cần build lại.
5. GitHub workflow `tmod-retry-failed-jobs.yml` tự thử lại job lỗi hạ tầng tối đa ba lần. Nó không sửa lỗi recipe bị ghi `Failed recipe`.

## Cách tiếp tục

1. Xem các run ở trên tại `https://github.com/huatien23/Tmod/actions/runs/<run-id>`. Kiểm tra job nào còn chạy hoặc thất bại. Có thể gọi API với `Authorization: Bearer $GH_TOKEN` mà không in token.
2. Sau khi job hiện tại xong, quét log tìm `Failed recipe:`; kiểm tra lỗi ngay trước dòng đó. Ưu tiên xác nhận `libmpg123` đã được xuất bản.
3. Tạo lại danh sách thiếu bằng cách lấy `Packages.gz` từ tất cả release `tmod-main-*`, `tmod-batch-*`, `tmod-retry-*` và so với `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz`. File đối chiếu cũ ở `build-audit/missing-packages-current.txt`, danh sách recipe chạy bù ở `build-audit/missing-recipes.txt` (có thể đã cũ).
4. Sửa recipe hoặc hệ thống build. Commit và push lên `main`. Để chạy bù theo danh sách trong repo, dispatch workflow `tmod-full-packages.yml` với input `selection=missing`. Workflow có tối đa 16 job song song và mỗi job 10 recipe. Cập nhật danh sách `build-audit/missing-recipes.txt` trước khi dispatch lần mới.
5. Kiểm tra nguồn APT bằng `apt update` và mô phỏng `apt install` các gói tiêu biểu; đặc biệt `clang`, `python`, `git`, `openssh`, `libmpg123`, các gói trước đó lỗi. Đừng chỉ đếm tên gói: phải kiểm tra phụ thuộc và đường dẫn `/data/data/com.huatiencode/files/usr`.
6. Khi kho đạt yêu cầu, dispatch `tmod-build.yml`. Script `prepare-apt-repo.sh` tự thêm các release đã có index vào `sources.list` trong bootstrap. Xác minh APK có package ID `com.huatiencode`, tên Tmod và kho gói mới. Sau đó mới cung cấp APK cuối cho người dùng.

## Lệnh GitHub API mẫu

```sh
python - <<'PY'
import json, os, urllib.request
run_id = '45373326658'
request = urllib.request.Request(
    f'https://api.github.com/repos/huatien23/Tmod/actions/runs/{run_id}',
    headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
             'Accept': 'application/vnd.github+json'})
with urllib.request.urlopen(request) as response:
    run = json.load(response)
print(run['status'], run['conclusion'], 'attempt', run['run_attempt'])
PY
```

Không đưa giá trị token vào URL, command line argument, log hoặc commit. Nếu phiên Grok không có `GH_TOKEN`, chỉ người dùng mới có thể cấp lại quyền.
