#!/usr/bin/env bash
# 在 Linux（含 aarch64 容器）上交叉编译 Windows 版接收端。
# 依赖：x86_64-w64-mingw32-gcc、x86_64-w64-mingw32-windres、curl、unzip、python3
# 产物：dist/LanCmd-Windows-<ver>.zip（解压即用，目标机不需要装 Python）
set -euo pipefail

cd "$(dirname "$0")"
VER=$(python3 -c "import re;print(re.search(r'VERSION = \"([0-9.]+)\"', open('lan_command_agent.py',encoding='utf-8').read()).group(1))")
EMBED_VER="3.11.9"
EMBED_ZIP="python-${EMBED_VER}-embed-amd64.zip"
EMBED_URL="https://www.python.org/ftp/python/${EMBED_VER}/${EMBED_ZIP}"
WORK="$(pwd)/build-win"
PKG="$WORK/pkg/LanCmd-Windows"
OUT="$(pwd)/../dist/LanCmd-Windows-${VER}.zip"

CC=x86_64-w64-mingw32-gcc
RC=x86_64-w64-mingw32-windres

need() { command -v "$1" >/dev/null || { echo "[X] 缺少 $1"; exit 1; }; }
need $CC; need $RC; need unzip; need python3

echo "== 版本 $VER =="

# 1) Windows Python 运行时（缓存，避免重复下载）
mkdir -p "$WORK"
if [ ! -f "$WORK/$EMBED_ZIP" ]; then
    echo "[1/5] 下载 $EMBED_ZIP"
    curl -sSL -o "$WORK/$EMBED_ZIP" "$EMBED_URL"
else
    echo "[1/5] 复用已下载的 $EMBED_ZIP"
fi

# 2) 图标 + 版本资源
echo "[2/5] 编译资源"
$RC --codepage=65001 -I . lancmd.rc -O coff -o "$WORK/res.o"

# 3) 三个 exe：无窗口接收端 / 控制台管理 / 可见启动面板
echo "[3/5] 交叉编译 exe"
$CC -O2 -s -Wall -DUNICODE -D_UNICODE -o "$WORK/LanCmdAgent.exe" \
    launcher.c "$WORK/res.o" -mwindows -lshell32 -static
$CC -O2 -s -Wall -DUNICODE -D_UNICODE -DLANCMD_CONSOLE -o "$WORK/LanCmdCtl.exe" \
    launcher.c "$WORK/res.o" -lshell32 -static
$CC -O2 -s -Wall -municode -mwindows -o "$WORK/Start.exe" \
    start_panel.c "$WORK/res.o" -ladvapi32 -lws2_32 -lshell32 -lcomctl32 -static

# 4) 组装
echo "[4/5] 组装目录"
rm -rf "$PKG"
mkdir -p "$PKG/python"
cp "$WORK/LanCmdAgent.exe" "$WORK/LanCmdCtl.exe" "$WORK/Start.exe" "$PKG/"
cp lan_command_agent.py start.bat 诊断.bat "$PKG/"
(cd "$PKG/python" && unzip -qo "$WORK/$EMBED_ZIP")
# 精简：接收端用不到这些
rm -f "$PKG/python"/_msi.pyd "$PKG/python"/_sqlite3.pyd "$PKG/python"/sqlite3.dll \
      "$PKG/python"/_bz2.pyd "$PKG/python"/_lzma.pyd "$PKG/python"/_overlapped.pyd \
      "$PKG/python"/winsound.pyd "$PKG/python"/_decimal.pyd "$PKG/python"/_elementtree.pyd \
      "$PKG/python"/_asyncio.pyd "$PKG/python"/_zoneinfo.pyd "$PKG/python"/_multiprocessing.pyd
cp "使用说明.txt" "$PKG/使用说明.txt"

# 5) 打包
echo "[5/5] 打 zip"
mkdir -p "$(dirname "$OUT")"
python3 - "$PKG" "$OUT" <<'PY'
import os, sys, zipfile
pkg, out = sys.argv[1], sys.argv[2]
root = os.path.basename(pkg)
n = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for dirpath, _, files in os.walk(pkg):
        for f in sorted(files):
            full = os.path.join(dirpath, f)
            arc = os.path.join(root, os.path.relpath(full, pkg)).replace(os.sep, "/")
            zi = zipfile.ZipInfo(arc)
            zi.date_time = (2026, 10, 22, 12, 0, 0)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (0o755 if f.endswith(".exe") else 0o644) << 16
            with open(full, "rb") as fh:
                z.writestr(zi, fh.read())
            n += 1
print("  %d 个文件, %.1f MB" % (n, os.path.getsize(out) / 1048576))
PY

echo
echo "完成：$OUT"
echo "exe 大小: Agent=$(stat -c%s "$WORK/LanCmdAgent.exe") Ctl=$(stat -c%s "$WORK/LanCmdCtl.exe") Start=$(stat -c%s "$WORK/Start.exe") 字节"
