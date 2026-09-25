# 愉乐音乐播放器 × EasyTier 集成方案

> 目标：让手机与家庭 Subsonic 服务器通过 EasyTier 去中心化组网处于同一虚拟网，
> 音乐 App 以固定虚拟 IP 访问远程曲库，免去 DDNS / 公网端口转发 / 内网穿透配置。
> 本文为**可行性方案（尚未实施）**，基于 `android` 分支（基线 `5d1b4c8`，2026-09-25）
> 与 EasyTier 官方文档/源码调研整理。

> **实施状态（2026-09-25 更新）**：M1（原生引擎交叉编译，`tools/build_easytier.ps1`）
> 与 M2 一期集成（`core:easytier` + 远程配置页卡片 + SubsonicService 探测集成 +
> 凭据加密/备份排除）**已落地**；M3（FFI/JNI 进程内嵌、PatchConfig 动态配置）为后续迭代。
> 与 §4.2 的差异：启动配置采用**命令行参数**（与官方配置项一一对应）而非 TOML 文件，
> 避免字段名兼容性风险。

## 0. 结论（TL;DR）

**推荐「无 TUN 用户态集成」**：把 `easytier-core` 以 `--no-tun` 模式运行在 App 内，
用 **端口转发**（`127.0.0.1:本地端口 → 虚拟网内 Subsonic IP:端口`）打通流量。
不需要 ROOT、不需要 VpnService 授权弹窗、不影响手机全局网络，
**现有 OkHttp / Subsonic 链路一行都不用改**（把「内网地址」填成本地转发端口即可）。

实施分两步演进：

1. **一期：子进程托管** —— 交叉编译 `easytier-core` 可执行文件随包发布，App 负责
   启停、写配置文件、健康检查。工作量小、风险低，最快验证全链路。
2. **二期：FFI/JNI 进程内嵌** —— 用官方 `easytier-ffi` 的 JNI JSON-RPC 桥把组网引擎
   链进 App 进程，去掉子进程管理，体积与稳定性更优，支持运行期动态改配置。

## 1. EasyTier 关键事实（调研结果）

| 事实 | 依据 |
| --- | --- |
| Apache License 2.0，与本项目（Apache 2.0）兼容，保留版权与 NOTICE 即可 | [EasyTier 许可证](https://easytier.cn/guide/license.html) |
| Rust 核心（`easytier` crate + `easytier-core` 可执行文件）；官方 Android 客户端为 Tauri 架构，`tauri-plugin-vpnservice` 内嵌核心 + VpnService | [tauri-plugin-vpnservice](https://github.com/EasyTier/EasyTier/tree/main/tauri-plugin-vpnservice)、[Mobile Application](https://deepwiki.com/EasyTier/EasyTier/5.7-mobile-application) |
| **无 TUN 模式**（`--no-tun`）：免 Root / 免 VpnService；节点可被虚拟 IP 访问；主动访问其他节点用 **SOCKS5**（`--socks5 端口`，当前无鉴权）或**端口转发** | [无 TUN 模式](https://easytier.cn/guide/network/no-root.html)、[SOCKS5](https://easytier.cn/guide/network/socks5.html) |
| **端口转发** `--port-forward proto://<bind>/<dst>`（tcp/udp）：把宿主端口流量转发到虚拟网内目标，**文档明确其可"在无 TUN 模式或受限环境下替代 TUN 接入虚拟网"**；配置文件字段 `[[port_forwards]]`；dst 必须是虚拟网 IPv4 | [端口转发](https://easytier.cn/guide/network/port-forward.html) |
| **管理 RPC**：`ConfigRpcService/PatchConfig` 运行期动态增删端口转发/配置，**官方定位就是 iOS/FFI 编程式集成场景** | [端口转发 · 管理 RPC](https://easytier.cn/guide/network/port-forward.html) |
| **官方 FFI 层**：`easytier-contrib/easytier-ffi` 提供 C ABI `call_json_rpc(service, method, domain, payload_json, &response_json)`，驱动进程内 `easytier_core::management::call_management_json_rpc`；并有 JNI JSON-RPC 桥（PR #2326）、iOS 侧 `easytier_ios_call_json_rpc` | [easytier-ffi/src/json_rpc.rs](https://github.com/EasyTier/EasyTier/blob/be4b9465/easytier-contrib/easytier-ffi/src/json_rpc.rs)、[FFI and Platform Integrations](https://deepwiki.com/EasyTier/EasyTier/10-ffi-and-platform-integrations) |
| 配置：TOML/YAML 文件（`-c`）+ 命令行覆盖 + `ET_*` 环境变量；`network_name` / `network_secret` / `peers` / 发现地址等；支持 Secure Mode、WireGuard 加密、KCP 代理、`--use-smoltcp` 用户态协议栈 | [配置文件](https://easytier.cn/guide/network/config-file.html)、[完整配置选项](https://easytier.cn/guide/network/configurations.html) |

## 2. 与愉乐现状的契合点

- 远程链路**全部是出站 HTTP(S)**：[SubsonicService](../core/network/src/main/kotlin/com/mtechviral/musicfinderexample/core/network/SubsonicService.kt)
  与 [CacheService](../core/cache/src/main/kotlin/com/mtechviral/musicfinderexample/core/cache/CacheService.kt)
  都走 OkHttp，地址来自 `SubsonicConfig`（内网优先 → 公网回退，`resolveBaseUrl` 探测）。
- 只需要访问**一台 Subsonic 服务器**，不需要全局组网 —— 这正是端口转发/SOCKS5
  的适用场景，而不是 VpnService 全局 TUN。
- 优化建议 01 落地后已有安全基建可复用：[CredentialCipher](../core/database/src/main/kotlin/com/mtechviral/musicfinderexample/core/database/CredentialCipher.kt)
  （Keystore 加密）、[UrlSanitizer](../core/common/src/main/kotlin/com/mtechviral/musicfinderexample/core/common/UrlSanitizer.kt)
  （日志脱敏）、备份排除规则 —— EasyTier 的 `network_secret` 按同一套保护。

## 3. 方案对比

| | A. 官方 EasyTier App 共存 | B. 子进程 + 端口转发（一期） | C. FFI/JNI 进程内嵌（二期） | D. VpnService TUN 全局组网 |
| --- | --- | --- | --- | --- |
| 集成深度 | 零集成（引导用户装官方 App） | App 内托管 easytier-core 进程 | 组网引擎链进 App 进程 | 同 B/C 但走系统 VPN |
| 权限 | 无 | 无（仅 INTERNET） | 无（仅 INTERNET） | VpnService 授权弹窗 + 前台服务 |
| 流量范围 | 全局（官方 App 管） | 仅 Subsonic（本地端口） | 仅 Subsonic | 全局/可限定网段 |
| 包体增量 | 0 | 每 ABI 约 5~15 MB | 每 ABI 约 3~10 MB | 同 B |
| 工作量 | 0.5 天（文档引导） | 1~2 周 | 1~2 周 | 2~3 周 |
| 主要风险 | 用户体验割裂、两 App 协作 | 子进程被杀、双进程生命周期 | Rust 交叉编译/JNI 调试成本 | 与其它 VPN 互斥、政策披露、耗电 |
| 结论 | 应急兜底 | **推荐一期** | **推荐二期目标形态** | 不推荐（超出需求） |

> 也可 A 与 B 并存：设置页提供「使用官方 EasyTier App」与「App 内置组网」两种模式，
> 前者零成本覆盖低频用户，后者给深度用户一体化体验。

## 4. 推荐方案技术设计（B → C 演进）

### 4.1 流量路径（端口转发模式）

```
愉乐 OkHttp ──► 127.0.0.1:18080（本地监听）
                    │  easytier-core --no-tun --port-forward tcp://127.0.0.1:18080/10.144.144.2:4533
                    ▼
              EasyTier 虚拟网（加密 P2P / 中继）
                    ▼
              10.144.144.2:4533  家庭 Subsonic 服务器（同样运行 easytier）
```

- 配置页「内网地址」直接填 `http://127.0.0.1:18080`（或由集成层自动注入），
  现有的「内网优先、公网回退」探测逻辑**原样复用**；
- 播放/下载的流地址由 `SubsonicService.getStreamUrl` 临时生成（建议 01 后不落库），
  自然走转发端口，无需感知虚拟网；
- 备选：SOCKS5 模式（`--socks5 12333`）—— OkHttp 配 `Proxy(SOCKS, 127.0.0.1:12333)`
  可让 Subsonic 客户端单独走代理，适合目标端口不固定/多服务场景；
  但 SOCKS5 当前**无鉴权**，必须只绑 `127.0.0.1`，故默认用端口转发。

### 4.2 模块划分

| 新增 | 职责 |
| --- | --- |
| `core:easytier` | EasyTier 配置模型（network_name/secret/peer/转发规则）、实例生命周期（启动/停止/健康检查/自动重连）、子进程或 FFI 封装、状态 StateFlow |
| `feature:subsonic` 扩展 | 远程配置页新增「EasyTier 组网」卡片：开关、网络名/密码、对端地址、虚拟 IP、连接状态指示、测试连接 |
| `core:database` 扩展 | `easytier_config` 表（或并入 `subsonic_config`）：mesh 凭据用 CredentialCipher 加密，备份排除规则同步覆盖 |

配置生成：把用户的设置序列化为 TOML 写入 `filesDir/easytier/config.toml`，
核心字段：`network_name` / `network_secret` / `peers`（如 `udp://公网中继:11010` 或
对端虚拟 IP 直连的公网地址）/ `[[port_forwards]]`（自动生成：本地端口 → 服务器虚拟 IP）。

### 4.3 生命周期与保活

- **按需启动**：远程扫描、远程播放、缓存下载、歌单同步开始时确保实例存活
  （与 `SubsonicService.resolveBaseUrl()` 的探测时机对齐）；
- **播放期保活**：后台播放依赖 [PlaybackService](../core/player/src/main/kotlin/com/mtechviral/musicfinderexample/core/player/PlaybackService.kt)
  的媒体前台服务；组网实例跟随进程存活即可，进程被杀时播放也停止；
- **缓存下载**（可离线收益）：任务长时运行时用 WorkManager + 前台服务类型
  `dataSync` 保活；下载失败进 `.tmp` 清理（建议 05 已具备）；
- **网络切换/Doze**：EasyTier 自带重连；App 侧监听 `ConnectivityManager` 变化触发
  重探测，连不上自动回退公网地址并在远程配置页提示。

### 4.4 构建与产物

- 一期：`cargo-ndk` 交叉编译 `easytier-core` 静态可执行文件
  （`aarch64-linux-android` 优先，`armeabi-v7a`/`x86_64` 按需），
  放入 `jniLibs` 以 `libeasytier.so` 命名，安装后从 `nativeLibraryDir` exec
  （Android 10+ 禁止从可写目录执行，`nativeLibraryDir` 是允许的路径）；
- 二期：构建 `easytier-ffi` 的 cdylib，Kotlin 侧经 JNI 调
  `call_json_rpc(service, method, domain, payload)` 完成
  实例创建 / 启停 / `PatchConfig` 动态改转发规则，免子进程；
- CI：锁定 EasyTier 版本号，构建产物进仓库 LFS 或发布流水线，附 LICENSE/NOTICE。

### 4.5 安全

- mesh 凭据（`network_secret`）与 Subsonic 密码同等对待：Keystore 加密存储、
  不进备份（`data_extraction_rules` 已排除 `music_player.db`，新增的配置同规则）、
  日志经 UrlSanitizer 脱敏（协议参数不含认证串，但避免把 secret 打进日志）；
- 端口转发只绑 `127.0.0.1`，虚拟网外不可达；
- 传输安全：EasyTier 自身加密（Secure Mode / WireGuard 模式按需开启），
  Subsonic 地址为 `http://127.0.0.1` 仅本机回环，不经过外部网络明文。

## 5. 实施步骤与工作量

| 里程碑 | 内容 | 工作量 |
| --- | --- | --- |
| M1 PoC | 手工交叉编译 easytier-core，真机跑 `--no-tun --port-forward`；愉乐「内网地址」填 `127.0.0.1:18080` 验证：测试连接 / 扫描远程曲库 / 播放 / 缓存下载 / 歌单同步 | 0.5~1 周 |
| M2 一期集成 | `core:easytier`（子进程托管 + 配置生成 + 健康检查 + 自动回退）、远程配置页 UI、mesh 凭据加密存储、生命周期接入 | 1~2 周 |
| M3 二期优化 | FFI/JNI 进程内嵌、`PatchConfig` 动态配置、连接状态展示/统计、SOCKS5 可选模式 | 1~2 周 |
| M4 测试发布 | 真机矩阵（蜂窝/WiFi 切换、Doze、服务器休眠、多 App 并发）、弱网播放与缓存回归、包体与耗电评估、版本锁定与 NOTICE | 1 周 |

## 6. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 后台进程被杀导致组网断开 | 播放期由媒体前台服务托底；其余时段按需拉起 + 失败回退公网地址 |
| Android 10+ 禁止执行可写目录的二进制 | 从 `nativeLibraryDir`（jniLibs）exec，或直接走 FFI 内嵌 |
| 包体增量（每 ABI 5~15 MB） | 优先只发 arm64-v8a；二期 FFI 裁剪特性 |
| 弱网/对称 NAT 下 P2P 失败 | EasyTier 支持公共中继与 KCP；配置页允许填共享节点/中继地址 |
| 版本升级 RPC 不兼容 | 锁定 EasyTier 版本；PatchConfig/JSON-RPC 做契约测试 |
| Google Play 政策 | 无 TUN 集成不涉及 VPN 声明；若最终走方案 D 需按政策披露 VPN 行为 |
| 许可证 | Apache 2.0 兼容；随包附 EasyTier 版权与 NOTICE，README 注明 |

## 7. 验收标准

1. 填写 EasyTier 网络名/密码/对端并启用后，远程配置页「测试连接」通过；
   扫描远程曲库、播放（含流式未缓存）、缓存下载、歌单同步/推送全链路可用。
2. 断网恢复、WiFi↔蜂窝切换、服务器重启后 60s 内自动恢复连接；
   虚拟网不可达时自动回退公网地址，并在配置页显示明确状态。
3. 全程无 ROOT、无 VpnService 授权弹窗；mesh 凭据不出现在数据库明文、备份与日志中。
4. 关闭 EasyTier 或未配置时，现有公网/内网直连行为完全不受影响（回归通过）。
