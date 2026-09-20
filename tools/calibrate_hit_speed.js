#!/usr/bin/env node
/**
 * M1 标定（第二版）：自动反解「力度 → 起跳仰角」曲线。
 *
 * 上一版手调的问题：轻力度过网余量 0.20、满力度 -0.04（下网），曲线在两端都不对。
 * 这一版改成数值反解：对每档力度，先固定拍面贡献，再解出「过网余量恰好 = 目标值」的基准仰角，
 * 然后看这条曲线能不能用一个简单线性式表达（能就直接写进 Java）。
 *
 * 运行：node tools/calibrate_hit_speed.js
 */
'use strict';

const G = 0.030;
const DRAG_LINEAR = 0.010;
const DRAG_QUADRATIC = 0.022;
const MAGNUS = 0.005;
const SPIN_DECAY = 0.988;
const MAX_SPIN = 5.5;

const TABLE = 0.76;
const NET_TOP = TABLE + 0.15;
const NET_X = 1.97;
const FAR_EDGE = 3.34;
const CONTACT_H = 0.99;
const TARGET_NET_MARGIN = 0.12; // 目标过网余量（格）

/** 一次飞行 */
function flight(v0, elevDeg, spinTop) {
  const rad = (elevDeg * Math.PI) / 180;
  let vx = v0 * Math.cos(rad);
  let vy = v0 * Math.sin(rad);
  let spin = spinTop;
  let x = 0;
  let y = CONTACT_H;
  let apex = CONTACT_H;
  let net = null;
  for (let t = 1; t <= 400; t++) {
    vx += MAGNUS * spin * vy;
    vy += -G - MAGNUS * spin * vx;
    const f = Math.min(1, Math.max(0.75, 1 - (DRAG_LINEAR + DRAG_QUADRATIC * Math.hypot(vx, vy))));
    vx *= f;
    vy *= f;
    x += vx;
    y += vy;
    spin *= SPIN_DECAY;
    apex = Math.max(apex, y);
    if (net === null && x >= NET_X) net = y - NET_TOP;
    if (y <= TABLE) return { land: x, net, apex, ticks: t };
  }
  return { land: Infinity, net, apex, ticks: 400 };
}

/** 解出「过网余量 = 目标」的仰角（在可行区间内二分） */
function solveElevation(v0, spinTop, target = TARGET_NET_MARGIN) {
  let lo = 2;
  let hi = 45;
  let best = null;
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2;
    const r = flight(v0, mid, spinTop);
    if (r.net === null) {
      // 还没到网就落台 → 仰角太低，抬高
      lo = mid;
      continue;
    }
    if (r.net < target) lo = mid;
    else hi = mid;
    best = { e: mid, ...r };
  }
  return best;
}

const BASE = 0.30;
const CHARGE_BONUS = 0.11;
/**
 * 自旋对速度的耦合：**下旋更快**（球被削出去后初速大、还带浮力），**上旋略慢**
 * （上旋是"压着抽"的薄摩擦，纯速度换转速）。
 * 二期写成 `1 + tilt*0.15`（tilt>0 前倾=上旋 → 更快）在物理上是反的，
 * 这也是用户报"上下旋速度一样/旋转没体现"的一部分原因。
 */
const COUPLING = 0.12;
const CLAMP_MIN = 0.28;
const CLAMP_MAX = 0.45;
const PADDLE_ELEVATION_MAX = 12; // 拍面贡献的仰角（度）：前倾压低、后仰抬高

const speedOf = (power, tilt) =>
  Math.min(CLAMP_MAX, Math.max(CLAMP_MIN, (BASE + CHARGE_BONUS * power) * (1 - tilt * COUPLING)));

console.log('=== 反解：每档力度需要多大基准仰角，才能让过网余量 = ' + TARGET_NET_MARGIN + ' 格 ===');
console.log('  力度   平击速度   需要的基准仰角   落点     过网余量   弧顶');
const solved = [];
for (const pw of [0, 0.25, 0.5, 0.75, 1.0]) {
  const v = speedOf(pw, 0);
  const r = solveElevation(v, 0);
  solved.push({ pw, v, e: r.e });
  console.log(
    `  ${pw.toFixed(2)}   ${v.toFixed(3)}     ${r.e.toFixed(2).padStart(6)}°        ${r.land.toFixed(2)}   ` +
    `${r.net.toFixed(2)}      ${r.apex.toFixed(2)}`
  );
}

// 线性拟合 e = A + B·power
const n = solved.length;
const sx = solved.reduce((a, s) => a + s.pw, 0);
const sy = solved.reduce((a, s) => a + s.e, 0);
const sxx = solved.reduce((a, s) => a + s.pw * s.pw, 0);
const sxy = solved.reduce((a, s) => a + s.pw * s.e, 0);
const B = (n * sxy - sx * sy) / (n * sxx - sx * sx);
const A = (sy - B * sx) / n;
console.log(`\n线性拟合：基准仰角 = ${A.toFixed(1)}° ${B >= 0 ? '+' : '-'} ${Math.abs(B).toFixed(1)}° × 力度`);

// 取整齐值并验证
const LAUNCH_SLOW = Math.round(A);
const LAUNCH_FAST = Math.round(A + B);
console.log(`取整齐值：力度 0 → ${LAUNCH_SLOW}°，力度 1 → ${LAUNCH_FAST}°`);

const launchBase = (power) => LAUNCH_SLOW + (LAUNCH_FAST - LAUNCH_SLOW) * power;

console.log('\n=== 验证 A：平击（拍面不加角度）===');
console.log('  力度   速度    仰角    落点     过网余量   弧顶   判定');
let bad = 0;
for (const pw of [0, 0.25, 0.5, 0.75, 1.0]) {
  const v = speedOf(pw, 0);
  const e = launchBase(pw);
  const r = flight(v, e, 0);
  const ok = r.net !== null && r.net > 0.02 && r.land > NET_X && r.land < FAR_EDGE;
  if (!ok) bad++;
  console.log(
    `  ${pw.toFixed(2)}   ${v.toFixed(3)}   ${e.toFixed(1).padStart(5)}°  ${r.land.toFixed(2).padStart(6)}   ` +
    `${(r.net ?? -9).toFixed(2).padStart(7)}   ${r.apex.toFixed(2)}   ${ok ? '✔ 对方台面' : r.land > FAR_EDGE ? '出台' : '下网'}`
  );
}

console.log('\n=== 验证 B：叠上拍面角度与自旋（力度 0.5）===');
console.log('  拍面            速度    仰角    自旋    落点     过网余量   滞空   判定');
for (const [name, tilt] of [
  ['强上旋 (+1)', 1],
  ['轻上旋 (+0.5)', 0.5],
  ['平击 (0)', 0],
  ['轻下旋 (-0.5)', -0.5],
  ['强下旋 (-1)', -1],
]) {
  const v = speedOf(0.5, tilt);
  const e = launchBase(0.5) + tilt * PADDLE_ELEVATION_MAX;
  const spin = tilt * MAX_SPIN * (0.35 + 0.65 * 0.5);
  const r = flight(v, e, spin);
  const ok = r.net !== null && r.net > 0.0 && r.land > NET_X;
  if (!ok) bad++;
  console.log(
    `  ${name.padEnd(14)} ${v.toFixed(3)}   ${e.toFixed(1).padStart(5)}°  ${spin.toFixed(2).padStart(5)}  ` +
    `${r.land.toFixed(2).padStart(6)}   ${(r.net ?? -9).toFixed(2).padStart(7)}   ${String(r.ticks).padStart(3)}   ` +
    `${ok ? '✔ 过网' : '✘ 下网'}${r.land > FAR_EDGE ? '（出台）' : ''}`
  );
}

console.log('\n=== 验证 C：极端组合（最容易打飞的几种）===');
for (const [name, pw, tilt] of [
  ['满力上旋', 1, 1],
  ['满力平击', 1, 0],
  ['满力后仰下旋', 1, -1],
  ['轻打上旋', 0, 1],
]) {
  const v = speedOf(pw, tilt);
  const e = launchBase(pw) + tilt * PADDLE_ELEVATION_MAX;
  const spin = tilt * MAX_SPIN * (0.35 + 0.65 * pw);
  const r = flight(v, e, spin);
  console.log(
    `  ${name.padEnd(12)} 速度 ${v.toFixed(3)}  仰角 ${e.toFixed(1).padStart(5)}°  落点 ${r.land.toFixed(2).padStart(5)}  ` +
    `过网 ${(r.net ?? -9).toFixed(2).padStart(6)}  ${r.land > FAR_EDGE ? '出台' : r.net > 0 ? '在台' : '下网'}`
  );
}

console.log('\n=== 验证 D：拍面全扫（力度 0.5，从极限前倾到极限后仰，看有没有下网/出台）===');
console.log('  拍面 tilt   速度    仰角    自旋     落点     过网余量   判定');
for (const tilt of [-1, -0.75, -0.5, -0.25, 0, 0.25, 0.5, 0.75, 1]) {
  const v = speedOf(0.5, tilt);
  const e = launchBase(0.5) + tilt * PADDLE_ELEVATION_MAX;
  const spin = tilt * MAX_SPIN * (0.35 + 0.65 * 0.5);
  const r = flight(v, e, spin);
  const netOk = r.net !== null && r.net > 0.0;
  const inTable = r.land <= FAR_EDGE;
  if (!netOk) bad++;
  console.log(
    `  ${tilt.toFixed(2).padStart(6)}   ${v.toFixed(3)}   ${e.toFixed(1).padStart(5)}°  ${spin.toFixed(2).padStart(5)}  ` +
    `${r.land.toFixed(2).padStart(6)}   ${(r.net ?? -9).toFixed(2).padStart(7)}   ` +
    `${netOk ? (inTable ? '✔ 过网且在台内' : '✔ 过网（出台）') : '✘ 下网'}`
  );
}

console.log('\n=== 建议写进 Java 的常量 ===');
console.log(`  BASE_HIT_SPEED        = ${BASE}`);
console.log(`  CHARGE_SPEED_BONUS    = ${CHARGE_BONUS}`);
console.log(`  MIN_HIT_SPEED         = ${CLAMP_MIN}`);
console.log(`  MAX_HIT_SPEED         = ${CLAMP_MAX}`);
console.log(`  LAUNCH_BASE_SLOW_DEG  = ${LAUNCH_SLOW}   // 力度 0 的起跳仰角`);
console.log(`  LAUNCH_BASE_FAST_DEG  = ${LAUNCH_FAST}   // 力度 1 的起跳仰角`);
console.log(`  PADDLE_ELEVATION_MAX  = ${PADDLE_ELEVATION_MAX}   // 拍面再贡献多少仰角`);
console.log(`  SPIN_SPEED_COUPLING   = ${COUPLING}`);
console.log(bad === 0 ? '\n全部场景都能过网 ✅' : `\n有 ${bad} 个场景不过网 ❌`);
