#!/usr/bin/env node
/**
 * M7.6 后续：「击球速度还是太快」——在**过网约束**下找最慢的可行组合。
 *
 * 【卡点】用户 2026-09-22 实测反馈「击球速度还是太快」，但标定显示 BASE_HIT_SPEED=0.27
 * 已经是**过网下限**（0.25/0.26 任何仰角都过不了网，因为击球点只比台面高 0.23 格、
 * 网在 1.97 格外高 0.15 格）。想再慢，就只能**把仰角抬高**换速度 —— 高弧线慢球
 * 在真实乒乓球里也存在（放高球/轻挑）。
 *
 * 这个脚本扫「基准仰角 × 基础速度」的组合，找：全部档位都能过网、且速度尽量低的解。
 *
 * 用法：node tools/speed_slow_sweep.js
 */
'use strict';

const G = 0.030;
const DRAG_LINEAR = 0.010;
const DRAG_QUADRATIC = 0.022;
const TABLE_H = 0.76;
const NET_TOP = TABLE_H + 0.15;
const NET_X = 1.97;
const FAR_EDGE = 3.34;
const CONTACT_H = TABLE_H + 0.23;
const MIN_ELEV = 6.0;

function fly(v0, elevDeg) {
  const rad = (elevDeg * Math.PI) / 180;
  let vx = v0 * Math.cos(rad);
  let vy = v0 * Math.sin(rad);
  let x = 0;
  let y = CONTACT_H;
  let netH = null;
  for (let t = 1; t <= 500; t++) {
    vy += -G;
    const f = Math.min(1, Math.max(0.75, 1 - (DRAG_LINEAR + DRAG_QUADRATIC * Math.hypot(vx, vy))));
    vx *= f; vy *= f;
    x += vx; y += vy;
    if (netH === null && x >= NET_X) netH = y;
    if (y <= TABLE_H) break;
  }
  return { land: x, net: netH === null ? null : netH - NET_TOP };
}

/** 该速度要过网需要的最小仰角 */
function minElevFor(v0, margin = 0.02) {
  for (let e = MIN_ELEV; e <= 60; e += 0.1) {
    const r = fly(v0, e);
    if (r.net !== null && r.net >= margin && r.land > NET_X) return e;
  }
  return null;
}

/** 评估一组参数：返回每个力度档的可行性与落点 */
function evaluate(base, bonus, slowElev, fastElev, floor = 0.25, cap = 0.45) {
  const rows = [];
  for (const pw of [0, 0.25, 0.5, 0.75, 1.0]) {
    const v = Math.min(cap, Math.max(floor, base + bonus * pw));
    const curveElev = slowElev + (fastElev - slowElev) * pw;
    const need = minElevFor(v);
    const e = need === null ? curveElev : Math.max(curveElev, need);
    const r = fly(v, e);
    const ok = r.net !== null && r.net > 0.02 && r.land <= FAR_EDGE;
    rows.push({ pw, v, e, land: r.land, net: r.net, ok });
  }
  return rows;
}

console.log('=== 目标：全部力度档都能过网且在台内，同时初速尽量低 ===');
console.log('  组合                             档0落点  档0.5落点  档1落点  最低速  判定');

const CANDIDATES = [
  ['现值  base .27 bonus .07 elev 24/14', 0.27, 0.07, 24, 14],
  ['A     base .25 bonus .05 elev 30/16', 0.25, 0.05, 30, 16],
  ['B     base .26 bonus .05 elev 28/15', 0.26, 0.05, 28, 15],
  ['C     base .26 bonus .04 elev 30/16', 0.26, 0.04, 30, 16],
  ['D     base .25 bonus .04 elev 32/18', 0.25, 0.04, 32, 18],
  ['E     base .24 bonus .04 elev 34/20', 0.24, 0.04, 34, 20],
  ['F     base .25 bonus .03 elev 33/19', 0.25, 0.03, 33, 19],
];

for (const [name, base, bonus, slow, fast] of CANDIDATES) {
  const rows = evaluate(base, bonus, slow, fast);
  const bad = rows.filter((r) => !r.ok).length;
  const slowest = Math.min(...rows.map((r) => r.v));
  console.log(`  ${name.padEnd(34)}` +
    rows.map((r) => r.land.toFixed(2).padStart(7)).join(' ') + '  ' +
    slowest.toFixed(3).padStart(6) + '  ' + (bad === 0 ? '✔ 全档可行' : `✘ ${bad} 档不行`));
}

console.log('\n=== 推荐组合的逐档明细 ===');
for (const [name, base, bonus, slow, fast] of [['C  base .26 bonus .04 elev 30/16', 0.26, 0.04, 30, 16],
  ['现值 base .27 bonus .07 elev 24/14', 0.27, 0.07, 24, 14]]) {
  console.log(`\n  ${name}`);
  console.log('   力度   初速    仰角    落点     过网余量  判定');
  for (const r of evaluate(base, bonus, slow, fast)) {
    console.log(`   ${r.pw.toFixed(2)}   ${r.v.toFixed(3)}   ${r.e.toFixed(1).padStart(5)}°  ` +
      `${r.land.toFixed(2).padStart(5)}   ${(r.net === null ? -9 : r.net).toFixed(2).padStart(6)}   ${r.ok ? '✔' : '✘ 下网/出台'}`);
  }
}
