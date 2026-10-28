# Windows 1.7.2 构建

发布 ZIP 为 Windows x64 解压包，适用于 Windows 10/11。目标电脑不需要安装 Python。升级时先执行 `LanCmdCtl.exe --stop`，替换完整目录，再启动；配置继续保存在 `%APPDATA%\LanCmd`。

## 目录

```text
LanCmd-Windows/
├── Start.exe              可见的启动和管理面板
├── LanCmdAgent.exe        后台接收服务启动器
├── LanCmdCtl.exe          控制台管理工具
├── LanCmdCapture.exe      GDI / Media Foundation / WIC JPEG 采集
├── lan_command_agent.py   协议、命令和连接管理
├── remote_features.py     会话、消息和按键
├── python/               官方 embeddable Python 3.11.9 x64
└── 使用说明.txt
```

整个目录需一起保留。接收服务由 `Start.exe` 或 `LanCmdCtl.exe --stop` 停止；当前版本没有本机会话确认窗口，也不设共享时长上限。旧参数 `--no-auto-accept` 不会恢复人工确认。

## Linux 交叉编译

```bash
apt-get install gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64 binutils-mingw-w64-x86-64 curl unzip
bash windows/build_windows_exe.sh
```

脚本下载并缓存官方 Python 运行时，编译资源、三个启动和管理 EXE、画面辅助 EXE，随后打包 `dist/LanCmd-Windows-<版本>.zip`。支持设置 `CC`、`CXX`、`RC` 使用兼容工具链，例如 llvm-mingw。

辅助程序采用 C++17 和 Windows 系统库，静态链接 C++ 运行时。屏幕通过 GDI 获取全部显示器，摄像头通过 Media Foundation 获取一帧，WIC 输出 JPEG；不录音、不在电脑端保存画面。屏幕最大 1280×960，摄像头最大 960×960，保持比例。会话 ID、作用域与连接生命周期由 Python 接收端管理。

## Windows 上构建

在安装了 MSVC C++ 工具和 Windows SDK 的 **x64 Native Tools Command Prompt** 运行 `build_exe.bat`。脚本编译 `LanCmdCapture.exe` 并使用 PyInstaller 构建接收端和管理工具。也可从官方同版本 ZIP 提取 `LanCmdCapture.exe` 放在脚本目录，再构建 Python 部分。

PyInstaller 产物需连同 `LanCmdCapture.exe` 分发。它与主发布 ZIP 使用不同的启动方式，每次启动可能解压运行时。主发布 ZIP 采用 MinGW 启动器和官方 Python 运行时。

源码方式需要 Python，并需要同目录 `remote_features.py`；屏幕和摄像头还需要已编译的 `LanCmdCapture.exe`。embeddable Python 的隔离模块路径由接收端显式补入脚本目录。

1.8.0 还需在同目录保留 `system_controls.py`、`input_guard.py` 和 `device_management.py`。Windows ZIP 与 PyInstaller 均包含这些依赖。设备管理测试：`python -m unittest discover -s tools -p test_device_management.py -v`；原生检查只读取宿主设备与策略，不在 CI 机器上锁定真实设备。

## 验证

```bash
python3 -m unittest discover -s tools -p test_remote_features.py -v
python3 -m unittest discover -s tools -p test_file_operations.py -v
python3 -m unittest discover -s tools -p test_pointer.py -v
python3 -m unittest discover -s tools -p test_cursor_visibility.py -v
# Windows 下先编译 LanCmdCtl.exe，再运行启动器参数回归
python -m unittest discover -s tools -p test_launcher.py -v
```

测试涵盖作用域与连接隔离、会话关闭、按键与消息参数校验，以及上传竞争覆盖、严格 base64、超量分块和失败清理。启动器测试核对首参数、空参数、空格、中文、引号和反斜线的透传。基础协议、文件传输与重连沿用相应 `tools/test_*.py` 脚本。

GitHub **Windows feature checks** 工作流在 Windows 运行器上用 MSVC 构建启动器和画面辅助程序，运行启动器、文件操作、会话及原生功能测试；另一个 Ubuntu 作业通过 MinGW 编译完整 ZIP，上传为 Actions Artifact。具体结果见对应提交的 Actions 记录。工作流不会自动替换 Release 附件。

1.5.1 修正启动器命令行开头多余的空格，并按 Windows 参数规则处理引号和反斜线，避免 Python 把解释器自身当成脚本或收到错误参数。

Windows 实机仍需验证实际摄像头画面、设备隐私权限、显示器缩放，以及 Unicode 按键在真实前台应用中的行为。

常见问题：缺少辅助程序时完整解压新版本；摄像头不可用时检查 Windows「隐私和安全性 → 摄像头 → 允许桌面应用访问」及其他应用是否占用设备；Windows N 版本可能需要 Media Feature Pack。输入控制要求桌面已解锁，不能操作 UAC 安全桌面或权限高于接收端的应用。

1.7.0 完整包还需包含 `system_controls.py` 和 `input_guard.py`（PyInstaller 已追加 hidden-import）。新增 `tools/test_system_controls.py` 验证音量参数、输入暂停定时/手动/断线恢复及物理解除回调。

1.7.1 输入暂停仅运行钩子和消息循环，不创建桌面提示窗口；原生测试枚举工作线程的可见窗口，验证暂停时没有提示窗口，并检查解除和恢复。

1.7.2 移除输入暂停的时长参数和到期恢复逻辑；协议层仅接受省略 seconds 或 seconds=0，测试验证拒绝旧版非零时长并保留会话中断恢复。
