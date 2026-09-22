#!/usr/bin/env node
/**
 * M8 侧旋扫参：在**球台实际尺度**里量「侧拐看得见吗」（现象 C）。
 *
 * 起因：用户三次反馈侧旋看不出来。ball_trajectory_diag.js 证明横向位移**是存在的**
 * （第一跳前 0.079 格、第二跳前 0.558），问题在于球台只有 2.74 格 —— 球飞出 2.5 格就落到
 * 对方台面了，而那时候侧移才 0.08 格（约 5 厘米），肉眼确实分不出来。
 *
 * 所以这个脚本只关心**在台面范围内的那一段**：
 *   - 第一跳（落对方台）之前的侧移 = 玩家肉眼能看到的"飞行侧弯"
 *   - 第一跳→第二跳之间的侧移 = 落地侧搓
 * 并对比两个可调参数：
 *   SIDE_AXIS_TILT_DEGREES（侧旋轴倾角：竖直分量管飞行侧弯、行进分量管落地侧搓）
 *   MAGNUS_COEFFICIENT（马格努斯力整体强度）
 *
 * 用法：node tools/side_spin_sweep.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const P = 'src/main/java/com/whale/pingpong/physics/PingPongPhysics.java';

/** 从 Java 源码里读常量，保证脚本与实机同源（踩过坑：手抄值会和代码漂移） */
function javaConst(file, name) {
  const src = fs.readFileSync(path.join(ROOT, file), 'utf8');
  const m = src.match(new RegExp(String.raw`public static final (?:double|int)\s+` + name + String.raw`\s*=\s*([^;]+);`));
  if (!m) throw new Error(`找不到常量 ${name}`);
  return Number(m[1].trim());
}

const C = {
  G: javaConst(P, 'GRAVITY'),
  DRAG_LINEAR: javaConst(P, 'DRAG_LINEAR'),
  DRAG_QUADRATIC: javaConst(P, 'DRAG_QUADRATIC'),
  SPIN_DECAY: javaConst(P, 'SPIN_DECAY'),
  MAX_SPIN: javaConst(P, 'MAX_SPIN'),
  R: javaConst(P, 'BALL_RADIUS'),
  M: javaConst(P, 'MASS'),
  I: javaConst(P, 'INERTIA'),
};

const v3 = (x = 0, y = 0, z = 0) => ({ x, y, z });
const add = (a, b) => v3(a.x + b.x, a.y + b.y, a.z + b.z);
const sub = (a, b) => v3(a.x - b.x, a.y - b.y, a.z - b.z);
const mul = (a, s) => v3(a.x * s, a.y * s, a.z * s);
const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
const cross = (a, b) => v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
const len = (a) => Math.sqrt(dot(a, a));
const norm = (a) => (len(a) < 1e-12 ? v3() : mul(a, 1 / len(a)));

const TABLE_TOP = 0.76;
const SURF_TABLE = { e: 0.90, mu: 0.60, retain: 0.96 };
const SURF_GROUND = { e: 0.75, mu: 0.65, retain: 0.85 };

function bounce(v, spin, n, s) {
  n = norm(n);
  const vn = dot(v, n);
  if (vn > 0) return { v, spin };
  const Jn = -(1 + s.e) * vn * C.M;
  let nv = add(v, mul(n, Jn / C.M));
  let ns = spin;
  const r = mul(n, -C.R);
  const contact = add(v, cross(spin, r));
  const tangent = sub(contact, mul(n, dot(contact, n)));
  const slip = len(tangent);
  if (slip > 1e-6) {
    const dir = mul(tangent, -1 / slip);
    const stop = slip / (1 / C.M + (C.R * C.R) / C.I);
    const jt = Math.min(s.mu * Math.abs(Jn), stop);
    const J = mul(dir, jt);
    nv = add(nv, mul(J, 1 / C.M));
    ns = add(ns, mul(cross(r, J), 1 / C.I));
  }
  return { v: nv, spin: mul(ns, s.retain) };
}

/**
 * 飞一次：返回第一跳（落台）时的横向位移 z、第一跳落点 x、
 * 以及第一跳之后到下一次落地（含地面）的侧移增量。
 */
function flight(sideTiltDeg, magnus, sideSpin, topspin = 0) {
  const ELEV = 17;
  const SPEED = 0.34;   // M7.6 后的典型满力速度
  const rad = (ELEV * Math.PI) / 180;
  let v = v3(SPEED * Math.cos(rad), SPEED * Math.sin(rad), 0);
  const flat0 = norm(v3(v.x, 0, v.z));
  const tilt = (sideTiltDeg * Math.PI) / 180;
  const sideAxis = norm(add(mul(v3(0, 1, 0), Math.sin(tilt)), mul(flat0, Math.cos(tilt))));
  const topAxis0 = norm(cross(v3(0, 1, 0), flat0));
  let spin = add(mul(topAxis0, topspin), mul(sideAxis, sideSpin));
  let pos = v3(0, TABLE_TOP + 0.23, 0);

  let firstZ = null;
  let firstX = null;
  let secondZ = null;
  let bounces = 0;

  for (let t = 1; t <= 400; t++) {
    const accel = add(v3(0, -C.G, 0), mul(cross(spin, v), magnus));
    v = mul(add(v, accel), Math.min(1, Math.max(0.75, 1 - (C.DRAG_LINEAR + C.DRAG_QUADRATIC * len(v)))));
    spin = mul(spin, C.SPIN_DECAY);
    pos = add(pos, v);

    if (bounces === 0 && pos.x > 1.75 && pos.y <= TABLE_TOP) {
      // 第一跳：落在台面上（x 需过了网附近）
      const r = bounce(v, spin, v3(0, 1, 0), SURF_TABLE);
      v = r.v; spin = r.spin;
      pos = v3(pos.x, TABLE_TOP, pos.z);
      firstZ = Math.abs(pos.z);
      firstX = pos.x;
      bounces = 1;
      continue;
    }
    if (bounces === 0 && pos.y <= TABLE_TOP) {
      // 还没过网就落地 → 直接算下网，不再统计
      if (pos.x < 1.75) return { net: true, firstZ: null, landX: pos.x, secondZ: null };
    }
    if (bounces === 1 && pos.y <= (pos.x <= 1.37 + 1.97 && pos.x <= 3.34 ? TABLE_TOP : 0)) {
      const onTable = pos.x <= 3.34;
      const r = bounce(v, spin, v3(0, 1, 0), onTable ? SURF_TABLE : SURF_GROUND);
      v = r.v; spin = r.spin;
      secondZ = Math.abs(pos.z);
      return { net: false, firstZ, firstX, secondZ, secondX: pos.x, onTable };
    }
    if (pos.y < -20) break;
  }
  return { net: false, firstZ, firstX, secondZ, secondX: null, timedOut: true };
}

console.log('球台尺度：全场 2.74 格、击球点到网 1.97、到对面台缘 3.34');
console.log('（下面 first = 第一跳前侧移 = 玩家肉眼看到的飞行侧弯；Δ = 落地侧搓）\n');

console.log('=== A. 侧旋轴倾角扫描（马格努斯保持现值 ' + javaConst(P, 'MAGNUS_COEFFICIENT') + '）===');
console.log('  倾角   第一跳前侧移   第一跳落点x   第一跳后侧移   落地侧搓Δ');
const magnusNow = javaConst(P, 'MAGNUS_COEFFICIENT');
for (const deg of [30, 45, 52, 60, 68, 75, 82]) {
  const r = flight(deg, magnusNow, 3.0);
  if (r.net) { console.log(`  ${String(deg).padStart(3)}°   下网（x=${r.landX.toFixed(2)}）`); continue; }
  const dz = r.secondZ === null ? null : r.secondZ - r.firstZ;
  console.log(`  ${String(deg).padStart(3)}°   ${r.firstZ.toFixed(3).padStart(8)}       ${r.firstX.toFixed(2).padStart(6)}      ` +
    `${(r.secondZ === null ? '-' : r.secondZ.toFixed(3)).padStart(8)}      ${dz === null ? '-' : dz.toFixed(3).padStart(6)}`);
}

console.log('\n=== B. 马格努斯系数扫描（倾角保持 60°）===');
console.log('  系数     第一跳前侧移   第一跳落点x   第一跳后侧移   落地侧搓Δ   重心倍数');
for (const k of [0.003, 0.004, 0.005, 0.006, 0.008]) {
  const r = flight(60, k, 3.0);
  if (r.net) { console.log(`  ${k.toFixed(3)}  下网（x=${r.landX.toFixed(2)}）`); continue; }
  const dz = r.secondZ === null ? null : r.secondZ - r.firstZ;
  // 自旋 3.0、球速 0.34 时的马格努斯加速度 / 重力
  const ratio = (k * 3.0 * 0.34) / C.G;
  console.log(`  ${k.toFixed(3)}  ${r.firstZ.toFixed(3).padStart(8)}       ${r.firstX.toFixed(2).padStart(6)}      ` +
    `${(r.secondZ === null ? '-' : r.secondZ.toFixed(3)).padStart(8)}      ${dz === null ? '-' : dz.toFixed(3).padStart(6)}   ${ratio.toFixed(2)}×`);
}

console.log('\n=== C. 组合对比：倾角 × 系数 ===');
console.log('  组合                     第一跳前侧移   落地侧搓Δ   备注');
for (const [deg, k, note] of [
  [60, 0.003, '现状'],
  [75, 0.003, '只加倾角'],
  [60, 0.005, '只加系数'],
  [75, 0.005, '两者都加'],
  [82, 0.005, '倾角更极端'],
]) {
  const r = flight(deg, k, 3.0);
  if (r.net) { console.log(`  倾角 ${deg}° + k=${k}        下网`); continue; }
  const dz = r.secondZ === null ? null : r.secondZ - r.firstZ;
  console.log(`  倾角 ${String(deg).padStart(2)}° + k=${k.toFixed(3)}      ${r.firstZ.toFixed(3).padStart(8)}    ` +
    `${dz === null ? '-' : dz.toFixed(3).padStart(6)}     ${note}`);
}

console.log('\n=== D. 上旋/下旋的侧移参照（确认上旋扎得下去、下旋飘得住）===');
for (const [label, top] of [['纯上旋', -3.0], ['纯下旋', 3.0], ['纯侧旋', 0]]) {
  const r = flight(60, magnusNow, label === '纯侧旋' ? 3.0 : 0, top);
  console.log(`  ${label}  第一跳落点 x=${r.firstX === null ? '-' : r.firstX.toFixed(2)}  ` +
    `第一跳前侧移 ${r.firstZ === null ? '-' : r.firstZ.toFixed(3)}  ${r.firstX !== null && r.firstX > 3.34 ? '⚠ 出台' : ''}`);
}
