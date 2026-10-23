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

## 3.1 分块传输

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
| `message` | `title? text` | `sent:true`（桌面消息已排入显示） |

- 会话绑定当前 TCP 连接、客户端 ID 和作用域，重连必须重新创建。`expires_in` 和 `capabilities.session_seconds` 均为 `null`，没有总时长或空闲时限。
- 手机页面进入后台时发送 `end`；连接关闭时接收端释放该连接的所有会话。接收端没有本机会话窗口；本机可停止整个接收服务。
- 手机通过原连接释放会话，不将旧会话 ID 发到自动重连后的新电脑连接。键盘页每 8 秒保活；屏幕约 800ms、摄像头约 1200ms 请求一帧（还需加上采集耗时）。这是 JPEG 预览，没有录音与视频编码。
- 按键与文字二选一；组合键最多 5 个不同按键，文字最多 1000 字。只控制当前用户桌面的前台应用；无法控制 UAC 安全桌面或权限更高的应用。
- 消息 1–2000 字、标题最多 60 字；后端最多同时显示 3 个消息框，不设每连接发送间隔。
- 手机 `hello.client` 统一为「LanCmd 手机」，不再发送制造商或机型；`client_id` 保持不变用于配对记录和会话身份。
- `screenshot` 与 `screen` 均要求有效的屏幕会话 ID。旧手机的其他命令仍兼容；工作台页面需要 Windows 1.5.0 或更新版本及完整辅助程序。
