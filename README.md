# LanCmd — 局域网命令控制（安卓 → Windows）

手机在同一个局域网下直接控制电脑：执行命令、看文件、截屏、锁屏、剪贴板、开关机。
电脑端是后台静默接收程序（无窗口），**一直在线**；手机端开机即自动发现、**不用输连接码**、
**掉线自动重连**。

**最新修复：Android 1.4.10 修复已连接却无法发送命令的问题。**

- [下载 Android 1.4.10 APK](https://github.com/DeepShawn/yuankong/releases/download/v1.4.10/LanCmd-1.4.10.apk)
- [下载 Windows 1.4.9 接收端](https://github.com/DeepShawn/yuankong/releases/download/v1.4.9/LanCmd-Windows-1.4.9.zip)（本次无需更新）
- Android 1.4.10 使用新签名，**从旧版升级须先卸载再安装**，应用内连接配置和历史记录会丢失。

```
┌──────────────┐   UDP 47824 广播探测/应答   ┌──────────────────────┐
│ 安卓 App      │ ──────────────────────────▶ │ Windows 接收端        │
│ com.aicode.  │                             │ pythonw 静默运行      │
│   lancmd     │   TCP 47823 行分隔 JSON     │ 无窗口 / 开机自启      │
│ 前台常驻保活  │ ◀─────────────────────────▶ │ 看护线程 / 端口自愈    │
└──────────────┘                             └──────────────────────┘
```

---

## 一、快速开始

### 1. 电脑端（Windows）

发布压缩包自带 Python，解压后即可运行；从源码运行才需要另外安装 Python 3.8+。

- **双击 `Start.exe`** ← 推荐。弹出小面板，能看见运行状态，一键启动/停止/开机自启。

也可以：

- **双击 `start.bat`**：命令行方式，显示启动结果后自动结束
- **双击 `LanCmdAgent.exe`**：直接静默运行，无任何提示
- **前台调试（看日志）**：`start_console.bat`
- **开机自启**：`install_autostart.bat`，或在 `Start.exe` 里点「开机自启」

启动后电脑不弹任何窗口，只在任务管理器里有一个 `pythonw.exe` 进程。
配置与日志在 `%APPDATA%\LanCmd\`（`agent.json`、`agent.log`、`paired.json`）。

Windows 1.4.9 默认免确认，手机连接后直接授权。需要恢复首次连接人工确认时执行：

```bat
LanCmdCtl.exe --no-auto-accept
```

### 2. 手机端（Android 7.0+，arm64-v8a）

从上方链接下载 `LanCmd-1.4.10.apk` 后安装：

```bash
adb install LanCmd-1.4.10.apk
```

打开 App 自动扫描并连上唯一的那台电脑；有多台时弹列表让你选。

---

## 二、界面说明

界面分四层，从上到下都是常用动作，不需要进二级菜单：

**① 状态卡**
- 左侧圆点：绿=已连接，黄=重连中，灰=断开
- 主机名 + `IP:端口` + 是否免确认直连
- 右侧胶囊显示实时延迟（≤80ms 变绿），点一下立即测一次
- 右上 `⋮` 菜单：立即重连 / 手动输入 IP / 断开 / 清除配对 / 清空日志 / 关闭后台常驻 / 关于

**② 快捷操作**（两行 8 个，Material 3 卡片网格）
信息 · 文件 · 进程 · 剪贴板 · 截屏 · 锁屏 · 重启 · 关机

未连接时点任意图标会提示并顺便触发重连，不会静默失败。

状态卡右侧还有一个上传按钮（↑），随时选文件发给电脑。

**③ 控制台**（占屏幕主要区域，圆角卡片 + 等宽字体）
- 命令回显绿色，正常输出浅色，错误红色，状态提示琥珀色（带时间戳）
- 自动滚到底部，超过 30 万字自动截断保留最近 20 万字

**④ 输入区**
- `CMD / PowerShell` 分段切换（MaterialButtonToggleGroup）
- 输入框 + 圆形发送按钮，键盘回车直接执行
- 历史按钮：保留最近 50 条，点选回填；可一键清空

**连接浮层**：连接中/重连中时，控制台区域盖一层半透明面板，显示当前动作、
第几次重试、倒计时秒数、已成功重连次数，并给“立即重连”按钮。

---

## 三、一直在线 & 断线重连

### 电脑端不会因为手机掉线而停

- 手机断开只结束那一条连接线程，监听与发现应答继续服务
- TCP keepalive（30s 空闲 + 10s×3 探测）让半开连接约 60s 内被内核回收
- TCP 监听 / UDP 应答各有看护线程，循环异常退出会 2s→30s 退避自动重启
- 端口被上一实例占用时持续重试，不会启动失败
- `SIGTERM/SIGINT/SIGBREAK/SIGHUP` 都能优雅退出并清理 pid 文件

### 手机端自动重连

| 触发 | 行为 |
|---|---|
| 连接断开（Wi-Fi 抖动、电脑重启） | 立即重连，失败按 0.8s→1.5s→2.5s→4s→6s→8s→12s 退避，无限重试 |
| 网络恢复（切 Wi-Fi、重连热点） | `ConnectivityManager` 回调，跳过倒计时立刻重试 |
| 心跳超时 | 每 20s ping，连续 3 次 10s 超时 → 主动断开触发重连；有命令执行中时暂停心跳判定 |
| 电脑 IP 变化 | 每 3 次失败重新 UDP 广播，按 `agent_id` 认回同一台电脑 |
| 首次配对过 | 用本地 token 直连，**不再弹电脑确认框** |
| 电脑端进程重启 | token 持久化在 `paired.json`，重连照旧免确认 |

### 后台不掉线

App 启动即拉起一个前台服务（最低优先级通知、无声音无振动、可折叠），
避免 Android 把后台长连接冻结。常驻通知只显示一行当前状态（如 `DESKTOP-8K2 · 192.168.1.10`）。

不想常驻可在 `⋮` →「关闭后台常驻」；关闭后 App 退到后台可能被系统挂起。

---

## 三·四、网络环境：VPN 和热点

### 手机开 VPN 也能用

VPN 开着时，系统默认路由指向 tun 网卡，普通 socket 的 UDP 广播会从 VPN 发出去，
结果就是「电脑明明在线却扫不到」。这个版本改了发现逻辑：

- 枚举所有 `Network`，**跳过 VPN 类型的网络**
- 每个 Wi-Fi / 以太网网络单独建 socket 并 `bindSocket`，保证广播从物理网卡出去
- 广播目标用该网络的链路地址现算（`IP | ~mask`），不只依赖 `255.255.255.255`
- 多个网卡并行探测，不互相拖慢

TCP 连接同样会绑定到物理网络，避免握手包被送进 VPN 隧道。

万一还是不通，界面上会提示「检测到 VPN」。此时把 VPN 客户端里的
**局域网直连白名单**打开（绕过 VPN 走本地），或者临时关掉 VPN。

### 电脑开热点、手机连热点

这个场景有两个坑，都处理了。**注意第一个必须手动做一次。**

#### 坑一：防火墙（必须处理）

电脑开热点后，手机连的那个网络被 Windows 判为**公用网络**，47823/47824 默认被防火墙拦掉。
表现是手机完全搜不到，或者连上就断。

修复（二选一）：

```
双击 Start.exe → 点「修复防火墙」→ UAC 点「是」
```

```bat
:: 或右键以管理员身份运行
LanCmdCtl.exe --fix-firewall
```

`Start.exe` 会实时检测，没放行时按钮上直接显示「防火墙未放行 !」。
命令行也可以查：

```bat
LanCmdCtl.exe --firewall-status
```

App 端也做了配合：连上之后如果发现电脑防火墙没放行，会弹窗提示你去修。

规则加的是两条入站放行（`profile=any`，覆盖公用/专用/域）：

```
LanCmd-TCP   TCP  47823
LanCmd-UDP   UDP  47824
```

#### 坑二：热点下 UDP 广播被丢

手机和电脑在同一个虚拟子网，但系统经常把广播包丢掉。所以加了两层兜底：

1. **网关单播探测**（主力）：探测帧除了广播，还会**单播发给当前网络的默认网关**。
   连电脑热点时网关就是那台电脑，接收端监听 `0.0.0.0:47824`，单播一样会应答。
   这比猜 IP 靠谱得多——能收到应答就说明确实是我们的接收端。
2. **网关直连**：如果连着「没有互联网的 Wi-Fi」（热点就是这样），第一次尝试就
   直接连网关的 47823，不用等广播失败重试。

电脑端也会识别热点网卡（`Wi-Fi Direct`、`本地连接*`、`Mobile Hotspot`），
不会把它误判成 VPN 排除掉。

#### 手机侧注意

连电脑热点时手机没有互联网，属正常。但有些手机会因为「此网络无法访问互联网」
自动切回移动数据，那就断了。需要在 Wi-Fi 设置里把这台热点设为「保持连接」，
或关掉「自动切换到移动数据」。

手动兜底：App 里「手动输入 IP」填 `192.168.137.1`。

### 多网卡时自动挑对地址

一台电脑可能同时有 Wi-Fi、热点、VPN 好几块网卡。接收端在发现应答里会带上全部地址，
手机按这个顺序挑：

1. 与手机当前网络**同网段**的地址
2. 与应答源地址相同的
3. 列表第一个

选不到的地址会自动换下一个重试。这一步避免了「能发现但连不上」。

---

## 三·五、文件传输

### 发文件到电脑

三个入口，都可以：

1. 状态卡右侧的 **↑ 上传按钮**
2. `⋮` →「发送文件到电脑」
3. 文件浏览器里点底部「上传到此目录」

选完文件（支持**多选**）会弹出确认框，显示文件名和目标目录，目录可直接改。
确认后逐个上传，屏幕中间显示一个不阻塞操作的进度框：

- 标题：`[2/5] photo.jpg`
- 进度条 + `1.2 MB / 3.4 MB · 35% · 8.9 MB/s`
- 「取消」按钮：中途取消会通知电脑删除半截文件

目标目录会记住上次用的，下次默认填同一个。

### 从电脑下文件

文件浏览器里点任意文件 → 「下载到手机」，同样有进度框，取消会删掉不完整文件。
保存到 `Android/data/com.aicode.lancmd/files/downloads/`。

### 为什么会话式分块

原来一次性把整个文件塞进一个 JSON 帧，base64 后膨胀 4/3，超过 32MB 就失败，
大文件还会撑爆内存。现在改成 512KB 一块逐块发，接收端先写 `.lancmd-part`
临时文件，全部到齐且 sha256 对上才原子改名成正式文件；中途取消或断线自动清理临时文件。

默认单文件上限 4GB（`agent.json` 里的 `max_upload_bytes` 可改）。

### 文件管理器

点「文件」进入，除了浏览还有：

- 点文件 → 下载 / 重命名 / 删除
- 底部「上传到此目录」→ 直接传到当前目录
- 底部「新建文件夹」

---

## 三·六、进程管理 GUI

点「进程」进入（不再是一堆纯文本）：

- **搜索框**：按进程名或 PID 实时过滤
- **排序**：按内存 / 按名称切换
- 每行显示进程名、`PID`、内存占用、会话，右上角有红色 **✕** 直接结束该进程
- **点整行 = 勾选**，可多选，底部「结束」批量处理（有二次确认，列出将结束的进程）
- 「全选」一键勾选当前筛选结果
- **接收端自身那一行是橙色且不能结束**，防止点一下就把自己连接断了
- 结束失败会显示电脑端的原始报错（如“拒绝访问”），不会静默

---

## 四、能干什么

| 手机操作 | 电脑侧行为 |
|---|---|
| 输入任意命令 | `cmd /c` 或 `powershell -Command` 执行，回传 stdout/stderr/退出码 |
| 文件 | 浏览任意目录、点文件下载到手机（`Android/data/com.aicode.lancmd/files/downloads/`） |
| 截屏 | GDI 抓取全屏，回到手机里显示（不落盘） |
| 剪贴板 | 读电脑剪贴板，也能把手机上编辑的文本写回电脑 |
| 进程 | 列出进程 PID/内存 |
| 锁屏 | `LockWorkStation` |
| 关机 / 重启 | `shutdown /s|/r /t 0`，App 内二次确认 |

上传/下载、重命名、删除、新建文件夹、结束进程都已在界面上。

协议里还支持（可用 `tools/test_transfer.py` 调）：
`put`/`get` 单帧小文件、`agent stop` 远程退出接收端。

---

## 五、Windows 端 exe

**已编译的 Windows 版请从上方链接下载 `LanCmd-Windows-1.4.9.zip`。**

解压到任意目录，双击 `LanCmdAgent.exe` 就能跑。**目标电脑不用装 Python，也不用装编译器。**

```
LanCmd-Windows/
├── Start.exe              启动面板（72KB，双击就用这个）
├── start.bat              命令行启动
├── LanCmdAgent.exe        接收端本体（29KB，无控制台窗口）
├── LanCmdCtl.exe          管理工具（53KB，有输出）
├── lan_command_agent.py   主逻辑，不要删
├── python/                自带的 Python 3.11 运行时
└── 使用说明.txt
```

```bat
Start.exe                                 :: 面板启动（推荐）
start.bat                                 :: 命令行启动
LanCmdAgent.exe                           :: 静默启动（无窗口）

LanCmdCtl.exe --install-autostart         :: 开机自启
LanCmdCtl.exe --status                    :: 查状态
LanCmdCtl.exe --stop                      :: 停止
```

**为什么是两个 exe**：主程序编译成 GUI 子系统，"不显眼"靠的就是这个；代价是它自己带
`--status` 看不到输出。所以另编一个控制台版专门做管理。两者共用同一份配置和 pid 文件。

**怎么编出来的**：`mingw-w64` 交叉编译 `launcher.c`（aarch64 容器里也能产出 x86-64 PE），
配官方 Windows embeddable Python 运行时。不依赖 PyInstaller，不需要 Windows 环境。

改了 `lan_command_agent.py` 后重新出包：

```bash
apt-get install -y gcc-mingw-w64-x86-64 binutils-mingw-w64-x86-64   # 一次性
cd windows && ./build_windows_exe.sh
```

细节（PE 结构、资源编译、无 tkinter 的回退、未验证项）见
[windows/BUILD-WINDOWS.md](windows/BUILD-WINDOWS.md)。

> 也可以在这台 Windows 上跑 `build_exe.bat` 用 PyInstaller 打包，或者干脆不编译、
> 直接双击 `start_hidden.vbs`（前提是电脑装了 Python）。

## 六、构建（源码）

```bash
cd android
export ANDROID_HOME=<你的 SDK>
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest  # 3 单测 + 3 跨语言集成测试
```

签名用 `android/lancmd-release.jks`，口令放在 `keystore.properties`（不入库，见 `.gitignore`）。
自己发布请重新生成密钥库。Android SDK 需 platforms;android-36 + build-tools;35.0.0。

**ABI**：App 纯 Kotlin + AndroidX/Material，无 JNI/native 库，任何 ABI 都能装，
arm64-v8a 设备直接使用。`ndk.abiFilters` 里保留 `arm64-v8a` 只是给将来加 native 库时定调。

电脑端无需构建，纯标准库单文件。验证协议：

```bash
python3 windows/lan_command_agent.py --auto-accept --console   # 终端 A
python3 tools/test_client.py 127.0.0.1          # 基础功能（10 项）
python3 tools/test_transfer.py 127.0.0.1        # 分块传输 + 进程管理（25 项）
python3 tools/test_reconnect.py 127.0.0.1 8     # 断线重连（8 轮 RST 风暴）
```

---

## 七、目录

```
lan-cmd/
├── android/                       安卓工程（Kotlin + Material 3）
│   ├── app/src/main/java/com/aicode/lancmd/
│   │   ├── LanCmdApp.kt           Application 级启动（Activity 重建不断连）
│   │   ├── LanCmdService.kt       前台常驻服务，防后台冻结
│   │   ├── MainActivity.kt        控制台界面与全部功能面板
│   │   ├── ProcessDialog.kt       进程管理 GUI（搜索/排序/多选/结束）
│   │   ├── Prefs.kt               设备 ID、上次电脑、配对凭据、上传目录
│   │   └── net/
│   │       ├── ConnectionManager.kt  连接状态机：发现→握手→心跳→退避重连
│   │       ├── FileTransfer.kt       分块上传下载 + 进度与取消
│   │       ├── AgentClient.kt        单条长连接与请求匹配
│   │       ├── Discovery.kt          UDP 广播发现
│   │       └── Protocol.kt           常量与路径工具
│   └── app/src/test/              路径单测 + 跨语言协议/重连集成测试
├── windows/
│   ├── lan_command_agent.py       接收端（标准库单文件，含看护线程）
│   ├── start_hidden.vbs           无窗口启动
│   ├── start_console.bat          前台调试
│   ├── install_autostart.bat      开机自启（HKCU）
│   ├── uninstall_autostart.bat    取消自启
│   ├── status.bat / stop_agent.bat
│   ├── launcher.c                 接收端/管理工具启动器（两版共用）
│   ├── start_panel.c              Start.exe 面板源码
│   ├── start.bat                  命令行启动
│   ├── start_hidden.vbs           无窗口启动（源码方式）
│   ├── lancmd.rc                  exe 资源：图标 + 中文版本信息
│   ├── build_windows_exe.sh       Linux/容器内交叉编译，产出 dist/*.zip
│   ├── build_exe.bat              在 Windows 上用 PyInstaller 打包（备选）
│   ├── icon.ico                   exe 图标（16~256 共 7 个尺寸）
│   ├── version_info.txt           PyInstaller 用的版本资源
│   ├── 使用说明.txt               随分发包给最终用户的说明
│   └── BUILD-WINDOWS.md           Windows 打包完整说明
├── tools/
│   ├── test_client.py             基础功能自测（10 项）
│   ├── test_transfer.py           分块传输 + 进程管理（25 项）
│   └── test_reconnect.py          断线重连风暴测试
├── protocol.md                    线格式、命令表、重连语义
└── dist/
    ├── LanCmd-1.2.apk             已签名 APK
    └── LanCmd-Windows-1.2.zip     Windows 端解压即用
```

---

## 八、已验证

在容器内（Python 3.12 + JDK 17 + Android SDK 36）实测：

- `test_client.py`：10/10 通过（发现、握手、执行+退出码、目录、上传/下载/删除、未握手拒绝）
- `test_reconnect.py`：8 轮 RST 掉线全部重连成功，平均 12ms，服务端持续在线无重启
- 接收端被 `SIGKILL` 后重启，旧 token 仍免确认直连
- 端口被占用时持续重试，占用解除后自动开始监听
- 5 个并发连接互不影响，中途关闭 3 个后其余照常工作
- `SIGTERM` 优雅退出并清理 pid 文件
- `test_transfer.py`：25/25 通过。1.5MB 文件 3 块上传+下载内容一致、乱序分块被拒、
  大小不符被拒且不留残文件、取消上传、改名/建目录/删目录、进程列表按内存排序、
  筛选、结束真实进程（rc=-9）、拒绝结束接收端自身、结束不存在进程报错
- Kotlin 集成测试真实连 Python 接收端共 8 项：握手、命令执行、token 免确认重连、
  连续心跳、1.2MB 分块上传下载回环、进程列表与自身保护
- 布局/资源 33 个 ID 与代码引用 100% 对应，drawable 与 string 无缺失
- APK `apksigner verify` 通过（v2 签名，minSdk 24 原生支持）
- v1.3 网络改动：发现应答含 `lan_ips`/`ips`/`hotspot_ips`；`info` 的网卡带 `vpn`/`hotspot` 标记；
  网卡分类实测（`WireGuard Tunnel`/`TAP-Windows` → VPN 排除，`本地连接*`/`Wi-Fi Direct` → 热点保留，
  `WLAN`/`VMware` → 普通保留）；三套协议测试仍全过

**未验证**：真机/模拟器上的视觉渲染与前台服务行为（容器内无 Android 运行时）。

---

## 九、边界与已知限制

- **只有电脑端能“隐藏”**：`start_hidden.vbs` / exe 不显示窗口，但进程仍在任务管理器可见；
  安卓端是正常可见的 App（外加一条低优先级常驻通知）。没有做进程名伪装、没有反卸载/守护。
- **同一局域网**：优先靠 UDP 广播。跨网段、开了 AP 隔离的 Wi-Fi 发现不到，此时用“手动输入 IP”。
  VPN 与电脑热点场景已单独处理（见三·四节），但**手机连 VPN 且 VPN 客户端强制全局代理时**，
  局域网流量仍可能被劫持，需要在 VPN 里配置绕过规则。
- **热点模式下手机走移动数据会失效**：连电脑热点时手机通常没有互联网，属正常现象；
  如果系统自动切回移动数据，会连不上，需要在 Wi-Fi 设置里关掉“自动切换”。
- **传输未加密**：JSON 明文 + token 明文的 TCP，仅适用于你信任的家庭/办公局域网。
- **Windows 1.4.9 默认免确认**：仅在信任的局域网使用；需要首次连接人工确认时运行
  `LanCmdCtl.exe --no-auto-accept`，确认一次后该设备可凭 token 重连。
- 截屏、锁屏、剪贴板、开关机走 Windows API，只在 Windows 接收端生效；
  `sh` 模式是给 Linux/macOS 上跑同一份接收端调试用。
- 前台服务保活不等于“永不掉线”：系统极端省电、强制停止 App、断网都会断开，
  但恢复条件满足后会自动重连。

## 十、排错

| 现象 | 处理 |
|---|---|
| App 一直转圈找不到电脑 | 确认同一 Wi-Fi；关路由器 AP 隔离；防火墙放行 `pythonw.exe` 的 47823/47824；手动输 IP |
| 显示“已掉线，自动重连中”不动 | 电脑端是否在跑（`status.bat`）；点浮层「立即重连」或测延迟 |
| 一直“等待电脑确认” | 电脑端弹框被其他窗口挡住，或 `approval_timeout` 到了；重连一次 |
| 连接被拒绝 | 电脑上曾点过“否”，重试会再次弹框 |
| 命令乱码 | 中文 Windows 的 GBK 已多级解码；仍乱码时切 PowerShell 模式 |
| 后台常驻通知烦 | `⋮` →「关闭后台常驻」 |
| 想彻底重置 | 手机 `⋮` → 清除配对记录；电脑 `python lan_command_agent.py --reset-pairing` |
