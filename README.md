# 地震了快跑！

## 功能

服务器通过WOLFX OPEN API实时获取地震预警，如果模组判断玩家所处位置可能受到地震影响，就会向玩家发出警报并在地震波到达时剧烈晃动玩家的屏幕画面10秒，同时惊吓玩家附近的自然生物。

## 工作原理

- 服务器通过WebSocket监听中国地震台网、四川省地震局、福建省地震局和重庆市地震局发出的地震预警。
- 玩家加入时服务器时，服务器向 WOLFX GeoIP 查询玩家公网 IP 的城市与坐标。如果服务器的公网出口不是公网 IP 时，则模组的预警功能不启动。
- 如果模组判断玩家所在地可能受到的地震烈度影响超过III度时，会通过HUD向玩家发送预警信息。HUD会显示玩家城市、震中经纬度、震中地名、震级、地震时间（UTC+08:00）、预估烈度和预计地震波到达倒计时。地震波到达后，画面剧烈摇晃 10 秒。
- 模组判断地震波到玩家位置时，玩家附近 16 格内未驯服、未拴绳、能在地面寻路的动物会短暂乱跑，最多持续 10 秒。每位玩家每次地震只触发一次，最多处理附近 32 只动物；村民、怪物、宠物以及飞行和水中生物不受此效果影响。
- 如果模组判断服务器公网出口IP所在的位置会受到地震影响，则向所有玩家发送一次聊天消息：“地震预警：服务器正处在地震影响范围中！”。服务器位置受影响本身不会触发玩家画面摇晃；只有玩家自己的位置也受影响时才会收到 HUD 预警和摇晃效果。

## 管理员命令

- `/quakerun status`：连接状态、服务器定位城市、最近有效报文、GeoIP 查询成败数。不显示 IP。


## 供其他 Forge 模组使用的 API

QuakeRun 自己负责接收 WOLFX、定位和判断影响；其他模组只需监听事件，就能做自己的提醒、音效或生物反应。公开类位于 `com.cmcplugins.quakerun.api` 包，事件发布到 `MinecraftForge.EVENT_BUS`，监听器在服务器主线程运行。事件不可取消，也不会提供玩家 IP、GeoIP 城市或玩家现实经纬度。

### 接入步骤

1. 开发时将 QuakeRun 的 JAR 加入**编译依赖**，使项目能导入 `com.cmcplugins.quakerun.api` 中的类。
2. 在自己模组的 `mods.toml` 中声明运行依赖。下面把 `examplemod` 换成自己模组的 ID：

   ```toml
   [[dependencies.examplemod]]
   modId="quakerun"
   mandatory=true
   versionRange="[1.0.0,)"
   ordering="NONE"
   side="BOTH"
   ```

3. 监听 **Forge 主事件总线**。

### 三个事件分别用于什么

| 事件 | 何时触发 | 常见用途 |
| --- | --- | --- |
| `QuakeReportEvent` | 接受一份新的预警报文或更高报告序号的修正报 | 保存最新震中、震级和深度；同步到地图或其他数据系统。 |
| `PlayerAffectedEvent` | 某玩家首次收到 HUD 预警 | 为该玩家增加自己的提示或准备倒计时。持续处于范围内时，修正报通常不会重复触发。 |
| `WaveArrivalEvent` | 地震波到达玩家位置 | 启动一次自己的音效、视觉效果或实体行为。它不是对真实地震波的测量。 |

`PlayerAffectedEvent` 和 `WaveArrivalEvent` 都提供 `player()`（服务器玩家）和 `quake()`（当时用于判断的报文）。前者还提供 `predictedIntensity()`（该玩家所在地的预估烈度）及 `estimatedArrival()`（预计到达时间，`Instant`）。预警到得太晚时可能没有 `WaveArrivalEvent`。

### 能从 `quake()` 读取哪些数据

`quake()` 返回不可变的 `QuakeData`，它是**一份来自WOLFX的预警报文**。

| 方法 | 含义 |
| --- | --- |
| `source()`、`eventId()`、`serial()` | 来源类型、来源事件 ID、报告序号。来源类型为 `cenc_eew`、`sc_eew`、`fj_eew` 或 `cq_eew`。同一来源可用 `source() + ":" + eventId()` 识别修正报。 |
| `reportTime()`、`originTime()` | 报告时间、地震时间，均为 `Instant`。需要显示本地时间时自行转换时区。 |
| `place()` | 震中地名；可能为空字符串。 |
| `latitude()`、`longitude()` | **震中**纬度、经度，单位为度。 |
| `magnitude()` | 报告的震级。 |
| `depthKm()` | WOLFX 报告的震源深度，单位公里；没有该字段时为 `null`。QuakeRun 内部估算所用的 10 公里默认值**不会写进这里**。 |
| `maxIntensity()` | WOLFX 报告的最大烈度；未提供时为 `null`，不是玩家所在地的预估烈度。 |



## 语言

界面文字跟随客户端的 Minecraft 语言设置，支持简体中文（`zh_cn`）与英语（`en_us`）。WOLFX 报文中的震中名称沿用数据源原文；GeoIP 城市优先使用所选语言的名称。

## 计算与限制

经纬度间距离使用 Haversine 公式。预估烈度使用 `I = I0 - 4 × log10(d/10 + 1)`，其中 `d` 为震中距（公里），`I0` 暂用预警报文的最大烈度近似；触发门槛为 2.5。福建省地震局的报文不提供最大烈度，使用[中国地震烈度经验模型](https://link.springer.com/article/10.1007/s10950-020-09965-w)从震级估算 `I0`。该模型由 5～8 级地震拟合，低于 5 级属于外推，误差可能明显增大。[距离衰减式研究](https://www.mdpi.com/2072-4292/14/17/4269)

预计较强震动到达时间按 `发震时间 + sqrt(震中距² + 深度²) / 3.5 km/s` 估算；深度缺失取 10 公里。模组不检测实际波形，也不区分 P 波和 S 波。城市级 GeoIP 坐标、地质条件及预警更新均可造成烈度或时间误差。

本模组及其估算仅供参考，不能替代官方地震预警、避险与疏散信息。请以相关官方机构的最新信息为准。[WOLFX API 文档](https://wolfx.jp/zh/docs/open-api/) · [WOLFX 使用条款](https://wolfx.jp/zh/legal/terms/)

## 作者与许可

作者：Breeze0424。本项目采用 MIT 许可证，详见 [LICENSE](LICENSE)。
