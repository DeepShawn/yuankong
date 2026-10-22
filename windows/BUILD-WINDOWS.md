# Windows 端 exe 说明

## 结论先说

**已经有编译好的成品了，直接用 `dist/LanCmd-Windows-1.2.zip`。**

解压到任意目录，双击 `LanCmdAgent.exe` 就能跑。**目标电脑不需要装 Python，也不需要装任何编译工具。**

```
LanCmd-Windows/
├── LanCmdAgent.exe        主程序（29KB，无控制台窗口）
├── LanCmdCtl.exe          管理工具（53KB，有控制台输出）
├── lan_command_agent.py   主逻辑，不要删
├── python/                自带的 Python 3.11 运行时（约 19MB）
└── 使用说明.txt
```

把整个文件夹留着，别只拷 exe。

---

## 这些 exe 是怎么来的

之前我说"容器里打不出来"，那是针对 PyInstaller —— 它确实不能跨平台。
但**交叉编译**可以：用 `mingw-w64` 的 GCC 直接产出 Windows x86-64 PE 文件，
再配一份官方 Windows embeddable Python 运行时。

```
launcher.c  ──x86_64-w64-mingw32-gcc──▶  LanCmdAgent.exe   (PE32+ / GUI 子系统)
lancmd.rc   ──x86_64-w64-mingw32-windres──▶  res.o  (图标 + 中文版本信息)
python-3.11.9-embed-amd64.zip  ──▶  python/  (官方运行时，免安装)
```

`launcher.c` 只做四件事：

1. 定位同目录的 `lan_command_agent.py` 和 `python/pythonw.exe`
2. 设置 `LANCMD_EXE` 环境变量指向自己（脚本据此把自启注册到 exe 而不是 pythonw）
3. 用 `CreateProcessW` + `CREATE_NO_WINDOW` 启动（这就是"没有黑窗口"的由来）
4. 把命令行参数原样透传

两个 exe 是同一个 `launcher.c`，靠编译宏区分：

| | 编译方式 | PE 子系统 | 行为 |
|---|---|---|---|
| `LanCmdAgent.exe` | 默认 | `WINDOWS_GUI` | 启动后立即返回，**全程无窗口** |
| `LanCmdCtl.exe` | `-DLANCMD_CONSOLE` | `WINDOWS_CUI` | 继承控制台，等子进程结束并返回其退出码 |

---

## 想自己重新编译

### 方式一：在 Linux / 容器里交叉编译（不需要 Windows）

```bash
# 一次性装工具链（aarch64 宿主也能装，输出仍是 x86-64 PE）
apt-get install -y gcc-mingw-w64-x86-64 binutils-mingw-w64-x86-64

cd windows
./build_windows_exe.sh
# 产物：../dist/LanCmd-Windows-<版本>.zip
```

脚本会做：下载官方 embed Python（缓存复用）→ windres 编资源 → 编两个 exe →
组装目录 → 打 zip。改了 `lan_command_agent.py` 重跑一次即可。

### 方式二：在 Windows 上用 PyInstaller

双击 `build_exe.bat`（会自己装 PyInstaller）。产物是自解压形式的单文件 exe，
每个约 8–12MB。这种方式不需要额外运行时目录，但：

- 每次启动要解压到临时目录，冷启动慢 1–3 秒
- 单文件结构容易被杀软误报

### 方式三：干脆不编译

电脑上装了 Python 的话，直接双击 `start_hidden.vbs` 就行，效果一样。

---

## 运行时为什么不带 tkinter

官方 embeddable 包不含 `tkinter`（需要额外拖 tcl/tk 的 dll 和脚本目录，约多 10MB）。
首次配对那个"是否允许连接"的确认框会因此走**回退分支**：

```python
try:
    import tkinter as tk          # 打包版没有，抛异常
except Exception:
    # 回退：系统原生 MessageBox，同样是 是/否 两个按钮
    ctypes.windll.user32.MessageBoxW(None, msg + "\n\n是否允许？", title, 0x04 | 0x40 | 0x10000)
```

已验证该分支：弹框标题、设备名+IP、按钮组合都对，点「否」会正确拒绝。
缺点是样子朴素（系统对话框），优点是更可靠、不依赖 tcl/tk。

---

## 使用

```bat
:: 启动（无窗口）
LanCmdAgent.exe

:: 管理（有回显）
LanCmdCtl.exe --status
LanCmdCtl.exe --install-autostart
LanCmdCtl.exe --stop
LanCmdCtl.exe --auto-accept
LanCmdCtl.exe --reset-pairing
LanCmdCtl.exe --uninstall-autostart
```

`LanCmdCtl.exe` 不带参数会打印帮助。

`LanCmdAgent.exe` 也能接受这些参数并执行，但没有控制台，你看不到输出——
所以管理一律用 Ctl，主程序只管启动。

---

## 已验证 / 未验证

**已验证（容器内）**

- 交叉编译零警告；PE 头解析确认：`x86-64` / 64 位 / Agent 是 `WINDOWS_GUI`、Ctl 是 `WINDOWS_CUI`
- 图标（7 个尺寸）与中文版本信息已嵌入资源段（10656 字节，`--codepage=65001` 才能正确写入中文）
- 宽字符串全部就位：`lan_command_agent.py`、`pythonw.exe`、`LANCMD_EXE` 等
- 分发包 26 个文件、9.5MB，解压后 19MB，关键依赖齐全（`python311.dll`、`_ctypes.pyd`、`libffi-8.dll`、`_socket.pyd`、`_hashlib.pyd`、`libcrypto-3.dll`、`vcruntime140.dll`）
- `LANCMD_EXE` 注入后自启命令正确指向 exe；无该变量时回退到 pythonw
- 无 tkinter 时原生弹框工作正常，点「否」正确拒绝
- 协议层：基础功能 10/10、传输+进程 25/25、断线重连 5/5 全过

**未验证**

容器是 aarch64 Linux，跑不了 x86-64 Windows 程序，所以 exe 的**实际运行时行为**没实测。
逻辑上各环节都对齐了，但如果真机上有问题，最可能是这两处：

1. `CreateProcessW` 的相对路径解析 —— 启动器传的 `dir` 作为工作目录，理论上没问题
2. embed Python 的 `sys.path` —— 依赖 `python311._pth`（内容为 `python311.zip` + `.`），脚本从完整路径启动，Python 会自动把脚本目录加入 `sys.path[0]`

真出问题的话，先用 `LanCmdCtl.exe` 跑（有控制台），能直接看到 Python 的报错。

---

## 常见问题

| 现象 | 处理 |
|---|---|
| 双击没反应 | 正常，它是无窗口程序。`LanCmdCtl.exe --status` 确认进程在不在 |
| 弹"启动失败"框 | 缺文件。确认 `lan_command_agent.py` 和 `python\pythonw.exe` 跟 exe 同目录 |
| 杀软报毒 | 自行编译的启动器 + 官方 Python 运行时，属误报，加信任 |
| 32 位 Windows | 这套是 x86-64，跑不了。用方式二在 32 位 Python 下重编 |
| 自启失效 | 文件夹移动过。重新执行 `LanCmdCtl.exe --install-autostart` |
| 手机连不上 | 防火墙放行 → 同一 Wi-Fi → 关掉路由器 AP 隔离 → 手动输 IP |
