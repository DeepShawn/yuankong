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
 "host":"DESKTOP-8K2","port":47823,"os":"Windows","ver":"1.0",
 "id":"9f3c1a7b2e","auto_accept":false}
```

`id` 是本机派生标识（主机名+架构+CPU 数），用于多机区分；不含任何凭据。
没有应答就换手动 IP，协议一致。

## 2. 握手（TCP）

```json
{"id":1,"cmd":"hello","v":1,"client":"Pixel 7","client_id":"and-<uuid>","token":""}
```

- `client_id` 首次由手机生成并存本地，之后不变。
- `token` 为空或与电脑端记录不符 → 进入配对流程：
  - `auto_accept=true`：直接配对
  - 否则电脑端弹确认框，`approval_timeout`（默认 45s）无人应答视为拒绝

成功：

```json
{"id":1,"ok":true,"paired":true,"token":"<32 hex>",
 "agent":"DESKTOP-8K2","agent_id":"9f3c1a7b2e","fresh":true}
```

- `agent_id` 是电脑的稳定标识（主机名+架构+CPU 数派生）。手机重连时用它核对
  “还是不是同一台电脑”，即使 IP 变了也能认回来。
- `fresh` 只在首次配对时为 true；带有效 token 重连时省略。
  手机据此判断“是否还需要电脑确认”。

## 2.1 断线重连语义

| 场景 | 行为 |
|---|---|
| 手机断 Wi-Fi / 杀进程 | TCP 连接由 RST 或 keepalive 超时（30s 空闲探测、10s×3 次）判定失效 |
| 手机重连 | 用本地 token 直接 `hello`，`paired=true`、无 `fresh`，**不需要电脑确认** |
| 电脑端进程重启 | `paired.json` 持久化，token 仍有效，手机重连照旧免确认 |
| 电脑端 IP 变化 | 手机每 3 次失败重新 UDP 广播，按 `agent_id` 认回同一台机器 |
| 手机侧网络恢复 | `ConnectivityManager` 回调立即触发重连，不等退避计时 |
| 心跳 | 手机每 20s `ping`，连续 2 次超时（8s）主动断开并重连 |

电脑端不主动断开空闲连接以外的任何状态；单次断线只结束该连接线程，
监听循环与发现应答继续提供服务（看护线程会在循环意外退出时退避重启）。

被拒：

```json
{"id":1,"ok":false,"error":"denied","message":"电脑端拒绝了连接"}
```

之后每条请求都要先握手；未握手的请求返回 `未授权，请先握手`（ecode 401）。
token 存 `paired.json`，手机存 SharedPreferences，所以再次连接是静默的。

## 3. 请求/响应

请求：`{"id":<int>,"cmd":"<name>", ...参数}`
响应：`{"id":<int>,"ok":true, ...结果}` 或 `{"id":<int>,"ok":false,"error":"...","ecode":<int>}`

`id` 由客户端递增，用于并发匹配；服务端原样回填。

### 命令表

| cmd | 参数 | 成功返回 |
|---|---|---|
| `info` | — | `name host user os arch python agent cpus cwd uptime_s drives[]` |
| `exec` | `shell`(cmd/powershell/sh) `line` `cwd?` `timeout?` | `code out err ms` |
| `ls` | `path` | `path parent items[{name,dir,size,mtime}]` |
| `get` | `path` `max?` | `name path size sha256 data`(base64)，仅限 ≤32MB |
| `put` | `path` `data`(base64) `overwrite?` | `path size`，仅限 ≤32MB |
| `upload` | 见下节 | 分块上传（大文件） |
| `download` | 见下节 | 分块下载（大文件） |
| `rm` | `path` | `removed` |
| `mkdir` | `path` | `path` |
| `rename` | `path` `to` | `from to` |
| `screenshot` | — | `w h format:"bmp" data`(base64) |
| `lock` | — | `locked` |
| `power` | `action`(shutdown/restart/logoff/sleep) | `action` |
| `clip` | `action`(get/set) `text?` | `text` |
| `ps` | `filter?` | `items[{name,pid,mem,mem_bytes,session}]` `total` `self_pid` |
| `kill` | `pid` `force?` | `pid killed out` |
| `ping` | — | `ts`(ms) |
| `agent` | `action`(status/stop) | `name ver uptime_s clients` / `stopping` |

错误码：`1` 一般错误，`2` 超过文件上限，`3` 传输会话不存在/过期，
`4` 分块序号错乱，`5` 数据超过声明大小，`6` 大小不匹配，`7` sha256 校验失败，
`8` 拒绝结束受保护进程，`9` 结束进程失败，`401` 未握手。

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
- 数据先写 `目标.lancmd-part`，`end` 时大小与 sha256 都对上才 `os.replace` 原子落盘
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
- `screenshot` 走 user32/gdi32 的 BitBlt + GetDIBits，直接输出 32 位 BMP，
  安卓 `BitmapFactory` 可解，不依赖 Pillow。
- 每个 TCP 连接一个线程，长连接保持；`sock.settimeout(300)` 空闲 5 分钟断开
  （手机会每 20s 心跳，正常不会触发）。
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
