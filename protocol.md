# LanCmd 协议 v1

UDP 47824 做发现，TCP 47823 做命令。帧 = 一行 UTF-8 JSON，以 `\n` 结尾，单帧上限 64MB
（文件用 base64 内联，接收端默认限制 32MB 明文）。

## 1. 发现（UDP）

手机向每个网卡的广播地址（含 255.255.255.255）发：

```json
{"app":"LANCMD","v":1,"type":"discover"}
```

接收端单播回：

```json
{"app":"LANCMD","v":1,"type":"agent","name":"DESKTOP-8K2",
 "host":"DESKTOP-8K2","port":47823,"os":"Windows","ver":"1.5.1",
 "id":"9f3c1a7b2e","auto_accept":true,
 "ips":["192.168.1.5","192.168.137.1","10.8.0.2"],
 "lan_ips":["192.168.1.5","192.168.137.1"],
 "hotspot_ips":["192.168.137.1"]}
```

`id` 是本机派生标识（主机名+架构+CPU 数），用于多机区分；不含任何凭据。

网卡字段说明：

| 字段 | 含义 |
|---|---|
| `ips` | 本机全部 IPv4（含 VPN 网卡），诊断用 |
| `lan_ips` | 排除 VPN 后的局域网地址，**手机优先从这里挑** |
| `hotspot_ips` | 识别为 Windows 移动热点的网卡地址（通常是 `192.168.137.1`） |

电脑可能同时有 Wi-Fi、热点、VPN 多块网卡。手机收到应答后按以下顺序选地址：
与当前手机网络同网段 → 与应答源地址相同 → 列表第一个。
选错的表现是「发现了却连不上」，所以这一步不能省。

### VPN 与热点的处理

| 场景 | 手机侧 | 电脑侧 |
|---|---|---|
| 手机开 VPN | 每个物理 Network 单独 `bindSocket` 后广播，绕开 tun 路由；跳过 `TRANSPORT_VPN` 网络 | — |
| 电脑开热点 | 探测帧额外**单播发往默认网关**（热点场景网关即电脑）；连「无互联网 Wi-Fi」时首次即连网关，超时 2.5s | 热点网卡名（`Wi-Fi Direct`、`本地连接*`、`Mobile Hotspot`）不判为 VPN，正常上报 |
| 电脑开热点 + 防火墙 | — | 必须放行 47823/47824。`--fix-firewall` 或 Start.exe 一键处理（自动提权） |
| 电脑开 VPN | — | `lan_ips` 排除 tap/tun/wireguard 等网卡，避免手机连到不可达地址 |

识别规则：网卡名含 `tap/tun/vpn/ppp/wireguard/utun/ipsec/zerotier/tailscale/clash` → 判为 VPN 排除；
含 `wi-fi direct` / `本地连接*` / `mobile hotspot` → 判为热点保留。
VMware / VirtualBox / Hyper-V 网卡**不排除**，它们有时确实能到局域网。

没有应答就换手动 IP，协议一致。

## 2. 握手（TCP）

```json
{"id":1,"cmd":"hello","v":1,"client":"LanCmd 手机","client_id":"and-<uuid>","token":""}
```

- `client_id` 首次由手机生成并存本地，之后不变。
- 当前接收端不校验 token 来限制连接；`token` 为空或不符时仍完成握手，并返回已有或新建的配对 token。
- `auto_accept` 在发现应答中固定为 `true`。`--auto-accept` / `--no-auto-accept` 是被忽略的兼容参数，没有确认窗口或 `approval_timeout`。

成功：

```json
{"id":1,"ok":true,"paired":true,"token":"<32 hex>",
 "agent":"DESKTOP-8K2","agent_id":"9f3c1a7b2e","fresh":true}
```

- `agent_id` 是电脑的稳定标识（主机名+架构+CPU 数派生）。手机重连时用它核对
  “还是不是同一台电脑”，即使 IP 变了也能认回来。
- `fresh` 表示是否新建配对记录：首次配对为 `true`，已有配对记录为 `false`。它不是授权凭据。

## 2.1 断线重连语义

| 场景 | 行为 |
|---|---|
| 手机断 Wi-Fi / 杀进程 | TCP 连接由 RST 或 keepalive 超时（30s 空闲探测、10s×3 次）判定失效 |
| 手机重连 | 发送 `hello`，返回 `paired=true`、`fresh=false`（已有记录） |
| 电脑端进程重启 | `paired.json` 持久化，返回已有配对记录；手机重新握手 |
| 电脑端 IP 变化 | 手机每 3 次失败重新 UDP 广播，按 `agent_id` 认回同一台机器 |
| 手机侧网络恢复 | `ConnectivityManager` 回调立即触发重连，不等退避计时 |
| 心跳 | 手机每 20s `ping`，连续 3 次超时（10s）主动断开并重连；其他请求在途时暂停心跳失败判定 |

电脑端不设 TCP 连接空闲超时；单次断线只结束该连接线程，
监听循环与发现应答继续提供服务（看护线程会在循环意外退出时退避重启）。

之后每条请求都要先握手；未握手的请求返回 `未授权，请先握手`（ecode 401）。
配对记录存 `paired.json`，手机存 SharedPreferences；当前 token 不构成访问控制。

## 3. 请求/响应

请求：`{"id":<int>,"cmd":"<name>", ...参数}`
响应：`{"id":<int>,"ok":true, ...结果}` 或 `{"id":<int>,"ok":false,"error":"...","ecode":<int>}`

`id` 由客户端递增，用于并发匹配；服务端原样回填。

### 命令表

| cmd | 参数 | 成功返回 |
|---|---|---|
| `info` | — | `name host user os arch python agent cpus cwd uptime_s drives[] lan_ips[] hotspot_ips[] interfaces[] firewall_ok admin` |
| `exec` | `shell`(cmd/powershell/sh) `line` `cwd?` `timeout?` | `code out err ms` |
| `ls` | `path` | `path parent items[{name,dir,size,mtime}]` |
| `get` | `path` `max?` | `name path size sha256 data`(base64)，仅限 ≤32MB |
| `put` | `path` `data`(base64) `overwrite?` | `path size`，仅限 ≤32MB |
| `upload` | 见下节 | 分块上传（大文件） |
| `download` | 见下节 | 分块下载（大文件） |
| `rm` | `path` | `removed` |
| `mkdir` | `path` | `path` |
| `rename` | `path` `to` | `from to` |
| `screenshot` | `sid` | 1.5.0 起等同 `screen`，要求屏幕会话，返回 JPEG |
| `lock` | — | `locked` |
| `power` | `action`(shutdown/restart/logoff/sleep) | `action` |
| `clip` | `action`(get/set) `text?` | `text` |
| `ps` | `filter?` | `items[{name,pid,mem,mem_bytes,session}]` `total` `self_pid` |
| `kill` | `pid` `force?` | `pid killed out` |
| `ping` | — | `ts`(ms) |
| `agent` | `action`(status/stop) | `name ver uptime_s clients` / `stopping` |

`info` 里 `firewall_ok` 用于让手机判断「连不上是不是防火墙拦的」，
`interfaces[].vpn/hotspot` 供排查网卡选择问题。

错误码：`1` 一般错误，`2` 超过文件上限，`3` 传输会话不存在/过期，
`4` 分块序号错乱，`5` 数据超过声明大小，`6` 大小不匹配，`7` sha256 校验失败，
`8` 拒绝结束受保护进程，`9` 结束进程失败，`401` 未握手，`403` 共享会话不存在、已停止或作用域/身份不符。

1.6.1 起，`ps` 返回额外的 `admin` 布尔值，表示接收端进程当前的 Windows 管理员状态。旧版本缺少此字段时客户端显示未知权限。此字段只查询状态，不触发提权；管理员启动需要在电脑 `Start.exe` 中操作并经 UAC 允许，现有普通权限进程不会自行升级权限。

## 3.1 分块传输

### 1.7.0 音量与本机输入暂停

Windows `capabilities.features` 新增 `audio`、`local_input`。

- `audio`：`action=get` 返回 `volume`（0–100 整数）、`muted`（布尔）。`action=set` 至少提供一个 `volume` / `muted`，修改后返回实际设备状态；两个字段独立，不自动取消静音。没有默认音频设备时返回错误。
- `local_input`：需要当前连接拥有的 `input` 会话 `sid`。`action=status/pause/release`；1.7.2 起仅支持手动解除（不限时长），`seconds` 可省略或为整数 0；非零时长返回错误，不启动暂停。1.7.0/1.7.1 的 1–30 秒模式不再支持。返回 `paused`、`remaining`（手动解除时为 null）。不可替换正在使用的暂停会话，不按总时长自动恢复。其他连接无法解除该会话。`status` 与普通会话 keepalive 更新活跃时间；暂停期间超过 12 秒没有会话活动即恢复。Android 每 3 秒查询状态，退出共享关闭会话立即恢复。
- 本机解除：物理键盘同时按 A R T E X + Ctrl + Delete，或 Ctrl+Alt+Del。1.7.1 起不创建本机暂停提示窗口，状态与解除方法显示在安卓端。仅抑制键鼠事件，不能覆盖安全桌面或独立触屏原始输入，不修改硬件驱动。远程输入通过专用标记保留，远程合成的解除组合不会触发物理解除。
- `input.keys` 新增 Windows `OEM_1/OEM_PLUS/OEM_COMMA/OEM_MINUS/OEM_PERIOD/OEM_2/OEM_3/OEM_4/OEM_5/OEM_6/OEM_7` 标点虚拟键，遵循电脑当前键盘布局。仍只发送完整的按下/释放组合，最多 5 键，不提供跨请求的持续按住状态。

### 文件分块

单帧上限 64MB、base64 后膨胀 4/3，所以大于几十 MB 的文件不能塞进一帧。
大文件走三阶段会话，块大小由接收端下发（默认 512KB）：

### 上传（手机 → 电脑）

```json
{"id":10,"cmd":"upload","action":"begin","path":"C:\Users\me\a.zip","size":104857600}
{"id":10,"ok":true,"sid":"9a07b24dd8a3c281","chunk":524288,"path":"C:\Users\me\a.zip"}
```

```json
{"id":11,"cmd":"upload","action":"chunk","sid":"9a07...","seq":0,"data":"<base64>"}
{"id":11,"ok":true,"received":524288,"total":104857600}
```

```json
{"id":12,"cmd":"upload","action":"end","sid":"9a07...","sha256":"<可选>"}
{"id":12,"ok":true,"path":"C:\Users\me\a.zip","size":104857600,"sha256":"..."}
```

- `seq` 必须从 0 连续递增，错序直接报错（防止丢块悄悄写坏文件）
- 数据先写 `目标.<sid>.lancmd-part`，`end` 时大小与可选 sha256 校验通过后提交。`overwrite` 默认 `true`；设为 `false` 时，提交会原子拒绝已存在的目标，即使目标是在上传期间新建的。
- 1.5.1 起严格拒绝非法 base64 和超过声明总大小的分块，拒绝时不推进块序号或写入数据。提交失败会清理传输会话与临时文件。小文件 `put` 的 `overwrite=false` 同样使用独占创建。
- `{"action":"abort"}` 或连接断开时删除临时文件，不留半截
- 默认上限 `max_upload_bytes` = 4GB

### 下载（电脑 → 手机）

```json
{"id":20,"cmd":"download","action":"begin","path":"C:\big.iso"}
{"id":20,"ok":true,"sid":"...","name":"big.iso","size":104857600,"chunk":524288}
{"id":21,"cmd":"download","action":"chunk","sid":"...","offset":0,"len":524288}
{"id":21,"ok":true,"offset":0,"data":"<base64>","eof":false,"size":104857600}
{"id":22,"cmd":"download","action":"end","sid":"..."}
```

按 `offset` 随机读，客户端逐块推进；`eof=true` 表示已到文件尾。
会话挂在 TCP 连接上，连接断开即释放文件句柄。

### 会话生命周期

- 每个连接独立的 `uploads` / `downloads` 表，互不串扰
- `transfer_idle_sec`（默认 600s）无进展的会话会被回收，避免手机中途消失占着临时文件
- 手机侧协程取消时会先发 `abort` 再断开，电脑端立即清理

### 示例

```json
{"id":7,"cmd":"exec","shell":"cmd","line":"ipconfig /all"}
{"id":7,"ok":true,"code":0,"out":"Windows IP 配置\r\n...","err":"","ms":42}
```

## 4. 实现说明

- `exec` 在 Windows 下用 `cmd /c "chcp 65001>nul & <line>"`（或 powershell 前置
  `[Console]::OutputEncoding=[Text.Encoding]::UTF8`）统一成 UTF-8，再按
  utf-8 → gbk → mbcs 多级解码兜底；`CREATE_NO_WINDOW` 防止闪黑框。
- `screen` / `screenshot` 使用 GDI 捕获全部显示器，`camera` 使用 Media Foundation；WIC 编码 JPEG，不依赖 Pillow，最大单帧 4MB。
- 每个 TCP 连接一个线程，长连接保持，不设连接空闲超时。`exec_timeout=0` 表示接收端不限制命令时长，客户端仍可通过 `timeout` 指定单次执行时限。
- 监听套接字启用 TCP keepalive（idle 30s / interval 10s / count 3），
  半开连接约 60s 内被内核判定失效，不会长期占用。
- 端口被上一次实例占用时持续重试（最长 30 次、退避 0.2~2s），不直接退出。
- 单实例保护：`agent.pid` + 存活检测；`--stop` 读取 pid 终止。
- `ps` 在 Windows 用 `tasklist /fo csv /nh` 解析，内存列 `12,345 K` 转换为 `mem_bytes` 供排序；
  `self_pid` 回传接收端自身 PID，客户端据此禁止“一键结束自己”。
- `kill` 拒绝 `pid 0/1` 与接收端自身；失败时把 `taskkill` 原始报错回传（而不是只给退出码）。
- 看护线程：TCP 监听与 UDP 应答各由一个 Supervisor 托管，循环异常退出后按
  2s→30s 退避自动重启，保证接收端长期在线。
- `SIGTERM/SIGINT/SIGBREAK/SIGHUP` 均触发优雅退出并清理 pid 文件。


## 5. 工作台命令（1.5.1）

所有命令先完成 `hello`。屏幕、摄像头和键盘使用独立作用域的会话 ID；当前接收端创建会话时不要求本机确认。

| cmd | 参数 | 成功返回 |
|---|---|---|
| `capabilities` | — | `version features[] session_seconds` |
| `storage` | — | `home drives[{path,total,free,used,percent}]` |
| `session` | `action:"begin" scope:"screen"/"camera"/"input"` | `sid scope expires_in:null` |
| `session` | `action:"keepalive" sid` | `active:true` |
| `session` | `action:"end" sid` | `stopped:true`，重复调用也成功 |
| `screen` | `sid` | `format:"jpeg" data`(base64) |
| `camera` | `sid action:"list"` | `devices[{id,name}]` |
| `camera` | `sid device:0..31 action:"frame"` | `format:"jpeg" data`(base64) |
| `input` | `sid keys:["CTRL","V"]` 或 `sid text:"你好"` | `sent:true` |
| `pointer` | `sid action x y button? delta?` | `sent:true`（Windows 1.6.0 起） |
| `message` | `title? text` | `sent:true`（桌面消息已排入显示） |

- 会话绑定当前 TCP 连接、客户端 ID 和作用域，重连必须重新创建。`expires_in` 和 `capabilities.session_seconds` 均为 `null`，没有总时长或空闲时限。
- 手机页面进入后台时发送 `end`；连接关闭时接收端释放该连接的所有会话。接收端没有本机会话窗口；本机可停止整个接收服务。
- 手机通过原连接释放会话，不将旧会话 ID 发到自动重连后的新电脑连接。键盘页每 8 秒保活；屏幕约 800ms、摄像头约 1200ms 请求一帧（还需加上采集耗时）。这是 JPEG 预览，没有录音与视频编码。
- 按键与文字二选一；组合键最多 5 个不同按键，文字最多 1000 字。只控制当前用户桌面的前台应用；无法控制 UAC 安全桌面或权限更高的应用。
- 消息 1–2000 字、标题最多 60 字；后端最多同时显示 3 个消息框，不设每连接发送间隔。
- 手机 `hello.client` 统一为「LanCmd 手机」，不再发送制造商或机型；`client_id` 保持不变用于配对记录和会话身份。
- `screenshot` 与 `screen` 均要求有效的屏幕会话 ID。旧手机的其他命令仍兼容；工作台页面需要 Windows 1.5.0 或更新版本及完整辅助程序。

### 1.6.0 远程桌面触控

`capabilities.features` 新增 `pointer`。鼠标命令使用 `input` 作用域会话，不能复用屏幕会话；与按键共享同一连接。`action` 可为 `move`、`click`、`double_click`、`down`、`up`、`scroll`，`button` 为 `left`（默认）、`right` 或 `middle`；滚动的 `delta` 为 -10 到 10 的非零整数，每步 120 个 Windows 滚轮单位。

`x`、`y` 是完整虚拟桌面 JPEG 的归一化坐标，必须是 0 到 1 的有限数值。Windows 使用绝对虚拟桌面坐标，因此兼容截图缩放及位于主显示器左侧、上方的屏幕。Android 按 FIT_CENTER 实际画面映射，黑边不产生点击；拖动离开图像时将结束位置限制在图像边缘。

```json
{"id":21,"cmd":"pointer","sid":"input-session-id","action":"click","x":0.5,"y":0.5,"button":"left"}
```

停止输入会话、关闭连接或发送输入失败时，接收端释放该会话持有的鼠标按键。另一个会话不能移动或释放正在拖动的鼠标。`up` 可重复调用；停止和旋转页面也会结束当前手势。

Android 工作台建立画面、键鼠和终端三条专属连接，各自重新握手，绑定原目标。`exec` 不占用键鼠或画面连接。停止等待终端结果不等于撤销已经执行的电脑命令；命令仍受客户端指定的执行超时约束。页面不自动开始共享，离开或切换电脑后需要重新开始。

## 可移动设备管理（1.8.0）

Windows `capabilities.features` 新增 `devices`。完成握手后使用：

```json
{"id":1,"cmd":"devices","action":"list"}
{"id":2,"cmd":"devices","action":"policy","scope":"storage","mode":"blocked"}
{"id":3,"cmd":"devices","action":"policy","scope":"storage","mode":"read_only"}
{"id":4,"cmd":"devices","action":"policy","scope":"portable","mode":"blocked"}
{"id":5,"cmd":"devices","action":"policy","scope":"storage","mode":"allow"}
{"id":6,"cmd":"devices","action":"eject","device_id":"列表返回的完整 PnP ID"}
```

`list`（默认动作）返回 `admin`、`devices` 及 `policies`。设备字段：`id/name/kind/size/paths/protected/ejectable`；`kind` 为 `storage` 或 `portable`，`size` 为字节，`paths` 为挂载路径数组。仅列出 USB 存储和 USB 便携设备。系统磁盘可显示但禁止弹出。

`policies.storage` / `policies.portable` 含 `mode`、`managed`、`conflict`；`mode` 为注册表配置的 `allow/read_only/blocked`，不代表当前驱动已生效。`managed` 表示存在 LanCmd 恢复记录；`conflict` 表示本机策略被其他程序改动。`policies.deny_all` 表示 HKLM/HKCU 的全局拒绝访问。HKCU 仅指接收端运行账户；其他驱动限制及域策略仍可能覆盖。`portable` 不接受 `read_only`。

`policy` 和 `eject` 要求管理员接收端，失败以正常 RPC 错误返回安卓，不弹出授权窗口。`mode=allow` 恢复 LanCmd 首次修改前的值，不清除预先存在的策略；无管理记录时不写入。修改按设备类别应用，断线及重启后保留。恢复记录 `device-policy.json` 需随原配置保留；外部策略冲突时拒绝继续写入。策略保存返回 `policies/notice`，需重新插拔设备，必要时重启。部分固定类型 USB 硬盘不受可移动磁盘策略限制。

`eject` 重新枚举并验证 ID，仅允许当前列表中可弹出且非系统设备，调用 Windows 配置管理器并提供 veto 输出，设备忙时返回错误，不显示 PnP 拒绝弹窗、不强制卸载。成功返回 `ejected/notice`。安卓确认与操作绑定原连接，不随重新连接或切换电脑迁移。

## 工作台扩展（1.9.0）

`apps` 默认 `action=list` 返回 `items[{handle,pid,title,name,minimized}]`；`activate/minimize/close` 必须提供当前列表中的 `handle` 字符串与 `pid` 整数。关闭为 WM_CLOSE，非强制终止；前台切换受 Windows 限制。

`files` 提供 `action=open/preview/extract` 与 `path`。打开使用 Windows 默认 open 动词；预览返回 `kind=text/image/archive`，对应 `text/encoding/truncated`、base64 `data`、或 `items/count/truncated`。解压仅 ZIP，`destination` 为新目录，成功返回 `path/count/bytes`；不覆盖已有目标，拒绝越界、链接、加密和不兼容 Windows 的路径，限制 2000 项、512 MB。

`commands` 默认 `action=list` 返回当前 `client_id` 的 `batches`；`action=add` 提供 `name` 与 `items[{line,shell,delay}]`，每批 1–50 条、每条等待 0–86400 秒，shell 为 cmd/powershell。第一条从启动开始等待，后续从前一条结束后等待，失败继续下一条。每条最多执行 300 秒；批次含 id/created/finished/items，每项含 status、due、started、finished、result 或 error。状态为 pending/waiting/running/completed/failed/cancelled。`action=cancel,batch_id` 取消尚未开始的项，不撤销 running 项。最多同时运行 4 批，结果 stdout/stderr 各截断 8192 字；手机断线不取消，接收端退出不持久化。

`local_input` 的 pause/status 新增 `touch_devices`。capabilities 的 `touch_pause` 表示接收端支持标准 HID 触屏临时暂停；触屏存在但无法识别或禁用时失败，不降级为仅键鼠暂停。触屏独立恢复进程监视原进程管道与心跳，在解除、管道结束或 6 秒无心跳时重新启用本次禁用的设备，不设持久禁用标记。输入会话原 12 秒心跳恢复与本机快捷键仍适用。

## 2.2.0 recordings / gestures

`recordings` actions: `devices`, `list`, `start` (`mode`: audio/screen/camera, `audio`: bool, `microphone`: -1 default or device id, `camera`: id, `seconds`: 1..3600), `stop`/`delete` (`recording_id`). Receiver-generated filenames, owner client_id checks, max 3 recordings and 200 records per owner. Files persist locally, no automatic deletion.

`gestures`: read-only `dimensions` returns virtual desktop width/height. Other actions require input-session `sid`: `play` (`delay`: 0..86400, `events`: 1..6000 `{ms,action,x,y}`), `status`, `cancel`. Events are move/down/up, normalized finite coordinates, monotonic integer milliseconds <=300000 and balanced left-button down/up. Exclusive remote pointer reservation; session heartbeat <12 seconds; cancel on session end. No Windows privacy permissions or indicators are changed.

## 2.3.0 management

`files` adds `text_read` (`encoding`: auto or explicit) and `text_create`/`text_write` (`text`, `encoding`, `newline`, `revision` required on write). Bounded to 512 KiB, exclusive create, optimistic content hash, Windows ReplaceFile. Encoding IDs: utf-8, utf-8-bom, gbk, gb18030, utf-16-le/be; newline preserve/crlf/lf/cr.

`scripts`: run(path), list, cancel(job_id); BAT/CMD/VBS only, owner client_id, 4 concurrent, 300 seconds, 8 KiB per output stream. `tasks`: list/detail(pid)/terminate(pid,created). `app_locks`: list/lock(path)/unlock(lock_id)/forget(lock_id), selected EXE execute ACL journal with conflict checks.

`registry`: root HKCU/HKLM/HKCR/HKU/HKCC, path, view 32/64; browse with key_offset/value_offset pages of 100; get(name); set(name,value,revision); delete_value(name,revision); create_key(child); delete_key(empty only); history/restore(backup_id). Value is {type,data}; integers and binary use strings; MULTI_SZ uses array. Missing value revision is `missing`.

`browser`: list/open(url,browser=default or fresh inventory ID), HTTP(S) only. `privacy_devices`: list; set_enabled(device_id,enabled), admin; mute(device_id,muted), capture endpoints only. Actual permissions, system indicators and hardware lights are unchanged.

Request envelope fields `id` (numeric correlation ID) and `cmd` are reserved. Entity IDs in response items remain `id`; send them back through the action-specific parameter (`recording_id`, `job_id`, `lock_id`, `backup_id`, or `device_id`).
