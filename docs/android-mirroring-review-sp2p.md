# 安卓手机镜像投屏到电视：SP2P 逻辑与流程审查

审查日期：2026-11-10。仓库：Wilson0365/MiracastReceiverScale。分支：feature/system-p2p-app-media-side-by-side。审查代码提交：a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7。

本报告覆盖 Android 手机作为 Miracast Source、电视作为 Sink 的 APP 模式，以及 SYSTEM 模式的交接边界。依据为当前源码、独立协议审查及本次重新执行的 JVM 单元测试；未连接手机或电视，未执行真机投屏、抓包或端到端延迟测量。源码注释中的 Windows 历史实测不能作为本次 Android 手机兼容性证据。本次仅添加文档，未修复业务代码。

## 1. 结论

当前链路结构完整：系统建 Wi-Fi Direct 网络，应用连接手机 TCP 7236，先绑定 UDP 19000，再完成 RTSP SETUP/PLAY，接收 RTP 中的 MPEG-2 TS，解出 H.264 和音频，在自定义播放页渲染。成功探测的 TCP socket 会直接用于握手，匹配成功的 PLAY 响应才启动播放页。

但不能据此认定所有安卓手机都能正常投屏。最大的端到端前提是：电视固件开启系统 WFD 后，既要向手机公布可用接收端、完成 P2P，又不能在应用连接前占用手机的 RTSP 会话。wifi_display_on=1 只是当前 takeoveroff 设备实验采用的配置，不是跨固件的媒体所有权保证。

优先处理的问题是视频解码错误恢复、协议输入大小限制和握手/媒体超时。随后处理 RTP 来源校验、格式协商、动态参数变化及音画同步。独立包允许两款应用同时安装，但端口及系统 WFD 设置没有隔离，两款接收服务不宜同时运行。

## 2. 模式与责任

| 阶段 | APP（开关打开） | SYSTEM（开关关闭） |
|---|---|---|
| 手机发现电视、WFD IE、P2P 协商、组和 DHCP | 系统/电视固件负责 | 系统/电视固件负责 |
| 读取 P2P 状态及接口地址 | 应用观察系统网络 | 应用观察通道停止 |
| 手机 Source TCP 7236 | 应用主动连接 | 原生系统负责 |
| RTP/TS、解码、音频、显示 | 应用 UDP 19000、MediaCodec、AudioTrack、PlayerActivity | 原生系统负责 |
| 缩放、裁剪、旋转 | 应用播放页提供 | 原生系统决定 |
| 应用断线恢复 | 关闭媒体并扫描系统现有网络 | 不运行应用 Miracast 媒体 |

APP 不调用 createGroup/connect/discoverPeers/removeGroup，不注入 WFD IE，不删除或重建系统组。WfdServer 名字容易误导：它在这条链路上是 TCP 客户端，手机才是 TCP 7236 监听端。Miracast 镜像也不同于 DLNA URL 播放或 AirPlay 镜像。

旧 [android-mirroring-report.md](android-mirroring-report.md) 审查的是旧主动 P2P 架构，不应套用到本分支；旧手机名称筛选、主动建组和无条件 secure-settings 预检结论不适用。

## 3. 全流程

~~~mermaid
flowchart TD
    A[电视启动接收服务] --> B{取得 LAN IPv4 或等待满 5 分钟}
    B --> C[初始化多协议接收器并 reconcile 模式]
    C --> D{持久模式}
    D -->|SYSTEM| E[停止应用 Miracast 媒体和观察通道]
    D -->|APP| F[P2P 支持 / 运行时权限 / Wi-Fi 预检]
    F --> G[准备 wifi_display_on 设置租约]
    G --> H[观察系统 P2P 状态 / 启动媒体扫描]
    I[手机选择电视 / 固件广告 WFD Sink] --> J[系统完成 P2P 组与 IP]
    J --> H
    H --> K[读取 p2p 接口 IPv4 和 prefix]
    K --> L[优先 GO 或扫描 Source TCP 7236]
    L --> M[保留成功 TCP socket]
    M --> N{同步绑定 UDP 19000}
    N -->|失败| L
    N -->|成功| O[RTSP M1-M7 / 匹配成功 PLAY]
    O --> P[打开 PlayerActivity 并发布 Surface]
    O --> Q[UDP RTP 接收 / 序号检查 / 有界队列]
    Q --> R[TS PAT / PMT / PES 解复用]
    R --> S[H.264 SPS/PPS + IDR / MediaCodec]
    P --> S
    S --> T[Surface 显示 / 比例适配 / 裁剪旋转]
    R --> U[AAC ADTS 或 LPCM / AudioTrack]
    O --> V[RTSP 断开 / TEARDOWN / 系统组丢失]
    V --> W[关闭应用媒体 / 关闭已播放页面]
    W --> H
    X[遥控返回 / 通知停止] --> Y[保存 SYSTEM / 全部接收器清理 / 退出]
~~~

### 3.1 服务入口与权限

服务先启动前台通知，然后等待普通网络地址，最多 5 分钟、每 5 秒重试。重复启动已经运行的服务只 reconcile Miracast，不重复创建整套接收器。普通启动使用 mediaPlayback 前台类型，开机启动使用 connectedDevice。

APP 预检检查 P2P 服务、运行时权限和 Wi-Fi 状态。WRITE_SECURE_SETTINGS 仅在需要实际更改全局设置时成为要求；当前值已经为 1 时无需写入。进入 APP 失败会清理应用资源并报告 Failed，持久 APP 意图仍可保留，界面恢复可重试；不等于自动把持久模式改成 SYSTEM。

证据：[service/CastReceiverService.kt:420](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt#L420)、[service/CastReceiverService.kt:512](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt#L512)、[miracast/MiracastController.kt:75](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastController.kt#L75)。

### 3.2 系统 P2P 与设置交接

WifiP2pObserver 注册系统广播并 requestConnectionInfo，周期刷新间隔 30 秒。系统组形成后触发媒体扫描；组丢失后关闭会话和探测 socket。观察 generation 与媒体 networkGeneration 用于阻止旧回调、旧探测进入新会话。

设置租约在应用写入前保存原值；未写入就不取得恢复权。观察到外部修改后永久放弃本会话恢复权；进程重启也不信任历史所有权。恢复只针对仍可证明由本会话拥有的设置写入，不删除系统 P2P 组。

这仍受两个边界约束：全局设置无法隔离两个应用；写入完成到 ContentObserver 注册之间存在观察盲窗，不能宣称所有外部修改竞争都已消除。

证据：[miracast/MiracastController.kt:42](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastController.kt#L42)、[miracast/WifiP2pObserver.kt:1](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WifiP2pObserver.kt#L1)、[miracast/SystemP2pHandoff.kt:24](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/SystemP2pHandoff.kt#L24)、[miracast/SystemSettingLease.kt:1](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/SystemSettingLease.kt#L1)。

### 3.3 连接手机

应用只读取名称以 p2p 开头、已启用的 IPv4 接口，按实际 prefix 生成候选，排除本机及适用的网络/广播地址。TV 非 GO 时优先连接 groupOwnerAddress；其余候选最多 1024 个，32 个一批，连接超时 1500 ms。宽子网只扫描附近窗口，不保证覆盖所有地址。

扫描通常每 3 秒进行；组形成后 20 秒内加快到 1 秒；等 P2P 地址最多 15 秒，每 500 ms 检查。成功 TCP socket 直接保留用于 RTSP。组丢失或停止使 generation 失效并关闭 socket，应用不会通过重建 P2P 来恢复。

证据：[miracast/WfdServer.kt:46](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L46)、[miracast/WfdServer.kt:178](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L178)、[miracast/WfdServer.kt:298](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L298)、[miracast/P2pSubnet.kt:1](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/P2pSubnet.kt#L1)。

### 3.4 RTSP 握手

~~~mermaid
sequenceDiagram
    participant P as 安卓手机 Source
    participant A as 电视 APP Sink
    participant U as UDP19000 / 播放页
    Note over P,A: 系统 P2P 和 IP 已就绪；APP 主动连接手机 TCP7236
    A->>U: 同步绑定 UDP19000（失败则不继续握手）
    P->>A: M1 OPTIONS
    A->>P: 200 + Public
    A->>P: M2 OPTIONS
    P->>A: 200
    P->>A: M3 GET_PARAMETER 能力请求
    A->>P: H.264 / AAC / UDP19000 / 无 HDCP / 无 UIBC
    P->>A: M4 SET_PARAMETER 格式与 presentation URL
    A->>P: 200
    P->>A: M5 SET_PARAMETER trigger SETUP
    A->>P: 200
    A->>P: M6 SETUP client_port=19000
    P->>A: 匹配 CSeq 的 200 + Session
    A->>P: M7 PLAY + Session
    P->>A: 匹配 CSeq 的 200
    A->>U: onStreamStart / 打开播放页
    P->>U: RTP / MPEG-2 TS
    Note over A,U: PLAY 成功与首包、首帧显示是不同里程碑
~~~

响应按 pending CSeq 关联，未匹配或重复响应不推进播放；非 200 响应关闭会话。SETUP 必须提供 Session，PLAY 有 Session 时检查一致性。presentation URL 只取第一个值，避免尾部 none 进入请求行。

当前能力固定为 AVC profile 位图 02、level 位图 10（High / Level 4.2），CEA 0x1C0（720p60、1080p30、1080p60）；音频只广告 AAC 00000001。无内容保护、UIBC 和 EDID。这里不能承诺受保护视频可投，不能把 TS 对 LPCM/HEVC 的识别当作已协商支持它们。

证据：[miracast/WfdSessionHandler.kt:50](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L50)、[miracast/WfdSessionHandler.kt:140](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L140)、[miracast/WfdSessionHandler.kt:187](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L187)。

### 3.5 RTP、TS、视频和音频

UDP 接收线程处理 RTP v2、CSRC 和扩展头，序号跟踪拒绝迟到/重复包，记录前向丢包。256 个 payload 的队列把收包与解码分离，满时丢最旧数据；丢包触发视频等待 IDR 和音频重同步。

TsDemuxer 根据 PAT 找 PMT，再找视频和音频 PID，按 continuity 检查 PES 连续性。视频输出 Annex B H.264 与 PTS；长度为 0 的视频 PES 等下一 PES 开始才输出，可能增加一个 PES 周期的等待。音频支持 AAC ADTS 分帧或 LPCM 大端转小端。

视频先收 SPS/PPS，取得有效 Surface 后建立 MediaCodec，并等待 IDR。Surface 改变时优先切换输出，失败则重建；没有有效 Surface 时释放视频 decoder。PlayerActivity 只发布目标和提供显示控制，RTP 接收器归 WfdServer 所有。旋转/裁剪/比例适配属于显示处理，不改变手机发送协议。

音频使用独立线程、浅队列和 AudioTrack，积压时丢旧 PES。视频立即送显，音频不按 PTS 调度，没有共同音画时钟；不能从“收到即播”推导出固定低延迟或无漂移。没有 Surface 时新的音频 PES 不入队，但已有队列/硬件缓冲可能短暂继续播放。

证据：[miracast/RtpReceiver.kt:79](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L79)、[miracast/TsDemuxer.kt:54](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/TsDemuxer.kt#L54)、[miracast/MiracastVideoRenderer.kt:40](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastVideoRenderer.kt#L40)、[miracast/MiracastAudioPlayer.kt:106](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastAudioPlayer.kt#L106)、[ui/PlayerActivity.kt:760](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt#L760)、[airplay/VideoDecoder.kt:180](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/airplay/VideoDecoder.kt#L180)。

### 3.6 断线、重连和退出

手机 TEARDOWN、TCP 结束或系统组丢失会清理应用媒体。已经开始播放的会话结束后，服务广播停止播放页，但接收服务继续运行，等待再次连接。没有 PLAY 的握手失败也回到扫描，不 reset 系统 P2P。

遥控返回或通知停止属于显式全局退出：保存 SYSTEM，停止 Miracast 和其他接收器、前台通知与服务。Activity 销毁只释放自己的显示资源，不应停止下一位发送者的 RTP 接收器；从最近任务划走也不等同于显式停止服务。

媒体重启有清理等待保护，但 stopAndAwait 的 5 秒只包住 stop() 之后的等待；stop() 内同步 TEARDOWN、锁等待和原生媒体释放不受该上限覆盖。因此不能承诺整个退出必定在 5 秒内完成。

证据：[service/CastReceiverService.kt:255](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt#L255)、[service/CastReceiverService.kt:438](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt#L438)、[miracast/WfdServer.kt:256](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L256)、[ui/PlayerActivity.kt:1679](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt#L1679)。

## 4. 审查发现与处理建议

优先级定义：P1 为稳定性或输入资源耗尽问题，应先修复；P2 为连接恢复、兼容性或数据一致性问题；P3 为诊断/部署限制。以下“已确认”指缺少相应代码保护，不表示已在电视上复现故障。

| 编号 / 优先级 | 触发与影响 | 源码证据 | 建议 |
|---|---|---|---|
| F01 / P1 | 视频 MediaCodec 进入错误状态后 isHealthy=false，但 renderer 只因 decoder 空或 Surface 改变而重建。同一 Surface 可能持续黑屏/冻结，RTSP 仍在线。恢复路径缺失已确认，硬件触发待实测。 | [miracast/MiracastVideoRenderer.kt:57](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastVideoRenderer.kt#L57)；[airplay/VideoDecoder.kt:191](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/airplay/VideoDecoder.kt#L191)；[airplay/VideoDecoder.kt:260](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/airplay/VideoDecoder.kt#L260) | 消费健康状态，重建 decoder，等待新 IDR；失败采用有限重试并向会话反馈。 |
| F02 / P1 | RTSP Content-Length 直接决定 ByteArray 分配；仅头部有 64 KiB 上限。超大正整数可在未发送 body 前造成内存耗尽。PES 不定长缓冲也无上限。 | [miracast/WfdSessionHandler.kt:100](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L100)；[miracast/WfdSessionHandler.kt:107](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L107)；[miracast/TsDemuxer.kt:180](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/TsDemuxer.kt#L180)；[miracast/TsDemuxer.kt:232](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/TsDemuxer.kt#L232) | 限制 RTSP body 和 PES 大小，严格拒绝无效长度，超限终止或重同步；不能只靠捕获 Exception 处理 OOM。 |
| F03 / P1 | decoder 初始化中 configure/start 抛异常时，赋值尚未完成，临时 VideoDecoder 中创建的 MediaCodec 没有释放路径；下一访问单元可再次申请。 | [miracast/MiracastVideoRenderer.kt:83](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastVideoRenderer.kt#L83)；[airplay/VideoDecoder.kt:153](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/airplay/VideoDecoder.kt#L153)；[airplay/VideoDecoder.kt:167](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/airplay/VideoDecoder.kt#L167) | 初始化异常必须释放临时资源，并限制重试频率。 |
| F04 / P2 | 15 秒/90 秒是每次 socket 读的空闲超时，不是 SETUP/PLAY 事务总时限。持续零碎字节/keep-alive 可长期占用唯一会话；SETUP 后未 PLAY 也用 90 秒。没有首 RTP/媒体停滞看门狗，RTP error 回调仅记录日志。 | [miracast/WfdSessionHandler.kt:91](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L91)；[miracast/WfdSessionHandler.kt:189](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L189)；[miracast/WfdServer.kt:189](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L189) | 分别设置握手总 deadline、pending 请求 deadline、PLAY 后首包与持续媒体超时，区分正常静态画面和故障。 |
| F05 / P2 | UDP 绑定所有本地接口，未关联 Source IP/端口、SSRC，PT 只记录而不要求 33。其他来源的有效 RTP 可能污染序号、产生假丢包或注入 TS。 | [miracast/RtpReceiver.kt:90](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L90)；[miracast/RtpReceiver.kt:115](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L115)；[miracast/RtpReceiver.kt:127](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L127)；[miracast/WfdSessionHandler.kt:197](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L197) | 根据协商/Source 校验来源和 PT，建立 SSRC 会话策略；不能硬猜发送 UDP 端口。 |
| F06 / P2 | 固定广告 1080p60/High 4.2，没有查询电视实际 AVC decoder 能力；M4 选定格式只记日志并回复成功。可能握手成功后才解码失败。 | [miracast/WfdSessionHandler.kt:66](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L66)；[miracast/WfdSessionHandler.kt:159](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L159)；[miracast/WfdSessionHandler.kt:174](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdSessionHandler.kt#L174) | 根据硬件能力构建广告，校验 Source 选项并绑定实际解码路径；不支持时明确拒绝或协商降档。 |
| F07 / P2 | 首次 SPS/PPS 后停止捕获，首次视频 PID 后停止处理 PMT，PAT 也固定。Source 若改变码流参数/PID，没有显式重配置路径。部分硬件可能接受带内 SPS，但缓存不会更新。 | [miracast/MiracastVideoRenderer.kt:92](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastVideoRenderer.kt#L92)；[miracast/TsDemuxer.kt:105](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/TsDemuxer.kt#L105)；[miracast/TsDemuxer.kt:129](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/TsDemuxer.kt#L129) | 比较参数集与 PSI 版本，更新映射/CSD并安全重建；测试 Source 实际参数变化，勿假定手机旋转一定改变编码尺寸。 |
| F08 / P2 | RTP 到达 100、102、101 时立即把 101 计为丢失，迟到包再丢弃。没有重排窗口，乱序会引发等待 IDR 与音频重同步。 | [miracast/RtpSequenceTracker.kt:12](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpSequenceTracker.kt#L12)；[miracast/RtpReceiver.kt:136](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L136) | 评估小型有界重排窗口，在延迟与恢复间取舍；这不是旧版 65535 丢包计数错误。 |
| F09 / P2 | 接收线程在 mediaLock 外直接修改 renderer 的 awaitingKeyframe；字段没有 volatile/锁，事件也不在媒体队列中排序。存在状态可见性与旧排队帧消费的竞争。 | [miracast/RtpReceiver.kt:140](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L140)；[miracast/RtpReceiver.kt:178](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L178)；[miracast/MiracastVideoRenderer.kt:25](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastVideoRenderer.kt#L25) | 由单个解码线程处理带 generation 的 discontinuity 事件，确保丢帧边界与媒体顺序一致。 |
| F10 / P2 | RTP P 位和末尾 padding count 未处理，尾部全部进入 TS；短 padding 常被忽略，但较长或 TS 形状 padding 可被误解析。PAT/PMT 也没有跨 TS 包的完整 section 重组。 | [miracast/RtpReceiver.kt:145](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L145)；[miracast/RtpReceiver.kt:192](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L192)；[miracast/TsDemuxer.kt:128](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/TsDemuxer.kt#L128) | 校验并去除 padding；完整 PSI 重组与 section 长度校验，加入跨包 PMT 测试。 |
| F11 / P2 | 音频 PES PTS 被丢弃，AAC 时间戳为 0，视频立即送显。没有音画同步或长期漂移修正。 | [miracast/TsDemuxer.kt:42](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/TsDemuxer.kt#L42)；[miracast/MiracastAudioPlayer.kt:217](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastAudioPlayer.kt#L217)；[airplay/VideoDecoder.kt:186](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/airplay/VideoDecoder.kt#L186) | 真机测量口型和长时漂移，再设计低延迟共同时间基准及纠偏。 |
| F12 / P2 | 仅有 P2P、没有普通 LAN 地址时，Miracast 初始化也被多协议网络门槛推迟最多 5 分钟。LAN helper 默认排除 p2p 接口。 | [service/CastReceiverService.kt:525](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt#L525)；[service/CastReceiverService.kt:532](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/service/CastReceiverService.kt#L532)；[utils/NetworkUtils.kt:135](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/utils/NetworkUtils.kt#L135) | Miracast 入口与 AirPlay/DLNA 的 LAN 就绪条件解耦，独立观察系统 P2P。 |
| F13 / P2 | stopAndAwait 先同步 stop，再启动 5 秒 timeout；发送 TEARDOWN、锁与原生释放可能先阻塞。 | [miracast/WfdServer.kt:256](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L256)；[miracast/WfdServer.kt:277](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L277)；[miracast/RtpReceiver.kt:220](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/RtpReceiver.kt#L220) | 拆分停止请求与等待，避免锁内网络写，针对原生阻塞建立可观测的退出界限。 |

部署与诊断事项：

- P2P 接口必须以 p2p 开头、IPv4 可读取，Source 默认端口 7236，宽子网候选有限；不满足时没有通用 fallback。
- SYSTEM 的原生播放成功不能证明 APP 拿到了媒体所有权；root 或 secure-settings 授权也不能保证这一点。
- TS 识别 HEVC PID，但 Miracast renderer 使用 AVC NAL 类型与 AVC decoder；本链路未支持 HEVC。因为广告只包含 AVC，应归为意外输入处理限制，不等于合规 AVC 手机必失败。
- hasActiveSession() 在 handler 存在时即为 true，注释却称 PLAY 已成功；连接请求及播放文案仍称 Windows。应增加 Source 名称、握手中/PLAY/首包/首帧分层状态。证据：[miracast/WfdServer.kt:183](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L183)、[miracast/WfdServer.kt:287](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/WfdServer.kt#L287)、[ui/PlayerActivity.kt:868](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/ui/PlayerActivity.kt#L868)。
- 观察通道不可用统一显示 PERMISSION_MISSING，可能把通道失效误诊为权限。证据：[miracast/MiracastController.kt:62](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/miracast/MiracastController.kt#L62)。
- 两个安装包默认自启都为 true，接收端口相同、wifi_display_on 是全局设置；独立 applicationId 只解决安装覆盖，不解决运行冲突。证据：[util/AppSettings.kt:28](https://github.com/Wilson0365/MiracastReceiverScale/blob/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/main/java/com/weekd/miracastreceiver/util/AppSettings.kt#L28)。验收前关闭其中一个自启并停止其服务。
- 设置写入到观察注册的盲窗需要设备级竞争测试；不能把已覆盖的 lease 逻辑测试当作 ContentObserver 时序证明。

## 5. 验证结果与覆盖边界

本次执行：

~~~powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
~~~

结果：BUILD SUCCESSFUL，26 个任务实际执行，耗时 2 分 35 秒。JUnit XML 合计 90 tests，0 failures、0 errors、0 skipped；其中 miracast 包 38 tests。第一次命令显示 UP-TO-DATE，因此又使用 --rerun-tasks 重新执行。本次没有重新执行 assembleDebug、lintDebug，也没有仪器测试，报告不把以前的构建或 lint 结果作为此次验证。

| 已有测试组 | 能支持的结论 | 不能支持的结论 |
|---|---|---|
| WfdResponseTest / WfdSessionStopTest | CSeq 匹配、重复/错误响应、标准 TEARDOWN | 所有手机的完整 M1-M7 协议兼容性 |
| RtpBindTest / WfdServerStopTest / WfdGroupLossTest | 端口先绑定、扫描退出等待、过期组探测被拦截 | 电视原生 socket/codec 释放时限与所有并发竞争 |
| RtpSequenceTrackerTest | 前向丢包、迟到重复拒绝、16 位回绕 | 无线乱序下的画面质量、丢包恢复速度 |
| TsDemuxerTest | 合成 AAC/LPCM PES、下一 PES 视频 flush、连续性断点 | 全部 PSI/PES 输入、真实手机码流、动态 PMT/SPS |
| SystemSettingLeaseTest / MiracastMediaLifecycleTest | 租约与生命周期逻辑、外部修改放弃恢复、进程恢复策略 | 固件全局设置含义与 ContentObserver 实际送达时序 |
| AudioThreadStopTest | join helper 不误报仍活着的线程 | 原生 AudioTrack/MediaCodec 停止行为 |

测试来源：[app/src/test](https://github.com/Wilson0365/MiracastReceiverScale/tree/a0e84e5f0b1ba521ba61d9b21da07ba00f4f3bd7/app/src/test/java/com/weekd/miracastreceiver/miracast)。所有测试通过仍不等于安卓镜像已在电视成功。

## 6. 真机验收与故障定位

### 6.1 里程碑

| 标记 | 必须收集的证据 | 未通过时优先查 |
|---|---|---|
| T1 系统组 | groupFormed、isGroupOwner、GO 地址 | 固件 WFD 广告、系统投屏开关、手机 Miracast 支持、P2P 权限 |
| T2 P2P IP | p2p 接口 IPv4、prefix、路由 | DHCP、接口命名、LAN 初始化等待 |
| T3 TCP | 应用 PID 持有连接手机 :7236 的 socket | 系统已占用 Source 会话、路由、Source 监听、扫描范围 |
| T4 PLAY | matching PLAY acknowledged 及对应 Session | 抓取 CSeq、M3/M4、SETUP Transport、错误响应、事务超时 |
| T5 首 RTP | First RTP packet、来源/SSRC/PT、包速率 | 协商端口、固件媒体抢占、Source 未发送、UDP 端口冲突 |
| T6 首帧 | decoder 输出与电视画面，缩放控制生效 | Surface、SPS/PPS、IDR、decoder 错误、广告能力过高 |

当前已有日志不能直接证明首帧显示；decoder initialized 和 keyframe found 只说明进入解码流程。首帧需要实际显示/输出回调与时间戳证据，不能用 T4 替代 T6。

### 6.2 设备矩阵（全部待真机执行）

| 组合/场景 | 操作 | 验收记录 |
|---|---|---|
| 目标电视 + OPPO | SYSTEM 成功后停止原生播放，切 APP，从手机重新选择电视 | 电视固件/Android 版本、手机型号/ColorOS、T1-T6、系统是否先连接 Source |
| 同电视 + 小米/三星 | 使用该型号实际支持的无线显示入口，依次连接 | MIUI/HyperOS/One UI、Miracast 可用性、所选视频音频格式；不能把 Google Cast 当 Miracast |
| Windows 辅助基线 | 无线显示连接到 APP | 仅作同电视协议对照，不替代手机测试 |
| Wi-Fi 无路由器 | TV Wi-Fi 打开、无普通 LAN，仅系统 P2P | 测量入口是否等 5 分钟，T1/T2 与服务启动时间 |
| 手机停止、再次连接 | 连续至少 10 次，含握手中取消 | 无残留端口/旧页面，接收服务继续，下一会话成功 |
| APP/SYSTEM 切换与长按重启 | 播放中操作，再由手机重新连接 | 应用媒体停止、不删除系统组、模式与租约正确 |
| 显示生命周期 | 手机横竖屏、电视缩放/旋转/裁剪、后台再前台 | 新 Surface 恢复、比例正确、参数变化是否发生、无永久黑屏 |
| 音画与长时 | 口型/计时视频、弱信号、持续 30-60 分钟 | 首帧耗时、端到端延迟、音画差/漂移、断音花屏次数 |
| 显式退出 | 遥控返回、通知停止、退出前后检查进程/socket | 音频停止、应用端口释放、SYSTEM 已保存、系统组保留 |
| 外部设置修改 | APP 时系统切 off/on，含准备/退出时的竞争 | 保留外部意图、无错误恢复，记录 observer 时间序列 |
| 两应用共存 | 两包安装，分别启动；仅受控地测试冲突 | 不覆盖安装，关闭一个自启，记录端口占用提示及全局设置干扰 |

### 6.3 采集命令

~~~sh
adb shell getprop ro.build.fingerprint
adb shell settings get global wifi_display_on
adb shell ip -4 addr
adb shell ip route
adb shell dumpsys wifip2p
adb shell pidof com.weekd.miracastreceiver.systemp2p
adb shell ss -tnp
adb shell ss -unap
adb logcat -v threadtime > miracast-session.log
~~~

筛选 Miracast、WFD、RTP、VideoDecoder、Network not ready 与 codec 错误，保留从启动到断线的完整时间线。在受限电视上 dumpsys/ss 的字段或 PID 可见性由固件和调试权限决定，命令不可用时应记录限制。设置尚非 1 且写入被拒绝时，按 README 为 SP2P 包授权 WRITE_SECURE_SETTINGS，再复测；不要把授权操作当兼容性验证。

建议逐阶段记录 T1-T6 时间，分别报告 P2P、TCP、RTSP、首包和首帧耗时；音画差使用同时可见/可听的事件测量。先检查真正的 socket 所有者，避免看到系统原生画面却误认为应用解码成功。

## 7. 建议实施顺序

1. 先在目标电视 + OPPO 证明系统建 P2P、应用持有 Source TCP 与 UDP、应用 Surface 真正出画；若系统抢先消费 RTSP，会阻断整个 APP 模式，需先确定固件可用交接机制。
2. 修复 F01-F04，补协议输入上限、初始化失败释放、真实握手 deadline 和首媒体超时测试。
3. 修复 F05-F10，补来源校验、硬件能力约束、动态码流、乱序/padding/PSI 与并发 discontinuity 验证。
4. 根据实测改善 F11-F13 和两个应用的运行冲突提示，再完成长时、切换、断线和离线验收。

本报告提交到当前 side-by-side feature 分支；release/v1.2.13、takeoveroff 与此前 feature 分支保留。报告是后续修复和设备验收的依据，不代表这些建议已经实施。
