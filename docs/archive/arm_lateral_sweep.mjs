#!/usr/bin/env node
/**
 * 反手「收向胸前」的定稿扫描（第六次，这次杠杆用对了）。
 *
 * 【前几版错在哪】我一直用躯干转体（bodyYaw）实现横向收臂，但实测它只能把肩挪 0.01 格
 * （肩在躯干中心旁边只有 1 像素），**对手臂位置几乎没有贡献**。
 * 真正的杠杆是**手臂自己的 armYaw**：−60°→+60° 能让球拍横向移动 1.1 格（见 A 表）。
 *
 * 用户要求：手臂从正常位置收到**胸前**（x ≈ 0，即身体中线）。
 * 用法：node tools/_arm_lateral_sweep.mjs
 */
import { readFileSync } from 'node:fs';

const src = readFileSync('src/main/java/com/whale/pingpong/util/ArmPose.java', 'utf8');
const num = (name) => {
  const m = new RegExp(`static final double ${name}\\s*=\\s*([^;]+);`).exec(src);
  if (!m) throw new Error(`找不到常量 ${name}`);
  return Function(`"use strict";return (${m[1].trim()});`)();
};
const ARM = num('ARM_LENGTH');
const PADDLE = num('PADDLE_LENGTH');
const SHOULDER = 5.0 / 16.0;
const rad = (d) => (d * Math.PI) / 180;

/** 三维姿态：手臂 roll→yaw→pitch，再叠躯干 yaw→pitch（与 ModelPart 累积顺序一致） */
function paddle(pitchDeg, armYawDeg, armRollDeg, bodyYawDeg = 0, bodyPitchDeg = 0, forehand = false) {
  const handed = forehand ? 1.0 : -1.0;
  const p = rad(pitchDeg);
  let vx = -SHOULDER * handed;
  let vy = ARM * Math.cos(p);
  let vz = ARM * Math.sin(p);

  const ar = rad(armRollDeg);
  let nx = vx * Math.cos(ar) - vy * Math.sin(ar);
  let ny = vx * Math.sin(ar) + vy * Math.cos(ar);
  vx = nx; vy = ny;

  const ay = rad(armYawDeg);
  nx = vx * Math.cos(ay) + vz * Math.sin(ay);
  const nz = -vx * Math.sin(ay) + vz * Math.cos(ay);
  vx = nx; vz = nz;

  ny = vy * Math.cos(p) - vz * Math.sin(p);
  const nz2 = vy * Math.sin(p) + vz * Math.cos(p);
  vy = ny; vz = nz2;

  const by = rad(bodyYawDeg);
  const rx = vx * Math.cos(by) + vz * Math.sin(by);
  const rz = -vx * Math.sin(by) + vz * Math.cos(by);
  const bp = rad(bodyPitchDeg);
  const y2 = vy * Math.cos(bp) - rz * Math.sin(bp);
  const z2 = vy * Math.sin(bp) + rz * Math.cos(bp);

  const dirY = Math.sin(p + bp);
  const dirZ = Math.cos(p + bp);
  return { x: rx, forward: -(z2 + dirZ * PADDLE), up: -(y2 + dirY * PADDLE) };
}

console.log('=== A. 横向杠杆对照：谁能把球拍移到中线（x≈0）===');
console.log('  armRoll   横向x      armYaw   横向x      bodyYaw  横向x');
for (const v of [-60, -40, -20, 0, 20, 40, 60]) {
  const a = paddle(-110, 0, v).x;
  const b = paddle(-110, v, 0).x;
  const c = paddle(-110, 0, 0, v).x;
  console.log(`  ${String(v).padStart(6)}°  ${a.toFixed(3).padStart(7)}   ${String(v).padStart(6)}°  ${b.toFixed(3).padStart(7)}   ${String(v).padStart(6)}°  ${c.toFixed(3).padStart(7)}`);
}

console.log('\n=== B. ArmPose 的 armYaw 默认 0 → 定稿反手参数（绝对值，Java 里由符号相乘得到）===');
console.log('  姿态                      横向x     身前     高度    说明');
const w = paddle(-30, 50, -10);
const f = paddle(-110, 10, 0);
console.log(`  引拍（pitch−30 yaw50 roll−10）  ${w.x.toFixed(3).padStart(6)}  ${w.forward.toFixed(3).padStart(6)}  ${w.up.toFixed(3).padStart(6)}   手臂甩到外侧`);
console.log(`  前挥（pitch−110 yaw10 roll0）   ${f.x.toFixed(3).padStart(6)}  ${f.forward.toFixed(3).padStart(6)}  ${f.up.toFixed(3).padStart(6)}   收向胸前中线`);
console.log(`  → 横移 ${(f.x - w.x).toFixed(3)} 格（${(f.x - w.x) > 0.3 ? '✔ 明显收臂' : '✘ 不够'}）`);

console.log('\n=== C. 与正手对照（正手 armYaw/roll 默认 0，靠 pitch 从下往上兜）===');
const fhW = paddle(-30 + 96, 0, -12, -24, -10, true);
const fhF = paddle(-30 - 62, 0, 10, 30, 0, true);
console.log(`  正手 引拍 x=${fhW.x.toFixed(3)} → 前挥 x=${fhF.x.toFixed(3)}（横移 ${(fhF.x - fhW.x).toFixed(3)}）`);
console.log(`  反手 引拍 x=${w.x.toFixed(3)} → 前挥 x=${f.x.toFixed(3)}（横移 ${(f.x - w.x).toFixed(3)}）`);
