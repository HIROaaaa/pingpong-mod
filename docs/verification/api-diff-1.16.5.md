# 1.16.5 API 差异扫描（M5 工作清单）

> 生成时间：2026-09-21 04:14:39
> 扫描范围：27 个源文件

## 总览

| 差异类别 | 命中处数 | 1.20.1 写法 → 1.16.5 写法 |
| --- | --- | --- |
| 注册表入口 | **5** | `net.minecraft.registry.Registries.ITEM / BLOCK / ENTITY_TYPE / ITEM_GROUP` → `net.minecraft.util.registry.Registry.ITEM / BLOCK / ENTITY_TYPE / ITEM_GROUP` |
| 方块设置构造 | **1** | `AbstractBlock.Settings.create()` → `AbstractBlock.Settings.of(Material.XX)` |
| 玩家眼睛位置 | **7** | `Entity.getEyePos()` → `没有这个方法：用 new Vec3d(getX(), getEyeY(), getZ())` |
| 物品设置 | **3** | `new Item.Settings()` → `new Item.Settings().group(...) —— 1.16.5 用 FabricItemGroupBuilder 而不是 ItemGroupEvents` |
| 创造页签 API | **3** | `FabricItemGroup.builder() / ItemGroupEvents.modifyEntriesEvent` → `FabricItemGroupBuilder.create(id)…build()` |
| 实体类型构造 | **2** | `FabricEntityTypeBuilder.create(SpawnGroup, factory).dimensions(…).build()` → `1.16.5 同样有 FabricEntityTypeBuilder，但 dimensions 的类型与 build 的泛型不同` |
| Mixin 目标签名 | **7** | `Camera.update / HeldItemRenderer.renderItem / Mouse.onMouseScroll …` → `1.16.5 的方法名与参数个数都不同（如 HeldItemRenderer 在 1.16.5 是 renderItem 的旧签名）` |
| 玩家持久数据 | **2** | `Entity.writeCustomDataToNbt(NbtCompound)` → `1.16.5 的 NBT 读写接口名与参数不同（且 NbtCompound 在 1.16.5 叫 CompoundTag）` |
| 网络注册 | **11** | `ServerPlayNetworking.registerGlobalReceiver(id, (server,player,handler,buf,sender)->…)` → `1.16.5 的回调参数个数/顺序不同，且 ClientPlayNetworking 多一个 boolean 参数` |
| 按键注册 | **3** | `KeyBindingHelper.registerKeyBinding(new KeyBinding(...))` → `1.16.5 同样有 KeyBindingHelper，但 KeyBinding 构造签名不同` |

**合计 44 处**需要版本分支。

## 明细

### 注册表入口（5 处）

- 1.20.1：`net.minecraft.registry.Registries.ITEM / BLOCK / ENTITY_TYPE / ITEM_GROUP`
- 1.16.5：`net.minecraft.util.registry.Registry.ITEM / BLOCK / ENTITY_TYPE / ITEM_GROUP`
- 备注：1.16.5 没有 Registries 这个类；改由 util/Registrar 一处适配

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/entity/ModEntities.java:20` | `Registries.ENTITY_TYPE,` |
| `src/main/java/com/whale/pingpong/util/Registrar.java:38` | `return Registry.register(Registries.ITEM, PingPongMod.id(path), value);` |
| `src/main/java/com/whale/pingpong/util/Registrar.java:43` | `return Registry.register(Registries.BLOCK, PingPongMod.id(path), value);` |
| `src/main/java/com/whale/pingpong/util/Registrar.java:49` | `return Registry.register(Registries.ENTITY_TYPE, PingPongMod.id(path), value);` |
| `src/main/java/com/whale/pingpong/util/Registrar.java:54` | `return Registry.register(Registries.ITEM_GROUP, PingPongMod.id(path), value);` |

### 方块设置构造（1 处）

- 1.20.1：`AbstractBlock.Settings.create()`
- 1.16.5：`AbstractBlock.Settings.of(Material.XX)`
- 备注：1.16.5 必须给 Material（木头/金属…），且要额外 import net.minecraft.block.Material

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/block/ModBlocks.java:15` | `AbstractBlock.Settings.create()` |

### 玩家眼睛位置（7 处）

- 1.20.1：`Entity.getEyePos()`
- 1.16.5：`没有这个方法：用 new Vec3d(getX(), getEyeY(), getZ())`
- 备注：1.16.5 只有 getEyeY()；调用点较多，建议在适配层包一个 eyePos(entity)

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/entity/PingPongBallEntity.java:250` | `double spawnY = thrower.getEyePos().y + 0.30;` |
| `src/main/java/com/whale/pingpong/entity/PingPongBallEntity.java:258` | `pos = thrower.getEyePos().add(flat.multiply(0.55));` |
| `src/main/java/com/whale/pingpong/item/PingPongPaddleItem.java:112` | `return TableGeometry.paddlePoint(player.getEyePos(), look, outward, hand, windUp, inTable);` |
| `src/main/java/com/whale/pingpong/mixin/CameraMixin.java:70` | `Vec3d eye = focusedEntity.getEyePos();` |
| `src/main/java/com/whale/pingpong/mixin/HeldItemRendererMixin.java:81` | `double eyeY = player.getEyePos().y;` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:339` | `Vec3d eyePos = player.getEyePos();` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:389` | `Vec3d eye = player.getEyePos();` |

### 物品设置（3 处）

- 1.20.1：`new Item.Settings()`
- 1.16.5：`new Item.Settings().group(...) —— 1.16.5 用 FabricItemGroupBuilder 而不是 ItemGroupEvents`
- 备注：物品栏归属的写法完全不同（ItemGroupEvents 是 1.19.3+ 的 API）

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/block/ModBlocks.java:20` | `public static final Item PINGPONG_TABLE_ITEM = new BlockItem(PINGPONG_TABLE, new Item.Settings());` |
| `src/main/java/com/whale/pingpong/item/ModItems.java:10` | `public static final Item PINGPONG_PADDLE = new PingPongPaddleItem(new Item.Settings().maxCount(1));` |
| `src/main/java/com/whale/pingpong/item/ModItems.java:13` | `public static final Item PINGPONG_BALL = new PingPongBallItem(new Item.Settings().maxCount(16));` |

### 创造页签 API（3 处）

- 1.20.1：`FabricItemGroup.builder() / ItemGroupEvents.modifyEntriesEvent`
- 1.16.5：`FabricItemGroupBuilder.create(id)…build()`
- 备注：1.16.5 的实现方式完全不同，需要单独一份 ModItemGroups

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/item/ModItemGroups.java:5` | `import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;` |
| `src/main/java/com/whale/pingpong/item/ModItemGroups.java:26` | `FabricItemGroup.builder()` |
| `src/main/java/com/whale/pingpong/util/Registrar.java:52` | `/** 注册一个物品栏（1.16.5 走 FabricItemGroupBuilder，1.20.1 走 FabricItemGroup.builder）。 */` |

### 实体类型构造（2 处）

- 1.20.1：`FabricEntityTypeBuilder.create(SpawnGroup, factory).dimensions(…).build()`
- 1.16.5：`1.16.5 同样有 FabricEntityTypeBuilder，但 dimensions 的类型与 build 的泛型不同`
- 备注：需按 1.16.5 的签名逐个核对（用 javap 从 1.16.5 的 remapped jar 取签名）

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/entity/ModEntities.java:4` | `import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;` |
| `src/main/java/com/whale/pingpong/entity/ModEntities.java:22` | `FabricEntityTypeBuilder.<PingPongBallEntity>create(SpawnGroup.MISC, PingPongBallEntity::new)` |

### Mixin 目标签名（7 处）

- 1.20.1：`Camera.update / HeldItemRenderer.renderItem / Mouse.onMouseScroll …`
- 1.16.5：`1.16.5 的方法名与参数个数都不同（如 HeldItemRenderer 在 1.16.5 是 renderItem 的旧签名）`
- 备注：每个 Mixin 都要按 1.16.5 的映射重写一份 —— 这是最费工的一块

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/mixin/CameraMixin.java:51` | `@Inject(method = "update", at = @At("RETURN"))` |
| `src/main/java/com/whale/pingpong/mixin/HeldItemRendererMixin.java:48` | `@Inject(method = "renderFirstPersonItem", at = @At("HEAD"))` |
| `src/main/java/com/whale/pingpong/mixin/HeldItemRendererMixin.java:85` | `@Inject(method = "renderFirstPersonItem", at = @At("RETURN"))` |
| `src/main/java/com/whale/pingpong/mixin/HeldItemRendererMixin.java:101` | `@Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"` |
| `src/main/java/com/whale/pingpong/mixin/HeldItemRendererMixin.java:147` | `@Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"` |
| `src/main/java/com/whale/pingpong/mixin/MinecraftClientMixin.java:22` | `@Inject(method = "doAttack", at = @At("HEAD"))` |
| `src/main/java/com/whale/pingpong/mixin/MouseMixin.java:29` | `@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)` |

### 玩家持久数据（2 处）

- 1.20.1：`Entity.writeCustomDataToNbt(NbtCompound)`
- 1.16.5：`1.16.5 的 NBT 读写接口名与参数不同（且 NbtCompound 在 1.16.5 叫 CompoundTag）`
- 备注：注意 1.16.5 用的还是 Yarn 的 NbtCompound；差异主要在调用点

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/entity/PingPongBallEntity.java:342` | `protected void writeCustomDataToNbt(NbtCompound nbt) {` |
| `src/main/java/com/whale/pingpong/entity/PingPongBallEntity.java:353` | `protected void readCustomDataFromNbt(NbtCompound nbt) {` |

### 网络注册（11 处）

- 1.20.1：`ServerPlayNetworking.registerGlobalReceiver(id, (server,player,handler,buf,sender)->…)`
- 1.16.5：`1.16.5 的回调参数个数/顺序不同，且 ClientPlayNetworking 多一个 boolean 参数`
- 备注：网络层要按版本各写一份适配（业务回调保持不变）

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:14` | `import net.fabricmc.fabric.api.networking.v1.PlayerLookup;` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:74` | `ServerPlayNetworking.registerGlobalReceiver(ACTION_CHANNEL, (server, player, handler, buf, responseSender) -> ` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:87` | `ClientPlayNetworking.registerGlobalReceiver(POSE_CHANNEL, (client, handler, buf, responseSender) -> {` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:99` | `ClientPlayNetworking.registerGlobalReceiver(MOTION_CHANNEL, (client, handler, buf, responseSender) -> {` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:198` | `ServerPlayNetworking.send(receiver, POSE_CHANNEL, buf);` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:220` | `java.util.Set<ServerPlayerEntity> receivers = new java.util.HashSet<>(PlayerLookup.tracking(ball));` |
| `src/main/java/com/whale/pingpong/net/ModNetworking.java:230` | `ServerPlayNetworking.send(player, MOTION_CHANNEL, buf);` |
| `src/main/java/com/whale/pingpong/PingPongMod.java:13` | `import net.fabricmc.fabric.api.networking.v1.PlayerLookup;` |
| `src/main/java/com/whale/pingpong/PingPongMod.java:60` | `for (ServerPlayerEntity other : PlayerLookup.tracking(joined)) {` |
| `src/main/java/com/whale/pingpong/server/PaddlePoseTracker.java:5` | `import net.fabricmc.fabric.api.networking.v1.PlayerLookup;` |
| `src/main/java/com/whale/pingpong/server/PaddlePoseTracker.java:68` | `for (ServerPlayerEntity receiver : PlayerLookup.tracking(player)) {` |

### 按键注册（3 处）

- 1.20.1：`KeyBindingHelper.registerKeyBinding(new KeyBinding(...))`
- 1.16.5：`1.16.5 同样有 KeyBindingHelper，但 KeyBinding 构造签名不同`
- 备注：参数顺序要核对

| 位置 | 代码 |
| --- | --- |
| `src/main/java/com/whale/pingpong/client/PingPongClient.java:13` | `import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;` |
| `src/main/java/com/whale/pingpong/client/PingPongClient.java:72` | `keySwitchHand = KeyBindingHelper.registerKeyBinding(new KeyBinding(` |
| `src/main/java/com/whale/pingpong/client/PingPongClient.java:74` | `keyBallCam = KeyBindingHelper.registerKeyBinding(new KeyBinding(` |
