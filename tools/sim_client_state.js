#!/usr/bin/env node
/**
 * PingPongClientState 状态机仿真（诊断工具，不参与构建）
 *
 * 【为什么有这个文件】用户报「球拍切回来之后不能再次更改仰俯和侧偏」——
 * 光读 Java 代码看不出问题（滚轮链路：MouseMixin → onScroll → poseDirty → syncPose 是通的），
 * 所以这里把 PingPongClientState 的**逐行逻辑原样搬过来**，在 Node 里跑一遍真实操作序列，
 * 用「数据」回答到底是状态机有 bug，还是问题出在 Minecraft 那侧的 API/渲染。
 *
 * 与 Java 源文件的对应关系（改源码时同步这里，否则仿真会骗人）：
 *   onScroll / startSwing / switchHand / toggleBallCam / startCharge / endCharge
 *   consumePoseDirty / markSynced / isPoseOutOfSync
 *   tick / savePose / onSlotChanged / updateSlot / reset
 *   PlayerHand.readyTilt / readySideTilt / flip
 *
 * 用法：node tools/sim_client_state.js
 */
'use strict';

// ------------------------------------------------------------------
// 常量：抄自 PingPongClientState
// ------------------------------------------------------------------
const SCROLL_STEP = 0.25;
const SWING_TICKS = 8;
const CHARGE_MIN_TICKS = 1;
const CHARGE_FULL_TICKS = 16;

/** MathHelper.clamp 的等价实现 */
const clamp = (v, min, max) => (v < min ? min : v > max ? max : v);

/** PlayerHand 的准备姿势 */
const READY = {
  FOREHAND: { tilt: -0.25, side: 0.15 },
  BACKHAND: { tilt: -0.35, side: -0.30 },
};

/** 克隆一份「干净」的客户端状态，方便每个场景独立跑 */
function newState() {
  return {
    tilt: 0,
    sideTilt: 0,
    hand: 'FOREHAND',
    swingTicks: 0,
    ballCam: false,
    chargeTicks: 0,
    POSES: new Map(),
    poseDirty: false,
    syncedTilt: NaN,
    syncedSide: NaN,
    syncedHand: null,
    previousSlot: -1,
  };
}

// ------------------------------------------------------------------
// PingPongClientState 的移植
// ------------------------------------------------------------------

/** MouseMixin 回调：滚轮调拍形 */
function onScroll(s, vertical, alt) {
  const delta = Math.sign(vertical) * SCROLL_STEP;
  if (alt) {
    s.sideTilt = clamp(s.sideTilt + delta, -1, 1);
  } else {
    s.tilt = clamp(s.tilt + delta, -1, 1);
  }
  s.poseDirty = true;
}

/** C 键：正反手切换 + 拍形回到该手型的准备姿势 */
function switchHand(s) {
  s.hand = s.hand === 'FOREHAND' ? 'BACKHAND' : 'FOREHAND';
  s.tilt = READY[s.hand].tilt;
  s.sideTilt = READY[s.hand].side;
  s.poseDirty = true;
  return s.hand;
}

function startCharge(s) {
  s.chargeTicks = CHARGE_MIN_TICKS;
}

function chargeRatio(s) {
  return clamp(s.chargeTicks / CHARGE_FULL_TICKS, 0, 1);
}

function hitPower(s) {
  return Math.sqrt(chargeRatio(s));
}

function endCharge(s) {
  const power = hitPower(s);
  s.chargeTicks = 0;
  return power;
}

function consumePoseDirty(s) {
  if (!s.poseDirty) return false;
  s.poseDirty = false;
  return true;
}

function markSynced(s, tilt, side, handValue) {
  s.syncedTilt = tilt;
  s.syncedSide = side;
  s.syncedHand = handValue;
}

function isPoseOutOfSync(s) {
  return (
    s.syncedHand !== s.hand ||
    Math.abs(s.syncedTilt - s.tilt) > 1.0e-4 ||
    Math.abs(s.syncedSide - s.sideTilt) > 1.0e-4
  );
}

function savePose(s, slot) {
  if (slot < 0) return;
  s.POSES.set(slot, { tilt: s.tilt, side: s.sideTilt });
}

function onSlotChanged(s, newSlot) {
  if (s.previousSlot >= 0) savePose(s, s.previousSlot);
  s.previousSlot = newSlot;
  const pose = s.POSES.get(newSlot);
  if (pose) {
    s.tilt = pose.tilt;
    s.sideTilt = pose.side;
  } else {
    s.tilt = READY[s.hand].tilt;
    s.sideTilt = READY[s.hand].side;
  }
  s.poseDirty = true;
}

function updateSlot(s, slot) {
  if (slot !== s.previousSlot) onSlotChanged(s, slot);
}

/** 每客户端 tick：state.tick(client) */
function tick(s, mc) {
  if (s.swingTicks > 0) s.swingTicks--;
  if (!mc.hasPlayer) return;

  const holdingPaddle = mc.mainHandIsPaddle;
  updateSlot(s, mc.selectedSlot);

  if (holdingPaddle) {
    if (!s.poseDirty) {
      const pose = s.POSES.get(mc.selectedSlot);
      if (pose) {
        s.tilt = pose.tilt;
        s.sideTilt = pose.side;
      }
    }
    // 【修复】滚轮刚改过的这一 tick 就把值写回槽位存档。
    // 原来只在「手里不是球拍」时保存，于是「滚完立刻切走」的最后一格会丢：
    // 下一 tick 进 else 分支保存的已经是切槽后 onSlotChanged 覆盖过的旧值。
    if (s.poseDirty) {
      savePose(s, mc.selectedSlot);
    }
    if (mc.attackKeyPressed) {
      s.chargeTicks = Math.min(s.chargeTicks + 1, CHARGE_FULL_TICKS);
    }  } else {
    savePose(s, mc.selectedSlot);
    s.chargeTicks = 0;
  }
}

/** PingPongClient.syncPose：把未同步的拍形发给服务端 */
function syncPose(s, net) {
  const dirty = consumePoseDirty(s) || isPoseOutOfSync(s);
  if (!dirty) {
    net.outOfSyncTicks = 0;
    return { sent: false };
  }
  if (net.outOfSyncTicks === 0 || net.outOfSyncTicks >= net.RESYNC_TICKS) {
    net.sent.push({ tilt: s.tilt, sideTilt: s.sideTilt, hand: s.hand });
    net.outOfSyncTicks = 1;
    return { sent: true };
  }
  net.outOfSyncTicks++;
  return { sent: false };
}

/**
 * 服务端 ModNetworking.handleAction 的 ACTION_POSE 分支（含校验与手型切换重置）
 * 以及服务端回包 → 客户端 applyPose → markSynced 的往返。
 */
function serverRoundTrip(s, net, hasPaddleOnServer) {
  const st = net.sent;
  net.sent = [];
  for (const packet of st) {
    if (!hasPaddleOnServer) continue; // 服务端校验：手里不是球拍就丢掉
    const handChanged = packet.hand !== net.server.hand;
    net.server.hand = packet.hand;
    if (handChanged) {
      net.server.tilt = READY[packet.hand].tilt;
      net.server.sideTilt = READY[packet.hand].side;
    } else {
      net.server.tilt = packet.tilt;
      net.server.sideTilt = packet.sideTilt;
    }
    net.server.swingPower = 0;
    // 广播回客户端（自己也会收到）
    markSynced(s, net.server.tilt, net.server.sideTilt, net.server.hand);
  }
}

// ------------------------------------------------------------------
// 场景：模拟真实的「完整客户端 tick」
// ------------------------------------------------------------------
function newNet() {
  return {
    sent: [],
    outOfSyncTicks: 0,
    RESYNC_TICKS: 20,
    server: { tilt: 0, sideTilt: 0, hand: 'FOREHAND', swingPower: 0 },
  };
}

function clientTick(s, mc, net, actions = []) {
  // MinecraftClientMixin 在 tick 最开头：左键按下 → 开始蓄力
  if (actions.includes('attackDown') && mc.attackKeyPressed) startCharge(s);
  // MouseMixin：滚轮。方向约定以 PingPongClientState.SCROLL_STEP 的实际实现为准：
  // delta = signum(vertical) * 0.25，而 GLFW 里「向上滚」vertical 为正，
  // 所以 scrollUp 传入 vertical = -1（效果：tilt 变小 = 后仰），scrollDown 传入 +1（前倾）。
  for (const a of actions) {
    if (!a.startsWith('scroll')) continue;
    const vertical = a.endsWith('Down') ? 1 : -1;
    onScroll(s, vertical, a.includes('alt'));
  }
  // 正反手切换在 tick 之后统一处理（PingPongClient 里 handleKeyBindings 排在 PingPongClientState.tick 之后）
  for (const a of actions) {
    if (a === 'switchHand') switchHand(s);
  }
  tick(s, mc);
  const r = syncPose(s, net);
  serverRoundTrip(s, net, mc.mainHandIsPaddle);
  return r;
}

function runTicks(s, mc, net, n, actions = [], perTickActions = {}) {
  for (let i = 0; i < n; i++) {
    clientTick(s, mc, net, perTickActions[i] || actions);
  }
}

// ------------------------------------------------------------------
// 断言与场景
// ------------------------------------------------------------------
let failures = 0;

function check(label, actual, expected) {
  const ok = Math.abs(actual - expected) < 1.0e-6;
  if (!ok) failures++;
  console.log(`${ok ? '  ok  ' : ' FAIL '} ${label}: 期望 ${expected}, 实际 ${actual}`);
}

function fmt(v) {
  return Number.isFinite(v) ? v.toFixed(3) : String(v);
}

console.log('=== 场景 A：滚轮能改拍形（基线）===');
{
  const s = newState();
  const net = newNet();
  const mc = { hasPlayer: true, mainHandIsPaddle: true, selectedSlot: 0, attackKeyPressed: false };
  clientTick(s, mc, net); // 进世界第一 tick：从准备姿势起步
  const before = s.tilt;
  runTicks(s, mc, net, 1, ['scrollUp']);
  console.log(`  起始 ${fmt(before)} → 滚一格后 ${fmt(s.tilt)}`);
  // 上滚 = 后仰 = tilt 更负（MouseMixin 注释：滚轮上 → 后仰），所以是减 SCROLL_STEP
  check('滚轮上=后仰（tilt-0.25）', s.tilt, clamp(before - SCROLL_STEP, -1, 1));
}

console.log('\n=== 场景 B：切走槽位再切回来，然后滚轮（用户报的 bug）===');
{
  const s = newState();
  const net = newNet();
  const mc = { hasPlayer: true, mainHandIsPaddle: true, selectedSlot: 0, attackKeyPressed: false };

  clientTick(s, mc, net); // 拿到球拍槽位 0 的准备姿势
  runTicks(s, mc, net, 2, ['scrollUp']); // 先上调两格（后仰，tilt -0.25 → -0.75）
  const beforeLeave = s.tilt;
  console.log(`  切走前 tilt=${fmt(beforeLeave)}`);

  // 离开：切到 1 号槽（空手），停 5 tick，再切回 0 号
  mc.mainHandIsPaddle = false;
  mc.selectedSlot = 1;
  runTicks(s, mc, net, 5);
  console.log(`  空手期间 tilt=${fmt(s.tilt)} POSES=${JSON.stringify([...s.POSES])}`);

  mc.selectedSlot = 0;
  mc.mainHandIsPaddle = true;
  runTicks(s, mc, net, 3);
  const afterReturn = s.tilt;
  console.log(`  切回后 tilt=${fmt(afterReturn)}`);

  clientTick(s, mc, net, ['scrollUp']);
  console.log(`  切回后再滚一格 tilt=${fmt(s.tilt)}`);
  check('切回后滚轮仍然生效（应比切回时 -0.25，即更后仰）', s.tilt, clamp(afterReturn - SCROLL_STEP, -1, 1));
  check('切回时恢复了离开前的值', afterReturn, beforeLeave);
}

console.log('\n=== 场景 C：滚到极限后再切走切回（怀疑点：夹紧在 ±1 上）===');
{
  const s = newState();
  const net = newNet();
  const mc = { hasPlayer: true, mainHandIsPaddle: true, selectedSlot: 0, attackKeyPressed: false };
  clientTick(s, mc, net);
  runTicks(s, mc, net, 8, ['scrollUp']); // 上滚 = 后仰，滚满到 -1
  console.log(`  滚满后 tilt=${fmt(s.tilt)}`);
  mc.mainHandIsPaddle = false;
  mc.selectedSlot = 2;
  runTicks(s, mc, net, 5);
  mc.selectedSlot = 0;
  mc.mainHandIsPaddle = true;
  runTicks(s, mc, net, 3);
  console.log(`  切回后 tilt=${fmt(s.tilt)}（−1 是后仰极限）`);
  clientTick(s, mc, net, ['scrollDown']);
  console.log(`  反向滚一格 tilt=${fmt(s.tilt)}`);
  check('极限值反向滚应生效', s.tilt, -1 + SCROLL_STEP);
}

console.log('\n=== 场景 D：服务端回包打断本地值？（markSynced 与本地值不一致时）===');
{
  const s = newState();
  const net = newNet();
  const mc = { hasPlayer: true, mainHandIsPaddle: true, selectedSlot: 0, attackKeyPressed: false };
  clientTick(s, mc, net);
  // 手工制造「服务端认为的值 ≠ 客户端本地值」：直接把服务端改成别的值
  net.server.tilt = 0.5;
  net.server.sideTilt = 0.5;
  runTicks(s, mc, net, 3, ['scrollUp']);
  console.log(`  本地 tilt=${fmt(s.tilt)} 服务端=${fmt(net.server.tilt)} synced=${fmt(s.syncedTilt)}`);
  // 本地值应为 ready(-0.25) 连滚 3 格向上（后仰）= -1.0 且已到下限；
  // 若被服务端旧值 0.5 覆盖，这里会变成 0.5。
  // 注：synced 停在 -0.25 是**正确**的 —— 场景 D 故意制造"服务端收到的是旧值"，
  // 本地值不应该被同步标记拉回去，这正是要验证的事。
  check('本地值不被服务端旧值覆盖', s.tilt, clamp(READY.FOREHAND.tilt - 3 * SCROLL_STEP, -1, 1));
}

console.log('\n=== 场景 E：切正反手后再滚轮 ===');
{
  const s = newState();
  const net = newNet();
  const mc = { hasPlayer: true, mainHandIsPaddle: true, selectedSlot: 0, attackKeyPressed: false };
  clientTick(s, mc, net);
  clientTick(s, mc, net, ['switchHand']);
  console.log(`  切反手后 tilt=${fmt(s.tilt)} side=${fmt(s.sideTilt)}（应为 -0.35 / -0.30）`);
  clientTick(s, mc, net, ['scrollDown']);
  console.log(`  再滚一格 tilt=${fmt(s.tilt)}`);
  // 切手后 tilt = -0.35；下滚 = 前倾 = +0.25 → -0.10
  check('切手后滚轮生效', s.tilt, -0.35 + SCROLL_STEP);
}

console.log('\n=== 场景 F：滚轮与滚轮之间夹着切槽位（模拟玩家快速切物品）===');
{
  const s = newState();
  const net = newNet();
  const mc = { hasPlayer: true, mainHandIsPaddle: true, selectedSlot: 0, attackKeyPressed: false };
  clientTick(s, mc, net);
  let angle = s.tilt;
  for (let round = 0; round < 4; round++) {
    // 每轮滚一格（向上=后仰）后立刻切走再切回
    clientTick(s, mc, net, ['scrollUp']);
    angle = clamp(angle - SCROLL_STEP, -1, 1);
    mc.mainHandIsPaddle = false;
    mc.selectedSlot = 1;
    runTicks(s, mc, net, 2);
    mc.selectedSlot = 0;
    mc.mainHandIsPaddle = true;
    runTicks(s, mc, net, 2);
    console.log(`  第 ${round + 1} 轮：滚后期望 ${fmt(angle)}，切回后实际 ${fmt(s.tilt)}`);
  }
  check('反复切换 + 滚动，角度不被切槽位回滚', s.tilt, angle);
}

console.log(`\n===== 结果：${failures === 0 ? '全部通过（状态机没问题，问题在 MC 侧）' : failures + ' 项失败（状态机有 bug，见上）'} =====`);
process.exit(failures === 0 ? 0 : 1);
