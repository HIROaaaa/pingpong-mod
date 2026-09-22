#!/usr/bin/env node
/**
 * 反手 armYaw 符号定稿（把两种候选各算一遍，直接读结论，不再靠推理）。
 *
 * 坐标约定（已按手性修正）：世界 x —— **正 = 反手的外侧（持拍手侧）**，x → 0 = 胸前中线。
 * 目标：引拍停在外侧（x 明显 > 0），前挥收到胸前（|x| ≈ 0）。
 *
 * 用法：node tools/_side_handedness.mjs
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

/** 世界横向（正 = 外侧）：含肩的基准偏移；armYawEff 是**已乘 handed** 的实际生效角 */
function lateralWorld(pitchDeg, armYawEff, forehand = false) {
  const handed = forehand ? 1.0 : -1.0;
  const p = rad(pitchDeg);
  const modelShoulderX = -SHOULDER * handed;      // 模型坐标：右肩 −5
  const modelY = ARM * Math.cos(p);
  const modelZ = ARM * Math.sin(p);
  const ay = rad(armYawEff);
  const yx = modelShoulderX * Math.cos(ay) + modelZ * Math.sin(ay);
  return -yx;                                      // 世界 X = −模型 x
}

const W_WINDUP_PITCH = -30;    // 反手引拍（= 待机）
const W_FORWARD_PITCH = -110;  // 反手前挥

console.log('反手（handed = −1）。目标：引拍在外侧、前挥收到胸前中线。\n');
console.log('  实际armYaw  引拍pitch-30    前挥pitch-110');
console.log('              世界x(外侧+)    世界x(外侧+)');
for (const eff of [-60, -45, -25, 0, 25, 45, 60]) {
  const w = lateralWorld(W_WINDUP_PITCH, eff);
  const f = lateralWorld(W_FORWARD_PITCH, eff);
  console.log(`  ${String(eff).padStart(9)}°  ${w.toFixed(3).padStart(11)}  ${f.toFixed(3).padStart(13)}`);
}

console.log('\n=== 候选：常量 → 实际生效 → 两个相位的世界 x ===');
console.log('  windup常量  forward常量   实际(w/f)     引拍x    前挥x    横移    判定');
for (const [wc, fc] of [[45, -25], [-45, 25], [45, 25], [-45, -25]]) {
  const we = wc * -1;   // handed = −1
  const fe = fc * -1;
  const wx = lateralWorld(W_WINDUP_PITCH, we);
  const fx = lateralWorld(W_FORWARD_PITCH, fe);
  const shift = fx - wx;                 // 负 = 往内收
  const ok = Math.abs(wx) > 0.2 && Math.abs(fx) < 0.15 && shift < -0.2;
  console.log(`  ${String(wc).padStart(10)}  ${String(fc).padStart(11)}   ` +
    `${String(we).padStart(4)}/${String(fe).padStart(4)}`.padEnd(14) +
    `${wx.toFixed(3).padStart(7)} ${fx.toFixed(3).padStart(8)} ${shift.toFixed(3).padStart(7)}   ${ok ? '✔ 外→胸前' : '✘'}`);
}

console.log('\n=== 正手对照（正手分支不设 armYaw，保持 0）===');
for (const [name, pitch] of [['引拍', -30 + 96], ['前挥', -30 - 62]]) {
  console.log(`  正手 ${name} 世界x = ${lateralWorld(pitch, 0, true).toFixed(3)}`);
}
