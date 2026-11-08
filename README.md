# Miracast Receiver — Android TV 投屏接收端

运行在 Android TV（小米等）上的 Miracast/WFD Sink 应用。核心目标：让自定义播放器成为投屏目标，从而能调整画面比例、做水平平移等系统投屏做不到的事。

---

## 功能特性

- **APP 模式**：完全接管 Wi-Fi Display，通过 Wi-Fi Direct P2P 自动接收手机投屏，走自定义播放器
- **SYSTEM 模式**：退出占用，将投屏控制权还给电视系统原生服务
- **autoConnectAndroid**：无需用户操作，TV 自动发现并连接 OPPO/Vivo/Xiaomi/Samsung 手机
- **P2P 自愈**：RTSP 握手失败（"传输通道被占用"）自动重置 P2P 组，无需手动重启手机
- **DriftWatch**：监测系统覆盖 `wifi_display_on` 开关，一次漂移自动修复，连续两次让步给系统
- **遥控器画面调整**：按 MENU 键打开比例控制浮层，支持多种宽高比、垂直/水平平移、精细对齐

---

## 遥控器操作

MENU 键打开比例控制浮层后：

| 按键 | 功能 |
|------|------|
| 左 / 右 | 切换比例模式 |
| 上 / 下 | 垂直平移 |
| 长按左 / 长按右 | 水平快速平移 |
| 按住 OK + 左 / 右 | 水平精细平移（慢速，便于精确对齐） |
| OK（单击） | 确认当前比例 |
| Back | 关闭浮层 |

---

## APP 模式工作原理

### autoConnectAndroid 连接流程

```
TV 开 APP 模式
  └─ discoverPeers()
       └─ PEERS_CHANGED 广播收到 OPPO/Vivo/Xiaomi/Samsung 设备
            └─ connect(groupOwnerIntent=15)  ← TV 抢 GO 角色（OPPO 的 intent=14）
                 └─ P2P-GROUP-FORMATION-SUCCESS  TV 成为 Group Owner
                      └─ WfdServer 扫描 P2P 子网，连接 Source 的 TCP 7236
                           └─ RTSP M1–M7 握手
                                └─ PLAY → 开始推流到自定义播放器
```

### "传输通道被占用"自动恢复

OPPO 等手机在 RTSP 会话没有收到 `TEARDOWN` 就断开时，WFD 状态机会卡在"session active"，拒绝新的连接。之前的表现是必须重启手机。

现在的处理路径：

```
TCP 连上 7236，但没走到 PLAY（streamStarted=false）
  └─ WfdServer.onSessionFailed 触发
       └─ MiracastController.scheduleP2pReset()
            └─ WifiDirectManager.disconnectSender() → removeGroup()
                 └─ OPPO 收到 P2P disconnect 事件，WFD 状态机重置
                      └─ onGroupDropped → scheduleRebuild() → 重新 discoverPeers
                           └─ 15~30s 后自动恢复连接
```

### APP 模式与系统投屏的共存

**现象**：APP 模式下打开系统投屏 app，投屏走系统；再回到自定义 app，走自定义播放器。

**原因**：

`wifi_display_on = 1` 不是锁，只是"WFD 子系统已启用"的全局标志。系统投屏服务看到它是 1，同样认为功能已开启，正常工作。

实际控制权取决于**谁先占住 P2P Group Owner 角色**，这是 `wpa_supplicant` 层的竞争：

- **自定义 app 运行时**：app 持续跑 `discoverPeers`，手机广播出现时抢先调 `connect(groupOwnerIntent=15)`，TV 赢得 GO → 投屏走自定义播放器
- **系统投屏 app 打开时**：系统服务主动发起 `discoverPeers + connect()`，它比被动等待的自定义 app 更快，抢先完成 P2P 协商 → 投屏走系统播放器

两套服务都调 `WifiP2pManager`，操作同一个 `wpa_supplicant`。没有互斥锁，完全靠时序竞争。这使得两者自然共存：自定义 app 用于日常投屏，TV 内置投屏 app 打开时自动接管，关掉后下次投屏自动回到自定义播放器。

### 另一条路：系统处理 P2P，app 只接流（待验证）

> 这是一个待测试的替代架构方向，记录在此。

当 app 调 `removeGroup()` 让出 P2P 控制权后，实测发现系统 Miracast 服务接管了连接——而投屏画面依然进入了自定义播放器。分析如下：

```
takeover OFF：
  app 写 wifi_display_on=1 + 调 removeGroup()
    → P2P 组消失，系统 Miracast 服务拿回控制权
    → OPPO 再投屏时，系统服务处理 P2P 握手
      （系统签名有权限授权 UserAuthorizingJoinState）
    → 连接成功后，RTSP 流打到 WfdServer（7236 端口仍在监听）
    → 自定义 PlayerActivity 播放 → 可以调画面比例 ✓
```

**关键洞察**：app 做了一件正确的事——让路。系统负责 P2P 握手这道难题（需要系统签名权限），app 只负责接收 RTSP 流并渲染。两者各司其职。

**真正的目标**：不在于谁处理 P2P 连接，而在于**画面进自己的 player**——这样才能调节比例。如果系统 Miracast 处理握手、流量进自定义 PlayerActivity，同样满足需求，且更简单稳定（绕开了 P2P GO 竞争、WFD IE 权限等问题）。

这条路计划在独立分支 `experiment/system-p2p-custom-player` 验证。

---

## 安装

### 环境要求

- Android Studio 2024.1+ 或命令行 Gradle
- Android SDK API 21+
- JDK 17+

### 构建

```bash
# Debug APK（推荐用于开发调试）
./gradlew app:assembleDebug

# Release APK
./gradlew app:assembleRelease
```

APK 输出：
```
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
app/build/outputs/apk/release/app-armeabi-v7a-release.apk
```

### 安装到电视

```bash
# 先连上电视（Wi-Fi ADB）
adb connect 192.168.1.2:5555

# 安装
adb install app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

### 权限

应用只需要 `WRITE_SECURE_SETTINGS`（通过 adb 授权，无需 root）：

```bash
adb shell pm grant com.weekd.miracastreceiver android.permission.WRITE_SECURE_SETTINGS
```

---

## 技术栈

| 组件 | 说明 |
|------|------|
| Wi-Fi Direct | `WifiP2pManager`，TV 作为 GO，groupOwnerIntent=15 |
| WFD 协议 | 主动连接 Source 的 TCP 7236，跑 RTSP M1–M7 |
| RTP 接收 | UDP 19000，接收 MPEG-2 TS |
| 状态管理 | `MiracastController` + `MiracastRuntime` StateFlow |
| 后台服务 | `CastReceiverService` 前台服务 |
| 日志 | Timber |

---

## 已知限制

- **Windows/Mac 投屏**：依赖 WFD IE 广播（需要 `setWFDInfo` 的 signature 级权限），无 root/系统签名时 Windows 端看不到本机。手机端（OPPO/Samsung 等）不依赖 WFD IE，正常使用。
- **非目标品牌手机**：`autoConnectAndroid` 只自动连接 OPPO/Vivo/Xiaomi/Samsung，其他品牌需手动连接。
- **P2P 恢复延迟**：握手失败后的自动恢复约需 15~30s（P2P 重建 + OPPO 重新响应扫描）。

---

## 许可证

本项目仅用于学习和研究目的。
