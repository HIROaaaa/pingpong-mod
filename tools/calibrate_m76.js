#!/usr/bin/env node
/**
 * M7.6 §8.3 四参数标定（第二版：跑真实仰角曲线，不是"最优仰角"）。
 *
 * 上一版用「对每个初速扫描出最佳仰角」看落点，会高估低速档的能力。
 * 真实代码里仰角是**力度算出来的**（LAUNCH_BASE_SLOW → LAUNCH_BASE_FAST 线性），
 * 所以必须按同一条曲线复验，还要叠上 clampElevation 的可行性夹紧。
 *
 * 结论（本脚本实测）：初速低于 0.27 时任何仰角都过不了网 —— 所以
 * 「BASE 直接降到计划里的 0.24」会让轻打档彻底失效，最终取 0.27 作下界。
 *
 * 用法：node tools/calibrate_m76.js
 */
'use strict';

const G = 0.030;
const DRAG_LINEAR = 0.010;
const DRAG_QUADRATIC = 0.022;
const MAGNUS = 0.005;

const TABLE_H = 0.76;
const NET_TOP = TABLE_H + 0.15;
const NET_X = 1.97;
const FAR_EDGE = 3.34;
const CONTACT_H = TABLE_H + 0.23;

// 真实代码里的仰角曲线（PingPongBallEntity）
const LAUNCH_SLOW = 24.0;
const LAUNCH_FAST = 14.0;
const MIN_ELEV = 6.0;

function fly(v0, elevDeg) {
  const rad = (elevDeg * Math.PI) / 180;
  let vx = v0 * Math.cos(rad);
  let vy = v0 * Math.sin(rad);
  let x = 0;
  let y = CONTACT_H;
  let apex = y;
  let netH = null;
  for (let t = 1; t <= 500; t++) {
    vy += -G;
    const f = Math.min(1, Math.max(0.75, 1 - (DRAG_LINEAR + DRAG_QUADRATIC * Math.hypot(vx, vy))));
    vx *= f; vy *= f;
    x += vx; y += vy;
    apex = Math.max(apex, y);
    if (netH === null && x >= NET_X) netH = y;
    if (y <= TABLE_H) break;
  }
  return { land: x, net: netH === null ? null : netH - NET_TOP, apex, ticks: 0 };
}

/** 扫出「这个速度最低能配多大仰角过网」，作为可行性下界（对应 Java 的 clampElevation） */
function minElevFor(v0, margin = 0.02) {
  for (let e = MIN_ELEV; e <= 60; e += 0.5) {
    const r = fly(v0, e);
    if (r.net === null) continue;
    if (r.net >= margin && r.land > NET_X) return e;
  }
  return null;
}

const baseElev = (power) => LAUNCH_SLOW + (LAUNCH_FAST - LAUNCH_SLOW) * power;

function evaluate(name, BASE, BONUS, FLOOR_SPEED, CAP) {
  console.log(`\n=== ${name} ===`);
  console.log('   力度   初速    仰角(曲线)  夹紧后  落点    过网余量  判定');
  let bad = 0;
  for (const pw of [0, 0.25, 0.5, 0.75, 1.0]) {
    const v = Math.min(CAP, Math.max(FLOOR_SPEED, BASE + BONUS * pw));
    let e = baseElev(pw);
    const need = minElevFor(v);
    let clamped = false;
    if (need !== null && e < need) { e = need; clamped = true; }
    const r = fly(v, e);
    const nets = r.net !== null && r.net > 0.02;
    const inTable = r.land <= FAR_EDGE;
    const ok = nets && inTable;
    if (!ok) bad++;
    const zone = !nets ? '过不了网' : !inTable ? '出台' : r.land <= 1.8 ? '自己半场' : r.land <= 2.4 ? '对方近台' : '压底线';
    console.log(`   ${pw.toFixed(2)}   ${v.toFixed(3)}   ${baseElev(pw).toFixed(1).padStart(6)}°  ${e.toFixed(1).padStart(5)}°${clamped ? '*' : ' '}  ` +
      `${r.land.toFixed(2).padStart(5)}   ${(r.net === null ? -9 : r.net).toFixed(2).padStart(6)}   ${zone}`);
  }
  console.log(bad === 0 ? '   → 全部档位都能过网且在台内 ✔（* = 触发了可行性夹紧）' : `   → 有 ${bad} 个档位不可行 ✘`);
  return bad;
}

console.log('=== 0. 可行性下界：多低的初速能过网 ===');
for (const v of [0.24, 0.25, 0.26, 0.265, 0.27, 0.28, 0.30]) {
  const need = minElevFor(v);
  console.log(`   v=${v.toFixed(3)}  ${need === null ? '过不了网（任何仰角）' : `需要 ≥ ${need.toFixed(1)}°`}`);
}

evaluate('旧参数 BASE .30 / BONUS .11 / 地板 .28', 0.30, 0.11, 0.28, 0.45);
evaluate('计划值 BASE .24 / BONUS .07（不可行）', 0.24, 0.07, 0.24, 0.45);
evaluate('最终值 BASE .27 / BONUS .07 / 地板 .265', 0.27, 0.07, 0.265, 0.45);
evaluate('更保守 BASE .27 / BONUS .06 / 地板 .265', 0.27, 0.06, 0.265, 0.45);

console.log('\n=== 满力度上限（含借力 0.10）是否超底线 ===');
for (const [label, v] of [['满力平击', 0.34], ['满力+借力', 0.44], ['上限夹紧', 0.45]]) {
  const need = minElevFor(v);
  const e = Math.max(baseElev(1.0), need === null ? 60 : need);
  const r = fly(v, e);
  console.log(`   ${label.padEnd(10)} v=${v.toFixed(3)} 仰角 ${e.toFixed(1)}° 落点 ${r.land.toFixed(2)} ${r.land > FAR_EDGE + 0.3 ? '✘ 超出底线 0.3 以上' : '✔ 未超底线'}`);
}
