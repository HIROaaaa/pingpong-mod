#!/usr/bin/env node
/**
 * 侧旋轴公式的对照实验（M1 用，一次性诊断脚本，保留作证据）。
 *
 * 背景：用户报「侧旋只有击球那一下向侧面动一下，之后就走直线」。
 * PingPongBallEntity 里侧旋轴写的是
 *     sideAxis = up * cos(40°) + flat * sin(40°)
 * 但「朝行进方向倾斜 40°」的正确写法是
 *     sideAxis = up * sin(40°) + flat * cos(40°)
 * 前者算出来几乎等于纯竖直轴，而纯竖直轴在水平面上的接触点速度 ω×r ≡ 0，
 * 数学上就不可能侧拐。这个脚本把两种写法都跑一遍，用数字对比。
 *
 * 运行：node tools/diag_side_axis.js
 */
'use strict';

const v3 = (x = 0, y = 0, z = 0) => ({ x, y, z });
const add = (a, b) => v3(a.x + b.x, a.y + b.y, a.z + b.z);
const mul = (a, s) => v3(a.x * s, a.y * s, a.z * s);
const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
const cross = (a, b) => v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
const len = (a) => Math.sqrt(dot(a, a));
const norm = (a) => (len(a) < 1e-9 ? v3() : mul(a, 1 / len(a)));

const G = 0.030;
const DRAG_LINEAR = 0.010;
const DRAG_QUADRATIC = 0.022;
const K = 0.005;
const SPIN_DECAY = 0.988;
const MAX_SPIN = 5.5;
const R = 0.14;
const M = 1.0;
const I = (2 / 3) * M * R * R;
const TILT = (40 * Math.PI) / 180;

const flat = norm(v3(1, 0, 0));   // 行进方向（水平）
const up = v3(0, 1, 0);

const axisBuggy = norm(add(mul(up, Math.cos(TILT)), mul(flat, Math.sin(TILT))));
const axisFixed = norm(add(mul(up, Math.sin(TILT)), mul(flat, Math.cos(TILT))));

console.log('=== 侧旋轴对比 ===');
console.log(`  行进方向        = (${flat.x}, ${flat.y}, ${flat.z})`);
console.log(`  现有写法 up*cos+flat*sin = (${axisBuggy.x.toFixed(3)}, ${axisBuggy.y.toFixed(3)}, ${axisBuggy.z.toFixed(3)})  与竖直方向夹角 ${(Math.acos(Math.abs(dot(axisBuggy, up))) * 180 / Math.PI).toFixed(1)}°`);
console.log(`  正确写法 up*sin+flat*cos = (${axisFixed.x.toFixed(3)}, ${axisFixed.y.toFixed(3)}, ${axisFixed.z.toFixed(3)})  与竖直方向夹角 ${(Math.acos(Math.abs(dot(axisFixed, up))) * 180 / Math.PI).toFixed(1)}°`);

/** 一次落台前的横向偏移 */
function lateral(axis, spinStrength, speed = 0.30, elevDeg = 6) {
  const rad = (elevDeg * Math.PI) / 180;
  let v = v3(speed * Math.cos(rad), speed * Math.sin(rad), 0);
  let spin = mul(axis, spinStrength);
  let p = v3(0, 1.05, 0);
  for (let t = 1; t <= 400; t++) {
    const mag = mul(cross(spin, v), K);
    v = add(v, add(v3(0, -G, 0), mag));
    v = mul(v, Math.min(1, Math.max(0.75, 1 - (DRAG_LINEAR + DRAG_QUADRATIC * len(v)))));
    p = add(p, v);
    spin = mul(spin, SPIN_DECAY);
    if (p.y <= 0.76 && v.y < 0) return { z: p.z, x: p.x, t };
  }
  return { z: p.z, x: p.x, t: 400 };
}

console.log('\n=== 飞行中的横向偏移（同速度、同自旋强度）===');
console.log('  自旋强度   现有写法(z)   正确写法(z)');
for (const s of [1.0, 2.0, 3.0]) {
  const a = lateral(axisBuggy, s);
  const b = lateral(axisFixed, s);
  console.log(`  ${s.toFixed(1).padStart(6)}    ${a.z.toFixed(3).padStart(9)}    ${b.z.toFixed(3).padStart(9)}   （落点 x≈${b.x.toFixed(2)}）`);
}

/** 落地那一刻的侧向接触速度 ω×r（决定「落地侧拐」） */
console.log('\n=== 水平面上接触点速度 ω×r 的侧向分量（决定落地会不会侧拐）===');
const rContact = v3(0, -R, 0);
for (const [name, axis] of [['现有写法', axisBuggy], ['正确写法', axisFixed]]) {
  const spin = mul(axis, 2.5);
  const contact = cross(spin, rContact);
  const lateralComp = Math.abs(contact.z);
  console.log(`  ${name}：ω×r = (${contact.x.toFixed(4)}, ${contact.y.toFixed(4)}, ${contact.z.toFixed(4)})  侧向分量 ${lateralComp.toFixed(4)}`);
}

console.log('\n结论：现有写法的侧旋轴与竖直方向夹角只有 ' +
  `${(Math.acos(Math.abs(dot(axisBuggy, up))) * 180 / Math.PI).toFixed(1)}°，` +
  `而正确写法是 ${(Math.acos(Math.abs(dot(axisFixed, up))) * 180 / Math.PI).toFixed(1)}°。`);
