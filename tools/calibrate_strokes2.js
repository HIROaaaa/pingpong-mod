#!/usr/bin/env node
/**
 * M3 标定（第三版，最终形态）：拍面角由**击球类型**定，力度只控挥拍速度。
 *
 * 【为什么前两版都不对】
 * - 第一版：拍面角固定、反解挥拍速度 → DRIVE 全档无解（后仰不够时法向冲量把球砸向地面）。
 * - 第二版：指定目标仰角、反解拍面角 → 仰角被"指定"了，落点却随力度线性冲出台（0.3 力度落 3.19，
 *   1.0 力度落 8.32）。
 * - 第三版（本文件）：**让仰角成为物理输出**。拍面角是"打法"的一部分（拉球前倾、搓削后仰），
 *   力度只缩放挥拍速度；出球仰角由接触模型算出来。于是要标定的只是
 *   「每种击球的拍面角 + 挥拍速度」，标准是「中等力度能过网落台、满力度允许出台但不飞出天际」。
 *
 * 运行：node tools/calibrate_strokes.js
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
const G = {
  BALL_RADIUS: javaConst(P, 'BALL_RADIUS'),
  MASS: javaConst(P, 'MASS'),
  GRAVITY: javaConst(P, 'GRAVITY'),
  DRAG_LINEAR: javaConst(P, 'DRAG_LINEAR'),
  DRAG_QUADRATIC: javaConst(P, 'DRAG_QUADRATIC'),
  MAGNUS_COEFFICIENT: javaConst(P, 'MAGNUS_COEFFICIENT'),
  SPIN_DECAY: javaConst(P, 'SPIN_DECAY'),
};
G.INERTIA = javaConst(P, 'INERTIA', G);

const v3 = (x = 0, y = 0, z = 0) => ({ x, y, z });
const add = (a, b) => v3(a.x + b.x, a.y + b.y, a.z + b.z);
const sub = (a, b) => v3(a.x - b.x, a.y - b.y, a.z - b.z);
const mul = (a, s) => v3(a.x * s, a.y * s, a.z * s);
const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
const cross = (a, b) => v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
const len = (a) => Math.sqrt(dot(a, a));
const norm = (a) => (len(a) < 1e-12 ? v3() : mul(a, 1 / len(a)));

const SURFACE = { NORMAL: { e: 0.72, mu: 0.85 }, BRUSH: { e: 0.62, mu: 1.15 } };

function resolve(ballV, ballSpin, n, paddleV, surface) {
  n = norm(n);
  const relative = sub(ballV, paddleV);
  const un = dot(relative, n);
  if (un >= 0) return { v: ballV, spin: ballSpin, contact: false, slipping: false };
  const r = mul(n, -G.BALL_RADIUS);
  const contact = add(relative, cross(ballSpin, r));
  const tangent = sub(contact, mul(n, dot(contact, n)));
  const slip = len(tangent);
  const Jn = -(1 + surface.e) * un * G.MASS;
  let nv = add(ballV, mul(n, Jn / G.MASS));
  let ns = ballSpin;
  let slipping = false;
  if (slip > 1e-6) {
    const dir = mul(tangent, -1 / slip);
    const stop = slip / (1 / G.MASS + (G.BALL_RADIUS * G.BALL_RADIUS) / G.INERTIA);
    const limit = surface.mu * Math.abs(Jn);
    const jt = Math.min(limit, stop);
    slipping = limit < stop;
    const J = mul(dir, jt);
    nv = add(nv, mul(J, 1 / G.MASS));
    ns = add(ns, mul(cross(r, J), 1 / G.INERTIA));
  }
  return { v: nv, spin: ns, slip, slipping, contact: true };
}

function hit(ballV, ballSpin, n, swingDir, swingSpeed, power, surface) {
  n = norm(n);
  // 【力度是非线性的】挥拍速度 = swingSpeed × (0.6 + 0.4×power)
  // 纯线性（×power）会让 0.2 力度下网、1.0 力度出台：出球速度动态范围太大，
  // 而球台只有 2.74 格。压成 0.6~1.0 倍后，全力度段都能落在台内。
  let swing = mul(norm(swingDir), swingSpeed * (0.6 + 0.4 * power));
  const along = dot(swing, n);
  if (along < 0) swing = sub(swing, mul(n, along));
  return resolve(ballV, ballSpin, n, swing, surface);
}

const TABLE = 0.76;
const NET_TOP = TABLE + 0.15;
const NET_X = 1.97;
const FAR = 3.34;
const CONTACT_H = TABLE + 0.23;
const APPROACH = v3(-0.30, 0, 0);
const IN_SPIN = v3(0, 0, -1.5);

function fly(v, spin) {
  let vx = v.x, vy = v.y, s = v3(0, spin.y, spin.z);
  let x = 0, y = CONTACT_H, apex = y, net = null;
  for (let t = 1; t <= 400; t++) {
    const a = mul(cross(s, v3(vx, vy, 0)), G.MAGNUS_COEFFICIENT);
    vx += a.x; vy += -G.GRAVITY + a.y;
    const f = Math.min(1, Math.max(0.75, 1 - (G.DRAG_LINEAR + G.DRAG_QUADRATIC * Math.hypot(vx, vy))));
    vx *= f; vy *= f; s = mul(s, G.SPIN_DECAY);
    x += vx; y += vy; apex = Math.max(apex, y);
    if (net === null && x >= NET_X) net = y - NET_TOP;
    if (y <= TABLE && vy < 0) return { land: x, net, apex, ticks: t };
  }
  return { land: Infinity, net, apex, ticks: 400 };
}

const normalOf = (tiltDeg) => {
  const rad = (tiltDeg * Math.PI) / 180;
  return norm(v3(Math.cos(rad), Math.sin(rad), 0));
};

function evaluate(stroke, tiltDeg, swingSpeed, power) {
  const r = hit(APPROACH, IN_SPIN, normalOf(tiltDeg), v3(stroke.swingX, stroke.swingY, 0),
    swingSpeed, power, SURFACE[stroke.surface]);
  if (r.v.x <= 0) return { ok: false, reason: '出球没有朝对面', r };
  const f = fly(r.v, r.spin);
  const good = f.net !== null && f.net >= 0.03 && f.land > NET_X && f.land < FAR;
  return { ok: good, speed: len(r.v), land: f.land, net: f.net, apex: f.apex, topspin: -r.spin.z, r, f };
}

// ------------------------------------------------------------------
// 每种击球：扫「拍面角 × 挥拍速度」，找出中等力度(0.6)能过网落台、且自旋方向正确的组合
// ------------------------------------------------------------------
const TYPES = [
  { key: 'DRIVE', swingX: 1.00, swingY: 0.05, surface: 'NORMAL', wantTopspin: null },
  { key: 'LOOP', swingX: 0.92, swingY: 0.39, surface: 'BRUSH', wantTopspin: 'positive' },
  { key: 'PUSH', swingX: 1.00, swingY: -0.18, surface: 'BRUSH', wantTopspin: 'negative' },
  { key: 'CHOP', swingX: 0.82, swingY: -0.57, surface: 'BRUSH', wantTopspin: 'negative' },
];

const POWER_MID = 0.6;
console.log('=== 扫参：每种击球的拍面角 × 挥拍速度（力度 ' + POWER_MID + '）===');
console.log('  类型   拍面角范围   挥拍速度范围     可行组合数   推荐(拍面角 / 挥拍速度)  出球速度  自旋');
const picked = {};
for (const ty of TYPES) {
  const hits = [];
  for (let tilt = -30; tilt <= 55; tilt += 1) {
    for (let sv = 0.05; sv <= 0.8001; sv += 0.01) {
      const e = evaluate(ty, tilt, Math.round(sv * 100) / 100, POWER_MID);
      if (!e.ok) continue;
      if (ty.wantTopspin === 'positive' && !(e.topspin > 0.3)) continue;
      if (ty.wantTopspin === 'negative' && !(e.topspin < -0.3)) continue;
      hits.push({ tilt, sv: Math.round(sv * 100) / 100, ...e });
    }
  }
  if (hits.length === 0) {
    console.log(`  ${ty.key.padEnd(6)} 无可行组合 ❌（需要改挥拍方向）`);
    picked[ty.key] = null;
    continue;
  }
  // 推荐：取"出球速度最接近 0.35"的那一组（0.35 是能过网又不出台的中间值）
  hits.sort((a, b) => Math.abs(a.speed - 0.35) - Math.abs(b.speed - 0.35));
  const best = hits[0];
  picked[ty.key] = best;
  const tilts = hits.map((h) => h.tilt);
  const svs = hits.map((h) => h.sv);
  console.log(
    `  ${ty.key.padEnd(6)} ${(Math.min(...tilts) + '° ~ ' + Math.max(...tilts) + '°').padEnd(13)} ` +
    `${(Math.min(...svs).toFixed(2) + ' ~ ' + Math.max(...svs).toFixed(2)).padEnd(16)} ${String(hits.length).padEnd(12)} ` +
    `${(best.tilt + '° / ' + best.sv).padEnd(24)} ${best.speed.toFixed(2)}     ${best.topspin.toFixed(2)}`
  );
}

console.log('\n=== 力度扫（用推荐值；看轻打是否下网、满打是否飞出天际）===');
console.log('  类型   力度0.2        0.4           0.6           0.8           1.0');
for (const ty of TYPES) {
  const best = picked[ty.key];
  if (!best) continue;
  const cells = [];
  for (const pw of [0.2, 0.4, 0.6, 0.8, 1.0]) {
    const e = evaluate(ty, best.tilt, best.sv, pw);
    const tag = !e.f || e.f.land === Infinity ? '飞'
      : e.f.net < 0.03 ? '网' : e.f.land > FAR ? '出' : '✔';
    cells.push(`${(e.f ? e.f.land : 0).toFixed(2)}/${tag}`.padEnd(14));
  }
  console.log(`  ${ty.key.padEnd(6)} ${cells.join('')}`);
}

console.log('\n=== 建议写进 StrokeType.java ===');
for (const ty of TYPES) {
  const b = picked[ty.key];
  if (!b) continue;
  console.log(`  ${ty.key.padEnd(6)} normalTilt = ${b.tilt}°   swingSpeed = ${b.sv}   （出球 ${b.speed.toFixed(2)} 格/tick，自旋 ${b.topspin.toFixed(2)}）`);
}
