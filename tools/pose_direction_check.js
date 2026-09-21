#!/usr/bin/env node
/**
 * 乒乓球动作姿态的**方向验证**（五期 M6 第二轮）。
 *
 * 【为什么需要】用户实测反馈「只是手臂微微动了一下」「正反手好像反了」。
 * 幅度好办（改数字），但**方向靠手感猜会错**：上一版就是靠直觉写的，
 * 一算就发现"引拍时手跑到了身前、前挥时手跑到了身后" —— 幅度再大也只是更明显地把动作做反。
 *
 * 这个脚本用真实的模型几何 + MC 的旋转顺序算出**手的位置**，断言每个相位的语义：
 *   待机  手在身前（z < 0）
 *   引拍  手在**身后**（z > 0）—— 用户要的"往后引拍"
 *   前挥  手回到**身前**（z < 0）
 * 并且断言摆幅够大、正反手互为镜像。
 *
 * 常量全部从 PingPongModelPose.java 解析，改 Java 后本脚本自动跟上。
 *
 * 运行：node tools/pose_direction_check.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const POSE = 'src/main/java/com/whale/pingpong/client/PingPongModelPose.java';
const src = fs.readFileSync(path.join(ROOT, POSE), 'utf8');

function constOf(name) {
  const m = src.match(new RegExp(String.raw`(?:private|public) static final float\s+` + name + String.raw`\s*=\s*([-+*/(). 0-9eE Ff_]+);`));
  if (!m) throw new Error(`找不到常量 ${name}`);
  const expr = m[1].trim().replace(/(\d)F\b/gi, '$1').replace(/F\b/g, '');
  // eslint-disable-next-line no-new-func
  return Function(`"use strict";return (${expr});`)();
}

const NAMES = [
  'HANDED_FLIP', 'READY_ARM_PITCH', 'READY_ARM_ROLL',
  'WINDUP_PITCH', 'WINDUP_ROLL', 'WINDUP_BODY_YAW', 'WINDUP_BODY_PITCH',
  'FORWARD_PITCH', 'FORWARD_BODY_YAW', 'FORWARD_BODY_PITCH', 'FOLLOW_PITCH',
];
const C = {};
for (const n of NAMES) C[n] = constOf(n);

// ------------------------------------------------------------------
// 模型几何（Yarn 1.20.1：PlayerEntityModel 的手臂 pivot x = -5/5，臂长 11）
//   坐标系：x 右为负、y 向下为正、z 后方为正；玩家面朝 -z，所以"身前" = z 负值
// ------------------------------------------------------------------
const ARM_LEN = 11;
const SHOULDER = { right: { x: -5, y: 2 }, left: { x: +5, y: 2 } };
const HAND_DOWN = { x: 0, y: ARM_LEN, z: 0 };

const rad = (d) => (d * Math.PI) / 180;
const rotX = (v, a) => ({ x: v.x, y: v.y * Math.cos(a) - v.z * Math.sin(a), z: v.y * Math.sin(a) + v.z * Math.cos(a) });
const rotY = (v, a) => ({ x: v.x * Math.cos(a) + v.z * Math.sin(a), y: v.y, z: -v.x * Math.sin(a) + v.z * Math.cos(a) });
const rotZ = (v, a) => ({ x: v.x * Math.cos(a) - v.y * Math.sin(a), y: v.x * Math.sin(a) + v.y * Math.cos(a), z: v.z });

/**
 * 手相对躯干中心的位置。
 * 手臂：先 roll 再 yaw 再 pitch（MC 的 ModelPart 累积顺序）；
 * 躯干整体再绕 Y 转（bodyYaw）与绕 X 转（bodyPitch）—— 转体也会把手甩到侧面，
 * 这正是真人打球"转腰带手"的效果，所以必须算进去。
 */
function handPosition(side, pitchDeg, rollDeg, bodyYawDeg, bodyPitchDeg) {
  const handed = side === 'right' ? C.HANDED_FLIP : -C.HANDED_FLIP;
  let v = rotZ(HAND_DOWN, rad(rollDeg * handed));
  v = rotX(v, rad(pitchDeg));
  let p = { x: SHOULDER[side].x + v.x, y: SHOULDER[side].y + v.y, z: v.z };
  p = rotY(p, rad(bodyYawDeg * handed));
  p = rotX(p, rad(bodyPitchDeg));
  return p;
}

/** 各相位（与 PingPongModelPose.update 的公式一致） */
function phase(name, side) {
  if (name === '待机') {
    return handPosition(side, C.READY_ARM_PITCH, C.READY_ARM_ROLL, 0, 0);
  }
  if (name === '引拍') {
    return handPosition(side, C.READY_ARM_PITCH + C.WINDUP_PITCH, C.READY_ARM_ROLL + C.WINDUP_ROLL,
      C.WINDUP_BODY_YAW, C.WINDUP_BODY_PITCH);
  }
  // 前挥：引拍归零 + forward 全量
  return handPosition(side, C.READY_ARM_PITCH + C.FORWARD_PITCH, C.READY_ARM_ROLL,
    C.FORWARD_BODY_YAW, C.FORWARD_BODY_PITCH);
}

let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
const f = (n) => (n >= 0 ? '+' : '') + n.toFixed(2);

console.log('=== 常量（从 PingPongModelPose.java 解析）===');
for (const [k, v] of Object.entries(C)) console.log(`  ${k.padEnd(20)} = ${v}`);

console.log('\n=== 各相位下"手"的位置（相对躯干中心；z 负 = 身前，z 正 = 身后）===');
console.log('  相位        正手 x/y/z                反手 x/y/z                左右镜像?');
const pos = {};
for (const name of ['待机', '引拍', '前挥']) {
  const r = phase(name, 'right');
  const l = phase(name, 'left');
  pos[name] = { right: r, left: l };
  const fmt = (p) => `${f(p.x).padStart(6)}/${f(p.y).padStart(6)}/${f(p.z).padStart(6)}`;
  console.log(`  ${name.padEnd(10)}  ${fmt(r)}    ${fmt(l)}    ${Math.abs(r.x + l.x) < 0.01 ? '是' : '否'}`);
}

const ready = pos['待机'].right;
const windup = pos['引拍'].right;
const forward = pos['前挥'].right;

console.log('\n=== 方向断言 ===');
check('① 待机时手在身前（z < 0）', ready.z < 0, `z=${f(ready.z)}`);
check('② 引拍时手在**身后**（z > 0）—— 用户要的"往后引拍"',
  windup.z > 0, `z=${f(windup.z)}（待机 ${f(ready.z)} → 引拍 ${f(windup.z)}）`);
check('③ 前挥时手回到**身前**（z < 0）', forward.z < 0, `z=${f(forward.z)}`);
check('④ 引拍的手比待机更高（y 更小）', windup.y < ready.y,
  `待机 y=${f(ready.y)} → 引拍 y=${f(windup.y)}`);
check('⑤ 摆幅够大：引拍→前挥的手部行程 ≥ 10 像素（约 0.6 格）',
  Math.hypot(forward.x - windup.x, forward.y - windup.y, forward.z - windup.z) >= 10,
  `行程 ${Math.hypot(forward.x - windup.x, forward.y - windup.y, forward.z - windup.z).toFixed(2)} 像素`);
check('⑥ 待机→引拍也有明显行程（≥ 5 像素，蓄力时看得见手在动）',
  Math.hypot(windup.x - ready.x, windup.y - ready.y, windup.z - ready.z) >= 5,
  `行程 ${Math.hypot(windup.x - ready.x, windup.y - ready.y, windup.z - ready.z).toFixed(2)} 像素`);
/*
 * 【镜像断言】左右肩 x 分别是 -5 与 +5，镜像的判据是两者**之和为 0**
 * （第一版写成"和为 10"是错的：右肩 -5 的镜像就是 +5）。
 */
check('⑦ 正反手姿态互为镜像（x 之和 ≈ 0）',
  Math.abs(ready.x + pos['待机'].left.x) < 0.01
  && Math.abs(windup.x + pos['引拍'].left.x) < 0.01
  && Math.abs(forward.x + pos['前挥'].left.x) < 0.01,
  `待机 ${f(ready.x + pos['待机'].left.x)} / 引拍 ${f(windup.x + pos['引拍'].left.x)} / 前挥 ${f(forward.x + pos['前挥'].left.x)}`);
check('⑧ 正反手不是同一套动作（右手在 -x 侧、左手在 +x 侧）',
  ready.x < 0 && pos['待机'].left.x > 0,
  `待机：正手 x=${f(ready.x)} / 反手 x=${f(pos['待机'].left.x)}`);

console.log(`\n===== ${fails === 0 ? '方向全部正确 ✅' : fails + ' 项方向有误 ❌'} =====`);
process.exit(fails === 0 ? 0 : 1);
