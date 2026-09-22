#!/usr/bin/env node
/**
 * 反手拉球的**横向**可达性：验证「手臂往胸前收」能不能靠转腰做出来。
 *
 * 用户 2026-09-22 第三次纠正：「手臂要往胸前收，不要只往后收」。
 *
 * 几何约束（ArmPose 注释里就写着）：手臂只能绕肩旋转，手恒落在**过肩的竖直面**里，
 * 所以手的横向位置 x 与手臂角度无关（恒等于肩的 x）—— 横向动作**只能靠躯干转体（bodyYaw）给**。
 * 这正是真人打球要转腰的原因。这个脚本算的就是：bodyYaw 转多少度、球拍能横移多少。
 *
 * 用法：node tools/_chest_sweep.mjs
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
 * 镜像 Java ArmPose.paddleOffset 的完整三维版本（含 bodyYaw）。
 * 返回 {x（横向：正 = 玩家右侧）, forward（身前为正）, up}。
 * 手持拍手是右手 → handed = +1（正手）时 shoulder 在 −x 侧。
 */
function paddle3d(pitchDeg, bodyYawDeg, bodyPitchDeg, forehand = true) {
  const handed = forehand ? 1.0 : -1.0;
  const p = rad(pitchDeg);
  const handY = ARM * Math.cos(p);
  const handZ = ARM * Math.sin(p);
  const handX = -SHOULDER * handed;

  const b = rad(bodyYawDeg);
  const rotX = handX * Math.cos(b) + handZ * Math.sin(b);
  const rotZ = -handX * Math.sin(b) + handZ * Math.cos(b);

  const bp = rad(bodyPitchDeg);
  const y2 = handY * Math.cos(bp) - rotZ * Math.sin(bp);
  const z2 = handY * Math.sin(bp) + rotZ * Math.cos(bp);

  const dirY = Math.sin(p + bp);
  const dirZ = Math.cos(p + bp);

  // 游戏坐标：x = rotX（横向），z 取反（身前为负 → forward 取正），y 取反（上为正）
  return {
    x: rotX,
    forward: -(z2 + dirZ * PADDLE),
    up: -(y2 + dirY * PADDLE),
  };
}

console.log('=== A. 只转腰（手臂角固定 −30 = 待机），看横向位移 ===');
console.log('  bodyYaw   横向 x     身前    高度');
for (const yaw of [-60, -40, -20, 0, 20, 40, 60]) {
  const r = paddle3d(-30, yaw, 0);
  console.log(`  ${String(yaw).padStart(7)}°  ${r.x.toFixed(3).padStart(7)}  ${r.forward.toFixed(3).padStart(6)}  ${r.up.toFixed(3).padStart(6)}`);
}

console.log('\n=== B. 反手拉球候选：引拍在外侧 → 前挥收向胸前中线（x → 0）===');
console.log('  组合                                   引拍x    前挥x    横移量   前挥身前  判定');
for (const [wYaw, fYaw, note] of [
  [0, 0, '不转腰（旧版：只有前后）'],
  [20, -10, '轻微转'],
  [28, -30, '中等转'],
  [35, -35, '较大转'],
  [40, -40, '最大转'],
]) {
  const w = paddle3d(-30, wYaw, 0);          // 反手引拍 = 待机 pitch
  const f = paddle3d(-110, fYaw, 0);         // 反手前挥 = pitch −110
  // 【方向说明】x 增大 = 朝身体中线/另一侧收（实测：待机 x = −0.313，中线在 0 附近）。
  // 所以"收向胸前"= 前挥的 x **大于** 引拍的 x。
  const shift = f.x - w.x;
  const ok = shift > 0.25 && f.forward > 0.4;
  console.log(`  引拍yaw ${String(wYaw).padStart(3)}° / 前挥yaw ${String(fYaw).padStart(4)}°  `.padEnd(42) +
    `${w.x.toFixed(3).padStart(7)} ${f.x.toFixed(3).padStart(8)} ${shift.toFixed(3).padStart(8)} ` +
    `${f.forward.toFixed(3).padStart(9)}   ${ok ? '✔ 收向中线' : '✘'}  ${note}`);
}

console.log('\n=== C. 与正手对照（正手默认：引拍 yaw −24 / 前挥 yaw +30，handed 相反）===');
const fhW = paddle3d(-30 + 96, -24, -10);
const fhF = paddle3d(-30 - 62, 30, 0);
console.log(`  正手 引拍 x=${fhW.x.toFixed(3)} → 前挥 x=${fhF.x.toFixed(3)}（横移 ${(fhW.x - fhF.x).toFixed(3)}）`);
const bhW = paddle3d(-30, 28, 0);
const bhF = paddle3d(-110, -30, 0);
console.log(`  反手 引拍 x=${bhW.x.toFixed(3)} → 前挥 x=${bhF.x.toFixed(3)}（横移 ${(bhW.x - bhF.x).toFixed(3)}）`);
console.log('  → 两者横移方向应当相反（正手从身体外侧扫向中线，反手从反手侧扫向中线）');
