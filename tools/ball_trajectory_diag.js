#!/usr/bin/env node
/**
 * 球的多跳轨迹诊断：专查用户报的
 *   「球的二跳直接走直线了，根本体现不出来侧旋」
 *   「球被击中后上升的高度有点高了」
 *
 * 做法：镜像 PingPongBallEntity.tickServer 的顺序（自旋衰减 → 重力+马格努斯 → 阻力 → 移动/碰撞），
 * 把**每一跳**前后的状态打出来，并把自旋向量分解成「上旋分量 / 侧旋分量」——
 * 这样能一眼看出问题是"自旋没了"还是"自旋轴还对不对"。
 *
 * 运行：node tools/ball_trajectory_diag.js [sidespin] [topspin]
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '..');

function javaConst(file, name, scope = {}) {
  const src = fs.readFileSync(path.join(ROOT, file), 'utf8');
  const m = src.match(new RegExp(String.raw`public static final (?:double|int)\s+` + name + String.raw`\s*=\s*([^;]+);`));
  if (!m) throw new Error(`找不到常量 ${name}`);
  const keys = Object.keys(scope);
  // eslint-disable-next-line no-new-func
  return Function(...keys, `"use strict";return (${m[1].trim()});`)(...keys.map((k) => scope[k]));
}

const P = 'src/main/java/com/whale/pingpong/physics/PingPongPhysics.java';
/**
 * 【踩过的坑】javaConst 的 scope 形参名必须与 **Java 里引用的名字一致**
 * （INERTIA 的表达式写的是 `MASS * BALL_RADIUS`，所以 scope 的键必须是 MASS / BALL_RADIUS，
 *  不能是我为了短而起的 M / R —— 第一版就是这么报 "MASS is not defined" 的）。
 */
const JAVA_SCOPE = {
  MASS: javaConst(P, 'MASS'),
  BALL_RADIUS: javaConst(P, 'BALL_RADIUS'),
};
const C = {
  G: javaConst(P, 'GRAVITY'),
  DRAG_LINEAR: javaConst(P, 'DRAG_LINEAR'),
  DRAG_QUADRATIC: javaConst(P, 'DRAG_QUADRATIC'),
  MAGNUS: javaConst(P, 'MAGNUS_COEFFICIENT'),
  SPIN_DECAY: javaConst(P, 'SPIN_DECAY'),
  MAX_SPIN: javaConst(P, 'MAX_SPIN'),
  R: JAVA_SCOPE.BALL_RADIUS,
  M: JAVA_SCOPE.MASS,
  I: javaConst(P, 'INERTIA', JAVA_SCOPE),
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
const NET_TOP = TABLE_TOP + 0.15;

/** 表面参数（镜像 PingPongPhysics.Surface） */
const SURF = {
  TABLE: { e: 0.90, mu: 0.60, retain: 0.96 },
  GROUND: { e: 0.75, mu: 0.65, retain: 0.85 },
};

/** 镜像 PingPongPhysics.bounce */
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
 * 把自旋按「与速度的关系」分解：
 *  - 上旋分量：自旋轴与 (up × 速度) 同向为正（球顶部朝前转 = 上旋）
 *  - 侧旋分量：自旋沿竖直轴的分量（会产生落地侧搓）
 */
function decompose(v, spin) {
  const flat = norm(v3(v.x, 0, v.z));
  const topAxis = norm(cross(v3(0, 1, 0), flat));   // 上旋轴
  const top = dot(spin, topAxis);                    // 沿上旋轴的分量（正=下旋、负=上旋，见下方说明）
  const side = dot(spin, v3(0, 1, 0));               // 竖直分量 = 侧旋（落地侧搓的来源）
  const alongV = dot(spin, norm(v));                 // 沿行进方向的分量（严格说不是有效自旋）
  return { top, side, alongV, total: len(spin) };
}

const SIDESPIN = Number(process.argv[2] || 3.0);
const TOPSPIN = Number(process.argv[3] || 0.0);

console.log('=== 球的完整轨迹（含每一跳的自旋分解）===');
console.log(`初始：侧旋 ${SIDESPIN}（沿竖直）/ 上旋 ${TOPSPIN} rad/tick`);
console.log('');

// 初速度：朝对面 +x，带一点仰角（模拟一次正常击球）
const ELEV = 17;
const rad = (ELEV * Math.PI) / 180;
const SPEED = 0.36;
let v = v3(SPEED * Math.cos(rad), SPEED * Math.sin(rad), 0);
const flat0 = norm(v3(v.x, 0, v.z));
const topAxis0 = norm(cross(v3(0, 1, 0), flat0));
/*
 * 【实测教训】侧旋轴必须用**实机的定义**：sin(θ)·up + cos(θ)·flat（θ=60°，见 SIDE_AXIS_TILT_DEGREES）。
 * 第一版我图省事写成"自旋沿竖直轴"，结果 ω 与 v 平行 → ω×v ≡ 0 →
 * 飞行中根本没有横向力，脚本自己就把"侧旋不侧拐"演示了一遍（那是我建模错，不是 bug）。
 * 实机是 sin/cos 组合：竖直分量负责飞行侧弯、行进分量负责落地侧搓。
 */
const SIDE_AXIS_TILT = (60 * Math.PI) / 180;
const sideAxis = norm(add(mul(v3(0, 1, 0), Math.sin(SIDE_AXIS_TILT)), mul(flat0, Math.cos(SIDE_AXIS_TILT))));
let spin = add(mul(topAxis0, TOPSPIN), mul(sideAxis, SIDESPIN));

let pos = v3(0, TABLE_TOP + 0.23, 0);
let onTable = true;
let bounces = 0;
let apex = pos.y;
let maxLateral = 0;

console.log('  tick   x       y       z(横向)  速度     自旋(上旋/侧旋/沿速度/模长)   事件');
for (let t = 1; t <= 120; t++) {
  // --- 与 tickServer 同序 ---
  spin = mul(spin, C.SPIN_DECAY);
  const acc = add(v3(0, -C.G, 0), mul(cross(spin, v), C.MAGNUS));
  v = add(v, acc);
  const f = Math.min(1, Math.max(0.75, 1 - (C.DRAG_LINEAR + C.DRAG_QUADRATIC * len(v))));
  v = mul(v, f);
  pos = add(pos, v);
  apex = Math.max(apex, pos.y);

  const d = decompose(v, spin);

  // --- 碰撞判定（简化：只看水平面）---
  const floorY = onTable ? TABLE_TOP : 0.0;
  let event = '';
  if (pos.y <= floorY && v.y < 0) {
    const surf = onTable ? SURF.TABLE : SURF.GROUND;
    const before = { v: { ...v }, spin: { ...spin } };
    const r = bounce(v, spin, v3(0, 1, 0), surf);
    v = r.v;
    spin = r.spin;
    pos = v3(pos.x, floorY, pos.z);
    bounces++;
    const dBefore = decompose(before.v, before.spin);
    const dAfter = decompose(v, spin);
    event = `第${bounces}跳(${onTable ? '台面' : '地面'}) 落点x=${pos.x.toFixed(2)} ` +
      `| 跳前 上旋${dBefore.top.toFixed(2)}/侧旋${dBefore.side.toFixed(2)}` +
      ` → 跳后 上旋${dAfter.top.toFixed(2)}/侧旋${dAfter.side.toFixed(2)} vx=${v.x.toFixed(3)}`;
    // 第一跳之后球离开台面范围（简化：x>1.5 视为出台落地）
    if (pos.x > 1.5) onTable = false;
  }

  if (t % 4 === 0 || event) {
    maxLateral = Math.max(maxLateral, Math.abs(pos.z));
    console.log(
      `  ${String(t).padStart(4)}  ${pos.x.toFixed(2).padStart(6)}  ${pos.y.toFixed(2).padStart(6)}  ` +
      `${pos.z.toFixed(3).padStart(7)}  ${len(v).toFixed(3)}   ${d.top.toFixed(2).padStart(6)}/${d.side.toFixed(2).padStart(6)}/` +
      `${d.alongV.toFixed(2).padStart(6)}/${d.total.toFixed(2).padStart(5)}   ${event}`
    );
  }
  if (pos.y < -2) break;
}

console.log('');
console.log(`最大横向偏移（第一跳之前）：见上表 z 列`);
console.log(`全程最大横向偏移 ${maxLateral.toFixed(3)} 格`);

console.log('');
console.log('=== 判读方法 ===');
console.log('  · 「跳后 侧旋」应保持非零，且「跳后 vx」的符号要能体现旋转方向；');
console.log('  · 如果「沿速度」分量很大而「上旋/侧旋」很小 → 自旋轴与速度方向不垂直 = 自旋白转了；');
console.log('  · 第二跳若 vx 与前跳几乎一样、侧向也没变化 → 就是用户报的"二跳走直线"。');
