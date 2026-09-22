#!/usr/bin/env node
/**
 * 反手 armYaw 定稿值计算（含 handed 符号）。
 *
 * ArmPose 里 yaw 会乘 handed（正手 +1 / 反手 −1），所以写进常量的值要让
 * 「反手时实际生效的 armYaw」落在目标区间。这里把带符号的完整链路算一遍。
 *
 * 目标（用户原话「手臂要放到胸前的位置」）：前挥时球拍落在身体中线附近（x ≈ 0），
 * 引拍时手臂甩在外侧（x ≈ +0.3），横移 ≈ 0.3 格。
 *
 * 用法：node tools/_arm_yaw_final.mjs
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

/**
 * 与 Java paddleOffset 同构。
 * @param armYawEff 已乘过 handed 的**实际** armYaw（度）
 */
function paddle(pitchDeg, armYawEff, armRollEff, bodyYawEff = 0, bodyPitchEff = 0, forehand = false) {
  const handed = forehand ? 1.0 : -1.0;
  const p = rad(pitchDeg);
  const handX = -SHOULDER * handed;
  const handY = ARM * Math.cos(p);
  const handZ = ARM * Math.sin(p);

  const ar = rad(armRollEff);
  const rollX = handX * Math.cos(ar) - handY * Math.sin(ar);
  const rollY = handX * Math.sin(ar) + handY * Math.cos(ar);

  const ay = rad(armYawEff);
  const yawX = rollX * Math.cos(ay) + handZ * Math.sin(ay);
  const yawZ = -rollX * Math.sin(ay) + handZ * Math.cos(ay);

  const by = rad(bodyYawEff);
  const rotX = yawX * Math.cos(by) + yawZ * Math.sin(by);
  const rotZ = -yawX * Math.sin(by) + yawZ * Math.cos(by);

  const bp = rad(bodyPitchEff);
  const y2 = rollY * Math.cos(bp) - rotZ * Math.sin(bp);
  const z2 = rollY * Math.sin(bp) + rotZ * Math.cos(bp);

  const dirY = Math.sin(p + bp);
  const dirZ = Math.cos(p + bp);
  return { x: rotX, forward: -(z2 + dirZ * PADDLE), up: -(y2 + dirY * PADDLE) };
}

const HANDED = -1.0; // 反手
console.log('反手（handed = −1）：写进常量的值 × (−1) = 实际生效值\n');
console.log('  常量值  实际yaw   姿态            横向x    身前     高度');

const CASES = [
  ['引拍', -30, '+30', +30],
  ['引拍', -50, '+50', +50],
  ['引拍', -45, '+45', +45],
  ['前挥', -110, '-10', -10],
  ['前挥', -110, '-30', -30],
  ['前挥', -110, '-50', -50],
];
for (const [phase, pitch, constantLabel, eff] of CASES) {
  const r = paddle(pitch, eff * HANDED, 0, 0, 0);
  console.log(`  ${constantLabel.padStart(6)}  ${String(eff).padStart(6)}°  ${phase} pitch${String(pitch).padStart(5)}°  ` +
    `${r.x.toFixed(3).padStart(7)}  ${r.forward.toFixed(3).padStart(6)}  ${r.up.toFixed(3).padStart(6)}`);
}

console.log('\n=== 候选组合（引拍手臂在外侧 → 前挥收到中线）===');
console.log('  引拍常量   前挥常量   引拍x    前挥x   横移    判定');
for (const [wc, fc] of [[30, -10], [35, -15], [40, -20], [45, -25], [50, -30]]) {
  const w = paddle(-30, wc * HANDED, 0, 0, 0);
  const f = paddle(-110, fc * HANDED, 0, 0, 0);
  const shift = f.x - w.x;
  const ok = Math.abs(f.x) < 0.15 && Math.abs(shift) > 0.3;
  console.log(`  ${String(wc).padStart(8)}   ${String(fc).padStart(8)}   ${w.x.toFixed(3).padStart(7)} ${f.x.toFixed(3).padStart(8)} ${shift.toFixed(3).padStart(7)}   ${ok ? '✔ 收向胸前中线' : '✘'}`);
}
