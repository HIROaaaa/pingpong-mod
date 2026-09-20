# Ping Pong（Minecraft Fabric 1.20.1 乒乓球 Mod）

球拍 + 乒乓球实体，服务端权威物理，带马格努斯效应（上旋 / 下旋 / 侧旋）。
挥拍**不会**转动玩家视角 —— 只有球拍模型自己在动。

---

## 1. 怎么玩

| 操作 | 效果 |
| --- | --- |
| 手持球拍 + **右键** | 发球（在身前轻轻抛出一颗球） |
| 手持球拍 + **左键** | 挥拍击球（命中球拍前方 3 格内最近的一颗球） |
| **滚轮向上** | 球拍后仰 → **上旋球**（球会下扎，弧线明显） |
| **滚轮向下** | 球拍前倾 → **下旋球**（球会飘，落地后可能被搓回来） |
| **左 Alt + 滚轮** | 球拍左右偏斜 → **侧旋球**（香蕉球，落地后侧拐） |
| **潜行 + 滚轮** | 交还给原版，正常切换快捷栏 |
| **手持球拍 + 潜行右键** | 回收附近的乒乓球，重新开球 |
| 手持乒乓球 + 右键 | 投掷一颗球（多人互发用） |

准星下方有 HUD：显示当前拍面俯仰 / 侧偏的百分比滑块。

合成：
- 球拍 = 3 木板 + 2 木棍（`PPP` / ` S ` / ` S `）
- 乒乓球 = 粘液球 + 白色染料 → 2 颗

---

## 2. 需求对照

| 需求 | 实现位置 |
| --- | --- |
| 1. 球拍物品 + 乒乓球实体 | `item/PingPongPaddleItem.java`、`entity/PingPongBallEntity.java`、`item/ModItems.java`、`entity/ModEntities.java` |
| 2. 左键挥拍，视角不动，只有拍模型挥 | 左键：`mixin/MinecraftClientMixin.java`（只发包，不取消原版）；独立挥拍动画：`mixin/HeldItemRendererMixin.java`（只改手持物矩阵，不碰摄像机） |
| 3. 滚轮控制拍面朝向 | `mixin/MouseMixin.java` + `client/PingPongClientState.java`；服务端在 `PingPongBallEntity#hitByPaddle` 里把角度换成出球方向与自旋 |
| 4. 马格努斯效应，自旋影响飞行与弹跳 | `physics/PingPongPhysics.java`（`magnusAcceleration` / `bounce`） |
| 5. 服务端权威 + 联机同步 | 物理只在 `PingPongBallEntity#tickServer` 跑；`net/ModNetworking.java`（C2S 挥拍包 + S2C 运动包）；自旋走 TrackedData |
| 6. 完整结构与源码 | 本仓库 |

---

## 3. 项目结构

```
pingpong-mod/
├── build.gradle / settings.gradle / gradle.properties
├── gradlew / gradlew.bat               # Gradle 8.7 wrapper
├── tools/gen_textures.js               # 用 Node 生成贴图（无需美术资源）
├── tools/physics_sanity_check.js       # 不启动游戏就能验物理的断言脚本
└── src/main/
    ├── java/com/whale/pingpong/
    │   ├── PingPongMod.java             # 主入口：注册物品/实体/网络
    │   ├── physics/
    │   │   └── PingPongPhysics.java     # 纯数学：马格努斯、空气阻力、带自旋的弹跳
    │   ├── entity/
    │   │   ├── ModEntities.java         # EntityType 注册（每 tick 同步位置）
    │   │   └── PingPongBallEntity.java  # 球实体：服务端物理 tick、击球、NBT
    │   ├── item/
    │   │   ├── ModItems.java
    │   │   ├── PingPongPaddleItem.java  # 右键发球 / 潜行右键清球
    │   │   └── PingPongBallItem.java    # 右键投球
    │   ├── net/
    │   │   └── ModNetworking.java       # C2S 挥拍、S2C 球运动、服务端命中判定
    │   ├── client/
    │   │   ├── PingPongClient.java      # 客户端入口
    │   │   ├── PingPongClientState.java # 拍面角度 / 挥拍状态
    │   │   ├── PingPongBallRenderer.java# 球的公告板渲染
    │   │   └── PingPongHud.java         # 准星下的拍面提示
    │   └── mixin/
    │       ├── MinecraftClientMixin.java # 左键 → 发挥拍包
    │       ├── MouseMixin.java           # 滚轮 → 调拍面角度
    │       └── HeldItemRendererMixin.java# 第一人称独立挥拍动画
    └── resources/
        ├── fabric.mod.json
        ├── pingpong.mixins.json
        ├── assets/pingpong/{lang,models,textures,icon.png}
        └── data/pingpong/recipes/
```

---

## 4. 物理模型

单位：长度 = 格，时间 = tick（1 tick = 1/20 s），自旋 ω = rad/tick。

**飞行**（每服务端 tick）：

```
a = (0, -g, 0) + k * (ω × v)          ← 重力 + 马格努斯
v = drag(v + a)                        ← 线性 + 二次空气阻力
v *= (1 - (0.010 + 0.020*|v|))
```

- 上旋（ω 与 `up × dir` 同向）：`ω × v` 指向**下**，球下扎 → 弧圈球。
- 下旋：`ω × v` 指向**上**，球发飘 → 削球。
- 侧旋（ω 沿竖直轴）：`ω × v` 指向**侧向** → 香蕉球。

**弹跳**（撞到方块时，法线 n）：

```
r  = -R * n                            ← 球心指向接触点
u  = v + ω × r                         ← 接触点速度
Jn = -(1 + e) * (v·n) * m              ← 法向冲量（e = 0.72）
u_t = u 的切向分量
Jt = min(μ|Jn|, |u_t| / (1/m + R²/I))  ← 切向摩擦冲量（μ = 0.65）
v += (Jn*n + Jt) / m
ω += (r × Jt) / I                      ← I = 2/3 m R²（空心球）
```

于是自旋会实实在在改变弹跳方向：
- 下旋球接触点向前滑 → 摩擦力向后 → 球被「搓」回来（实测弹跳后 vx 0.62 → 0.46）；
- 上旋球接触点向后滑 → 摩擦力向前 → 弹起后加速前冲（实测 0.53 → 0.58）；
- 侧旋撞墙 → 产生竖直方向的搓动，弹起后乱窜。

**调参的关键结论**（踩过的坑，别改回去）：
马格努斯强度 `k` 和自旋上限 `ω` 必须一起调。`k` 只决定「力」，
但 `ω·R` 决定接触点滑不滑动 —— 只有 `ω·R` 达到球速量级（本 Mod 球半径 0.14），
弹跳时的摩擦力才会把自旋真正转成前进/后退的冲量。
第一版取 `k=0.014, ωmax=4` 时：下旋的升力(0.048) > 重力(0.030)，球变成滑翔机（实测飞 21 格）。
现取 `k=0.005, ωmax=5.5`：力不变，但 `ω·R = 0.77` 正好在球速量级上，飞行和弹跳都对。

**实测数据**（`node tools/physics_sanity_check.js`，水平出球 0.85 格/tick）：

| 场景 | 零自旋落点 | 有自旋落点 |
| --- | --- | --- |
| 水平 + 上旋 | 6.05 格 | **4.60 格**（下扎） |
| 水平 + 下旋 | 6.05 格 | **9.95 格**（发飘） |
| 仰角 22° + 下旋（削球） | 13.28 格 | **18.29 格** |
| 水平 + 侧旋 | 直线 | 横向偏移 **0.70 格** |

手感调参全在 `PingPongPhysics` 与 `PingPongBallEntity` 顶部常量区，
球速上限 `MAX_HIT_SPEED`、自旋上限 `PingPongPhysics.MAX_SPIN`、
出球角度 `MAX_TILT_DEGREES`、马格努斯强度 `MAGNUS_COEFFICIENT`。
改完跑一次 `node tools/physics_sanity_check.js`（10 条断言）就知道有没有调坏。

---

## 5. 编译与运行

要求：**JDK 17**（1.20.1 必须，JDK 21+ 会报错）。

```bat
:: Windows：本机 JDK 26 是默认 java，必须显式指定 JDK 17
set JAVA_HOME=C:\Program Files\Java\jdk-17.0.3.1
gradlew.bat build          :: 产物 build/libs/pingpong-1.0.0.jar
gradlew.bat runClient      :: 直接起一个带 Mod 的客户端
gradlew.bat runServer      :: 起服务端（多人测试）
```

依赖：Fabric Loader ≥ 0.15.11、Fabric API 0.92.2+1.20.1、Minecraft 1.20.1。

版本组合说明：Loom 固定为 `1.6.12`（配合 Gradle 8.7）。
若把 wrapper 升到 Gradle 8.8+，可换成 `1.7-SNAPSHOT` —— 目前的
`1.7-SNAPSHOT`（2024-09-08 构建）已要求 Gradle ≥ 8.8，直接用 8.7 会配置失败。

### 已验证（日志见 `docs/verification/`）

- `build` 编译通过，产出 `build/libs/pingpong-1.0.0.jar`；
- `runClient` 实机启动到主菜单，日志无 Mixin 报错、无模型/贴图缺失；
- 三个 Mixin 目标 `doAttack()` / `onMouseScroll(JDD)V` / `renderFirstPersonItem(...)`
  均通过 Mixin 注解处理器解析成真实 intermediary 签名（见产物内 `pingpong-refmap.json`）；
- `runServer` 真实服务端实测：
  - `/summon pingpong:pingpong_ball` 成功（实体注册与召唤可用）；
  - 带初速度的球按重力 + 马格努斯飞行，落点 `y = -59.999`（地面 -60 + 代码里的
    1e-3 防抖偏移），证明分步移动 / 碰撞反弹 / 静止判定全部按预期工作；
  - 上旋 `SpinZ` 从 -3.0 衰减到 ~0，说明落地摩擦正确地把自旋转成了前冲；
  - 全程无 `Ticking entity` 崩溃、无异常堆栈；
- 物理断言脚本 10/10 通过。

### 未自动化验证的部分

真人手感（挥拍命中判定、滚轮调角度的实际体感）与双端联机同步需要真人操作，
代码逻辑和 API 均已核对，但没有自动化测试覆盖；联机部分只做了单端验证。

---

## 6. 联机同步说明

- 物理只在服务端算，客户端**不跑**自己的物理，所以不会出现「两个人看到球在不同位置」。
- 球实体：`trackedUpdateRate(1)`，每 tick 同步位置，高速球也不会瞬移。
- 自旋：三个 `TrackedData<Float>` 自动同步，客户端用于渲染旋转与粒子。
- 速度：击球瞬间广播 `ball_motion` 包（`PlayerLookup.tracking` + 出球者本人）。
- 服务端会校验「手里确实拿着球拍」并限制挥拍频率，客户端无法伪造出球。
