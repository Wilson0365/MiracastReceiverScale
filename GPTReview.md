这两个类一补上，整个架构就基本清楚了。我现在认为，你最初关于 Takeover OFF 的判断方向是正确的，而且有了更直接的代码证据。

但需要修正一个重要细节：

不是系统把 RTSP 流转发给你的 WfdServer，而是系统或其他 P2P 管理逻辑先让 P2P 网络建立起来，然后你的 App 主动连接投屏端的 7236 端口，自己完成 RTSP 握手，并直接接收 RTP 数据。

这比「系统接管 RTSP，再把流交给 App」更符合目前代码的实际行为。

## 1. 现在可以还原出完整数据链路

你的三个类分别负责不同的工作：

| 模块                      | 实际职责                             |
| ----------------------- | -------------------------------- |
| P2P / 系统 Miracast 服务    | 提供可用的 P2P 网络环境（具体谁建立 Group 还需验证） |
| `WfdServer`             | 扫描 P2P 网段，主动连接 Source 的 7236     |
| `WfdSessionHandler`     | 执行 RTSP Sink 侧协议                 |
| `RtpReceiver`           | 监听本机 UDP 19000，接收 RTP            |
| `TsDemuxer`             | 解复用 MPEG-2 TS                    |
| `MiracastVideoRenderer` | 将 H.264 数据送入 MediaCodec          |
| `PlayerActivity`        | 提供自定义播放器的 Surface                |

具体流程如下：

## Takeover OFF 的实际工作链路

第一阶段：P2P 网络建立

系统或其他 P2P 管理逻辑建立 Group，Android 出现 p2p\* 网络接口；WFD IE 广播也需要符合代码注释中的 root 注入前提。

第二阶段：App 寻找投屏端\
`WfdServer.dialSource()`

获取 P2P 网段前缀，例如 192.168.49.，扫描 .2～.254 的 TCP 7236 端口。

找到 Source 后保留这条 TCP Socket，不再重新连接。

第三阶段：App 自己处理 RTSP\
`WfdSessionHandler`

M1～M7，协商视频格式、RTP 端口、Session ID，发送 SETUP 和 PLAY。

第四阶段：App 自己收 RTP\
`RtpReceiver`

本机 UDP 19000 → RTP Header → TS Payload → TsDemuxer。

第五阶段：自定义播放器\
`MiracastVideoRenderer → MediaCodec → PlayerActivity.mirrorSurface`

自己的 Surface 渲染

## 2. 最关键的证据：WfdServer 根本不是 Server

看这段：

```
private fun tryConnect(ip: String): Socket? = try {
    Socket().also {
        it.connect(
            InetSocketAddress(ip, port),
            CONNECT_TIMEOUT_MS
        )
    }
} catch (e: Exception) {
    null
}
```

你的 App 是 TCP Client。

它实际上做的是：

```
手机（App）                         投屏端（Source）

本地随机端口  ───── TCP 连接 ─────>  7236
              <──── RTSP ────────
              ───── RTSP ────────>

本地 UDP 19000 <──── RTP 视频 ────  Source
```

还有一个非常关键的设计：

```
val session = connected.first()
connected.drop(1).forEach {
    runCatching { it.close() }
}
return@coroutineScope session
```

它保留真正建立的 TCP Socket，直接交给：

```
val handler = WfdSessionHandler(
    context,
    socket,
    rtpPort
)
```

这意味着 App 并不是向系统索要一个现成的 RTSP 会话，而是自己发现 Source、自己建立连接、自己管理 RTSP。

你最初的猜测中：

> 系统 Miracast 服务处理 P2P 握手，RTSP 流打到 WfdServer。

应该修正为：

> 系统或其他 P2P 管理逻辑提供已经建立的 P2P 网络，App 在该网络上主动连接 Source 的 RTSP Server，自己完成 RTSP 和 RTP 接收。

这两个架构的区别非常大。

## 3. 为什么 Takeover OFF 反而可以成功？

现在可以解释这个现象了。

你的 `WfdServer` 没有调用：

```
WifiP2pManager.createGroup()
WifiP2pManager.connect()
WifiP2pManager.requestGroupInfo()
```

它只做了：

```
NetworkInterface.getNetworkInterfaces()
    .toList()
    .firstOrNull {
        it.name.startsWith("p2p") && it.isUp
    }
```

也就是说，它有一个前置条件：

P2P 网络必须已经存在。

这很可能就是 Takeover OFF 的价值所在。

### Takeover ON

可能发生的过程：

```
App 尝试控制 P2P
    ↓
App 自己管理 Group / WFD 信息
    ↓
碰到系统权限或 P2P GO 竞争
    ↓
连接不稳定或 Source 无法正确发现 Sink
```

### Takeover OFF

可能发生的过程：

```
App 设置 wifi_display_on=1
    ↓
App removeGroup()
    ↓
原有 P2P Group 被释放
    ↓
系统 WFD / Wi-Fi P2P 管理逻辑接管
    ↓
P2P 接口重新出现，网络建立
    ↓
App 的 WfdServer 检测到 p2p* 接口
    ↓
App 扫描 Source:7236
    ↓
App 自己完成 RTSP + RTP
    ↓
自己的 PlayerActivity 显示画面
```

这是一种非常有价值的职责分离。

但有个细节不能忽略：你的 `WfdServer` 注释写着：

```
Source 能发现本机，依赖 wpa_supplicant
广播 WFD IE，这需要 root 注入
```

因此，不能直接说 Takeover OFF 下所有 WFD 工作都交给系统了。

更准确的划分是：

| 工作           | 目前代码能确定的情况             |
| ------------ | ---------------------- |
| P2P Group 建立 | 不在这两个类中                |
| P2P 接口获取     | App 读取现有接口             |
| WFD IE 广播    | 注释指出依赖 `WfdRootHelper` |
| RTSP 建立      | App 主动连接               |
| RTP 接收       | App 自己接收               |
| 解码及渲染        | App 自己完成               |

所以我认为你的方向应该是：不争夺系统 P2P 管理权，但保留 App 对媒体会话的控制权。

## 4. 代码中我发现三个值得优先处理的问题

这几个地方可能影响你后续的稳定性。

### 问题一：RTP Socket 启动存在竞态

你现在的调用：

```
val receiver = RtpReceiver(
    rtpPort,
    { PlayerActivity.mirrorSurface }
).apply {
    start()
}

val handler = WfdSessionHandler(
    context,
    socket,
    rtpPort
)

handler.handleSession()
```

看起来是先启动 RTP 接收器，再进行 RTSP。

但是 `RtpReceiver.start()` 内部是：

```
scope.launch {
    socket = DatagramSocket(port)
    isRunning = true
    // 开始收包
}
```

`start()` 返回时，UDP Socket 不一定已经绑定成功。

因此存在这个时间窗口：

```
RtpReceiver.start()
    ↓
启动异步协程
    ↓
WfdSessionHandler 开始 RTSP
    ↓
M3 告诉 Source：UDP 19000
    ↓
Source 开始发 RTP
    ↓
UDP Socket 可能还没 bind
```

这可能导致第一批 RTP 包丢失，严重时直接无法出画面。

建议改成先同步完成 UDP bind，再开始 RTSP。

例如：

```
fun start(): Boolean {
    if (isRunning) return true

    return try {
        socket = DatagramSocket(port).apply {
            receiveBufferSize = 1024 * 1024
        }

        isRunning = true
        active = this

        scope.launch {
            runDecoder()
        }

        scope.launch {
            receiveLoop()
        }

        Timber.i("RTP socket bound: UDP $port")
        true
    } catch (e: Exception) {
        Timber.e(e, "Failed to bind RTP socket")
        onError?.invoke("RTP 接收启动失败: ${e.message}")
        false
    }
}
```

然后：

```
val receiver = RtpReceiver(
    rtpPort,
    { PlayerActivity.mirrorSurface }
)

if (!receiver.start()) {
    socket.close()
    return
}

rtpReceiver = receiver

val handler = WfdSessionHandler(context, socket, rtpPort)
```

这里的 `receiveLoop()` 需要把原来的 UDP 接收循环抽出来，避免重复绑定 Socket。

这样才能真正保证：告诉 Source 的 RTP 端口已经准备就绪。

### 问题二：扫描网段固定假设 `/24`

你的代码：

```
?.hostAddress
?.substringBeforeLast('.')
?.plus(".")
```

实际上是假定 P2P 网络一定是 `/24`。

目前典型的 Android P2P 网段可能是：

```
192.168.49.0/24
```

但不应该把这个条件当成永久保证。

另外：

```
.firstOrNull {
    it.name.startsWith("p2p") && it.isUp
}
```

如果设备同时有多个 P2P 接口，你只取第一个，可能拿到错误的网段。

建议后续改进为：

- 获取 IPv4 地址对应的真实 prefixLength。
- 计算实际 Network Prefix 和 Broadcast Address。
- 检查多个 P2P 接口。
- 结合当前 P2P Group 信息确定正确接口。

### 问题三：RTP 丢包统计可能误判

你目前的代码：

```
if (expectedSeq >= 0 && seq != expectedSeq) {
    val lost = (seq - expectedSeq + 0x10000) and 0xFFFF
    packetsLost += lost
}
```

如果收到乱序包，或者重复包，这段代码可能把它统计成接近 65535 个丢包。

例如：

```
期待序号：100
实际收到：102
下一包：101
```

前面确实丢了一个包，但下一包 101 是迟到的，不应该再次认定丢失 65535 个包。

建议增加序号前进窗口判断，并对重复和乱序包单独处理。

另外，`RtpReceiver` 的队列满了会主动丢弃旧包，但当前 `packetsLost` 没有统计这类丢弃。视频信息面板显示的丢包率因此可能低于真实值。

## 5. 还有一个 RTSP 判断需要补强

上一份 `WfdSessionHandler` 代码中：

```
} else if (playRequested) {
    Timber.i("WFD: PLAY acknowledged, RTP should start on $rtpPort")
    onStreamStart?.invoke(rtpPort)
    startPlayerActivity()
    playRequested = false
}
```

这里没有真正检查响应是否成功。

只要 `playRequested == true`，下一条 RTSP 响应就有可能触发播放器启动，即使它返回的是错误状态码。

建议至少校验：

```
val statusLine = msg.lineSequence().firstOrNull() ?: return

if (!statusLine.contains("200 OK")) {
    Timber.e("RTSP request failed: $statusLine")
    return
}
```

正式实现还应该校验 CSeq，确保收到的确实是对应 PLAY 请求的响应。

另外，启动 PlayerActivity 不代表已经有视频帧，应该把以下三个时间点分别记录：

1. RTSP PLAY 成功。
2. 收到第一个有效 RTP 包。
3. MediaCodec 输出第一帧。

这样能准确定位是连接问题、传输问题还是解码问题。

## 6. 如何彻底证明这套架构？

我建议你在 Takeover OFF 成功投屏时做一次进程级验证。

执行：

```
adb shell ss -tnp | grep 7236
```

预期看到类似：

```
ESTAB
192.168.49.100:xxxxx
192.168.49.123:7236
```

重点是确认连接两端 IP 和 PID，判断是否由你的 App 持有 TCP 连接。

然后：

```
adb shell ss -unap | grep 19000
```

检查 App 是否绑定 UDP 19000。

最后查看：

```
adb logcat -v time | grep -E \
"WFD: connected to source|WFD session started|First RTP packet|RTP stats|PLAY"
```

理想情况下的证据链

1. P2P 接口出现

   `p2p*` 接口 UP，具有 IPv4 地址。
2. App 建立 TCP

   App PID 持有到 Source:7236 的 ESTABLISHED 连接。
3. App 完成 RTSP

   获取 Session ID，PLAY 返回 200。
4. App 收到 RTP

   UDP 19000 收到数据，`packetsReceived` 持续增加。
5. App 输出视频

   MediaCodec 输出首帧，目标为 `PlayerActivity.mirrorSurface`。

## 7. 我的最终判断

架构方向正确

你现在这套代码实际上实现了一个非常有用的设计：

借用系统的 P2P 网络能力，但不借用系统的媒体渲染能力。

具体来说：

- 系统或其他有权限的组件负责让 P2P 网络可用。
- RootHelper 负责代码注释所述的底层 WFD IE 广播。
- App 主动发现投屏端并建立 RTSP。
- App 自己接收 RTP。
- App 自己解复用、解码和播放。
- 最终画面进入自己的 Surface，因此可以控制比例、缩放和其他显示参数。

还有一个小建议：你的 `WfdServer` 类名容易造成误解。它实际上更像 `WfdSourceConnector` 或 `WfdSinkSessionClient`，因为它并没有监听 7236，而是在主动连接 Source。

现在唯一尚未从代码中证明的事情，是 Takeover OFF 后究竟哪个系统组件建立了 P2P Group，以及 WfdRootHelper 在这个过程中是否仍然参与 WFD 广播。

把 `WfdRootHelper` 和执行 `removeGroup()`、设置 `wifi_display_on` 的服务代码也结合起来，就能把整个控制面和媒体面完整还原出来。
