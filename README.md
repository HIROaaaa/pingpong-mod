# Ping Pong（Minecraft Fabric 1.20.1 乒乓球 Mod）

球拍 + 乒乓球实体 + 蓝色乒乓球台，服务端权威物理，带马格努斯效应（上旋 / 下旋 / 侧旋）。
挥拍**不会**转动玩家视角 —— 只有球拍模型自己在动。

---

## 1. 怎么玩

| 操作 | 效果 |
| --- | --- |
| 手持**乒乓球** + 右键 | **把球垂直上抛**（上升约 3.4 格、滞空约 1.5 秒） |
| 手持**球拍** + 左键 | 挥拍击球（命中拍面位置 3 格内最近的一颗球，视角不动） |
| **滚轮向上** | 球拍后仰 → **上旋球**（弧线下扎，速度更快） |
| **滚轮向下** | 球拍前倾 → **下旋球**（球发飘，速度更慢） |
| **左 Alt + 滚轮** | 球拍左右偏斜 → **侧旋球**（飞行侧弯 + 落地侧拐） |
| **潜行 + 滚轮** | 交还给原版，正常切换快捷栏 |
| 手持球拍 + **潜行右键** | 回收场上的乒乓球，重新开球 |

球拍右键**不会**生成球 —— 打法是：先用乒乓球物品把球抛起来，落下时自己调拍形去打，
和现实里打乒乓球一样。

准星下方 HUD 会显示当前拍面俯仰 / 侧偏的百分比；每次滚轮调节还会在 action bar 打出即时数字。

合成：

| 物品 | 配方 |
| --- | --- |
| 乒乓球拍 | `PPP` / ` S ` / ` S `（P=任意木板，S=木棍） |
| 乒乓球 ×2 | 粘液球 + 白色染料（无序） |
| **乒乓球台** | `BBB` / `WWW` / `S S`（B=蓝色羊毛，W=木板，S=木棍） |

球台是**一个方块就是一整张球台**：放下即成型（3 格长 × 2 格宽，台面 0.75 格高、中间带网），
拆掉也一次拆完。长边自动顺着你面朝的方向铺开。

---

## 2. 需求对照

### 一期（初版需求）

| 需求 | 实现位置 |
| --- | --- |
| 1. 球拍物品 + 乒乓球实体 | `item/ModItems.java`、`item/PingPongPaddleItem.java`、`item/PingPongBallItem.java`、`entity/PingPongBallEntity.java` |
| 2. 左键挥拍，视角不动，只有拍模型挥 | `mixin/MinecraftClientMixin.java`（只发包，不取消原版）；`mixin/HeldItemRendererMixin.java`（只改手持物矩阵，不碰摄像机） |
| 3. 滚轮控制拍面朝向 | `mixin/MouseMixin.java` + `client/PingPongClientState.java`；服务端在 `PingPongBallEntity#hitByPaddle` 里把角度换成出球方向与自旋 |
| 4. 马格努斯效应，自旋影响飞行与弹跳 | `physics/PingPongPhysics.java` |
| 5. 服务端权威 + 联机同步 | 物理只在 `tickServer` 跑；`net/ModNetworking.java`（C2S 挥拍 + S2C 运动）；自旋走 TrackedData |

### 二期（用户实测反馈后）

| 用户报的问题 / 要求 | 处理 |
| --- | --- |
| 球拍右键不该把球打出去，球拍只负责击球 | 删掉右键发球，右键留空（潜行右键才回收球） |
| 球要单独上抛、落下来自己调拍形打 | `PingPongBallItem` 改为垂直上抛（`toss()`） |
| 旋转没有任何体现、上下旋速度一样 | 滚轮步长 0.1→0.25（滚 4 格满）、出球速度乘自旋耦合 `×(1+tilt×0.15)` |
| 侧旋在地上不侧拐 | **真数学 bug**：纯竖直侧旋轴 ω×r≡0，改为朝行进方向倾斜 40° |
| 球不会弹跳，只会在平地走 | **真 bug**：原版 `Entity#move` 会清零速度，物理速度改为自持（详见第 4 节） |
| 需要一个专门的乒乓球台，蓝色的，一个方块搞定 | `block/PingPongTableBlock.java` + 生成的蓝色贴图 |
| 球拍要有立体建模、改角度要看得出来 | `models/item/pingpong_paddle.json` 改为真 3D（红/黑胶皮 + 木柄）+ action bar 数字反馈 |

---

## 3. 项目结构

```
pingpong-mod/
├── build.gradle / settings.gradle / gradle.properties
├── gradlew / gradlew.bat               # Gradle 8.7 wrapper
├── tools/gen_textures.js               # 用 Node 生成全部贴图（无需美术资源）
├── tools/physics_sanity_check.js       # 不启动游戏就能验物理的断言脚本
└── src/main/
    ├── java/com/whale/pingpong/
    │   ├── PingPongMod.java             # 主入口：注册物品/方块/实体/网络
    │   ├── physics/
    │   │   └── PingPongPhysics.java     # 纯数学：马格努斯、空气阻力、分表面弹跳
    │   ├── entity/
    │   │   ├── ModEntities.java         # EntityType 注册（每 tick 同步位置）
    │   │   └── PingPongBallEntity.java  # 球实体：服务端物理、自持速度、击球、NBT
    │   ├── block/
    │   │   ├── ModBlocks.java           # 方块注册
    │   │   └── PingPongTableBlock.java  # 球台：模型与碰撞箱跨 3×2 格
    │   ├── item/
    │   │   ├── ModItems.java
    │   │   ├── PingPongPaddleItem.java  # 只击球（+潜行右键回收球）
    │   │   └── PingPongBallItem.java    # 右键垂直上抛
    │   ├── net/
    │   │   └── ModNetworking.java       # C2S 挥拍、S2C 球运动、服务端命中判定
    │   ├── client/
    │   │   ├── PingPongClient.java      # 客户端入口（含球台 cutout 渲染层）
    │   │   ├── PingPongClientState.java # 拍面角度 / 挥拍状态
    │   │   ├── PingPongBallRenderer.java# 球的公告板渲染
    │   │   └── PingPongHud.java         # 准星下的拍面提示
    │   └── mixin/
    │       ├── MinecraftClientMixin.java # 左键 → 发挥拍包
    │       ├── MouseMixin.java           # 滚轮 → 调拍面角度 + action bar 反馈
    │       └── HeldItemRendererMixin.java# 第一人称独立挥拍动画
    └── resources/
        ├── fabric.mod.json / pingpong.mixins.json
        ├── assets/pingpong/
        │   ├── blockstates/pingpong_table.json
        │   ├── models/block/pingpong_table_{x,z}.json   # 两个轴向的 3×2 球台模型
        │   ├── models/item/{pingpong_paddle,pingpong_ball,pingpong_table}.json
        │   ├── textures/{item,entity,block}/*.png       # 全部由脚本生成
        │   └── lang/{zh_cn,en_us}.json
        └── data/pingpong/{recipes,loot_tables}/...
```

---

## 4. 物理模型

单位：长度 = 格，时间 = tick（1 tick = 1/20 s），自旋 ω = rad/tick。

### 飞行（每服务端 tick）

```
a = (0, -g, 0) + k * (ω × v)          ← 重力 + 马格努斯
v = drag(v + a)                        ← 线性 + 二次空气阻力
v *= (1 - (0.010 + 0.016*|v|))
```

- 上旋（ω 与 `up × dir` 同向）：`ω × v` 指向**下**，球下扎 → 弧圈球。
- 下旋：`ω × v` 指向**上**，球发飘 → 削球。
- 侧旋（ω 轴朝行进方向倾斜 40°）：`ω × v` 指向**侧向** → 香蕉球。

### 弹跳（撞到方块时，法线 n）

```
r  = -R * n                            ← 球心指向接触点
u  = v + ω × r                         ← 接触点速度
Jn = -(1 + e) * (v·n) * m              ← 法向冲量
u_t = u 的切向分量
Jt = min(μ|Jn|, |u_t| / (1/m + R²/I))  ← 切向摩擦冲量
v += (Jn*n + Jt) / m
ω += (r × Jt) / I                      ← I = 2/3 m R²（空心球）
```

**e 与 μ 按碰撞面材质给**（`PingPongPhysics.Surface`），这是「球台能弹、草地一般、球网吃球」的开关：

| 表面 | e（恢复系数） | μ（摩擦） |
| --- | --- | --- |
| 球台台面 | **0.90** | 0.60 |
| 球台侧面 | 0.70 | 0.65 |
| 球网 | **0.22** | 0.95 |
| 其他地面 | 0.75 | 0.65 |

### 三个踩过的坑（别改回去）

**① 原版 `Entity#move` 会清零实体速度 → 物理速度必须自己存一份。**
用户报「球不会弹跳，只在平地走」。实测：竖直下落 10 格的球落地后**一次都不弹**。
用 `javap -c` 反查 `net.minecraft.entity.Entity#move` 的字节码，能看到两处 `setVelocity` 调用 ——
它在碰撞时把速度改掉了，于是我读到的是被清零的速度，反弹永远算不出来。
现在 `PingPongBallEntity` 自己维护 `physicsVelocity`，原版速度只用于追踪同步与渲染。

**② 纯竖直的侧旋轴永远不会侧拐。**
水平地面上，纯竖直轴自旋的接触点速度 `ω × r ≡ 0`（数学上恒为零），摩擦力无从产生侧向搓动。
把侧旋轴朝行进方向倾斜 40° 后：飞行横向马格努斯保留 `cos40°`，落地侧向搓动来自 `sin40°`。

**③ 贴地必须快速吃掉自旋。**
不加这一步，球会被自己的自旋在地面反复「搓」着跑（实测侧旋球横移 7 格以上，完全失真）。
`GROUND_SPIN_DECAY = 0.85`（约 15 tick 衰减到 9%）+ `GROUND_ROLL_FRICTION = 0.96`。

另外两条调参结论：
- 马格努斯系数 `k` 与自旋上限 `ωmax` **必须一起调**：`k` 决定「力」，而 `ω·R` 决定接触点滑不滑动。
  第一版 `k=0.014, ωmax=4` 时下旋升力(0.048) > 重力(0.030)，球变滑翔机（实测飞 21 格）。
  现取 `k=0.005, ωmax=5.5`：`ω·R = 0.77` 正好落在球速量级上（真实乒乓球也是如此）。
- 出球必须有最小仰角（`MIN_LAUNCH_ELEVATION_DEGREES = 6°`）：平射球落地时 v_y 太小，
  弹起不足 0.4 格，肉眼就是「贴地滚」。

### 实测数据

tick 级轨迹（服务端日志，`PINGPONG_TRACE=1` 打开）：

| 项 | 数据 |
| --- | --- |
| 落地瞬间 | tick 18 `vy=-0.467` → tick 19 `vy=+0.366`（0.467×0.75 ≈ 0.35 ✓） |
| 第一次弹起 | 顶点 1.84 格（消耗空气阻力后的合理值） |
| 后续弹跳 | -0.295→+0.240、-0.208→+0.176、-0.153→+0.135、-0.076→+0.078 |
| 静止 | tick 87 起稳定在 `y=-59.999`（地面 -60 加 1e-3 防抖偏移） |
| 球台弹跳 | 落差 4.25 格 → 弹起 2.0 格（e=0.90 高于草地） |
| 球网 | 球落在网上停在网顶 `y=0.15`（台面上方），几乎不弹（e=0.22） |

手感调参全在 `PingPongPhysics` 与 `PingPongBallEntity` 顶部常量区。
改完跑 `node tools/physics_sanity_check.js` 先粗筛，再用服务端实测确认。

---

## 5. 编译与运行

要求：**JDK 17**（1.20.1 必须，JDK 21+ 会报错）。

```bat
:: Windows：本机默认 java 是 26，必须显式指定 JDK 17
set JAVA_HOME=C:\Program Files\Java\jdk-17.0.3.1
gradlew.bat build          :: 产物 build/libs/pingpong-1.0.0.jar
gradlew.bat runClient      :: 起一个带 Mod 的客户端
gradlew.bat runServer      :: 起服务端（多人测试）
```

依赖：Fabric Loader ≥ 0.15.11、Fabric API 0.92.2+1.20.1、Minecraft 1.20.1。
版本组合：Loom 固定 `1.6.12` + Gradle 8.7（`1.7-SNAPSHOT` 已要求 Gradle ≥ 8.8）。

### 调试开关

```bat
:: 让球每 tick 把位置/速度打进日志，用来核对弹跳轨迹
set PINGPONG_TRACE=1
gradlew.bat runServer
```

外部用 `/data get` 采样**对不齐 tick**（70 条命令会被服务器在一个 tick 内全部处理完），
想看真实轨迹只能用这个开关。

### 已验证

- `build` 编译通过，产出 `build/libs/pingpong-1.0.0.jar`；
- `runClient` 实机启动到主菜单，日志无 Mixin 报错、无模型/贴图缺失；
- 三个 Mixin 目标 `doAttack()` / `onMouseScroll(JDD)V` / `renderFirstPersonItem(...)`
  均解析成真实 intermediary 签名（见产物内 `pingpong-refmap.json`）；
- `runServer` 真实服务端：球的弹跳轨迹逐 tick 核对（见上表），
  球台台面弹性、球网吃球、地面衰减全部符合恢复系数，全程无 `Ticking entity` 崩溃。

### 未自动化验证的部分

真人手感（挥拍命中是否顺手、滚轮调节的实际体感）与双端联机同步需要真人操作，
代码逻辑与 API 均已核对，但没有自动化测试覆盖；联机仍只做了单端验证。

---

## 6. 联机同步说明

- 物理只在服务端算，客户端**不跑**自己的物理，所以不会出现「两个人看到球在不同位置」。
- 球实体：`trackedUpdateRate(1)`，每 tick 同步位置，高速球也不会瞬移。
- 自旋：三个 `TrackedData<Float>` 自动同步，客户端用于渲染旋转与粒子。
- 速度：击球瞬间广播 `ball_motion` 包（`PlayerLookup.tracking` + 出球者本人）。
- 服务端会校验「手里确实拿着球拍」并限制挥拍频率，客户端无法伪造出球。

---

## 7. 版本与发布

版本号写在 `gradle.properties` 的 `mod_version`，它同时决定：
产物名 `build/libs/pingpong-<version>.jar`、以及 `fabric.mod.json` 里的 `version`。

版本规则 `MAJOR.MINOR.PATCH`：新方块/手感调整 = MINOR，纯修复 = PATCH，存档或协议不兼容 = MAJOR。
每次发版更新 `CHANGELOG.md`。

发版流程：

1. 改 `mod_version` 与 `CHANGELOG.md` → 提交推送；
2. 打标签并推送：`git tag -a v1.1.0 -m "..."` + `git push origin v1.1.0`；
3. `gradlew.bat build`，把 `build/libs/pingpong-<version>.jar` 作为 GitHub Release 附件上传。

已发布版本：

| 版本 | 标签 | 内容 |
| --- | --- | --- |
| 1.0.0 | `v1.0.0` | 一期初版：球拍 + 球实体 + 马格努斯物理 + 服务端权威 |
| 1.1.0 | `v1.1.0` | 二期：修弹跳与侧旋两个真 bug、球拍 3D 模型、蓝色单方块球台 |

