#!/usr/bin/env node
/**
 * 球拍位置（击球判定点）的验证 —— 用户第 4 条反馈：「点位要跟着动画里球拍的位置更改」。
 *
 * 【为什么需要】判定点从"写死的偏移"改成"由 ArmPose 按姿态算出"之后，有两个新风险：
 *   ① 算出来的点可能**跑到身体里面**或太靠后 —— 那样玩家永远打不到球；
 *   ② 四类击球的动作差异得真的体现出来（用户第 2、3 条反馈：搓球要像搓球、
 *      反手拉球要从腹部往下引拍再往上拉）。
 * 这两条都能用纯数学先算出来，不用进游戏。常量从 ArmPose.java 解析，改 Java 自动跟上。
 *
 * 运行：node tools/paddle_point_check.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const ARM_POSE = 'src/main/java/com/whale/pingpong/util/ArmPose.java';
const src = fs.readFileSync(path.join(ROOT, ARM_POSE), 'utf8');

function constOf(name, type = 'double') {
  const m = src.match(new RegExp(String.raw`public static final ` + type + String.raw`\s+` + name + String.raw`\s*=\s*([-+*/(). 0-9eE Ff_]+);`));
  if (!m) throw new Error(`找不到常量 ${name}`);
  const expr = m[1].trim().replace(/(\d)[Ff]\b/g, '$1').replace(/\bF\b/g, '');
  // eslint-disable-next-line no-new-func
  return Function(`"use strict";return (${expr});`)();
}
/**
 * 从 anglesFor 里抓各击球类型的参数。
 * 【为什么要抓手型分支】拉球的 if (forehand) / else 两支给的参数不同 ——
 * 反手是"从腹部往下引拍再往上拉"，正手是"从下往上兜"，第一版把两者写成同一组，
 * 脚本一比对就发现"反手前挥和正手一模一样"（等于没做反手）。
 */
function branchParams() {
  const block = src.slice(src.indexOf('switch (stroke)'));
  const out = {};
  const re = /case\s+([A-Z_]+):\s*\n(?:\s*case\s+([A-Z_]+):\s*\n)?([\s\S]*?)(?=\n\s*case|\n\s*default)/g;
  const grab = (body, name) => {
    const mm = body.match(new RegExp(name + String.raw`\s*=\s*([-0-9.]+)`));
    return mm ? parseFloat(mm[1]) : null;
  };
  let m;
  while ((m = re.exec(block)) !== null) {
    const keys = [m[1], m[2]].filter(Boolean);
    const body = m[3];
    // 手型分支：正手（if (forehand)）与反手（else）
    let forehand = body;
    let backhand = body;
    if (/if\s*\(forehand\)/.test(body)) {
      const parts = body.split(/}\s*else\s*{/);
      forehand = parts[0];
      backhand = parts[1] || body;
    }
    for (const k of keys) {
      const isBackhand = /BACKHAND/.test(k);
      const part = isBackhand ? backhand : forehand;
      out[k] = {
        windupPitch: grab(part, 'windupPitch'),
        forwardPitch: grab(part, 'forwardPitch'),
        windupBodyPitch: grab(part, 'windupBodyPitch'),
        windupRoll: grab(part, 'windupRoll'),
        forwardBodyYaw: grab(part, 'forwardBodyYaw'),
      };
    }
  }
  return out;
}

const ARM = {
  ARM_LENGTH: constOf('ARM_LENGTH'),
  PADDLE_LENGTH: constOf('PADDLE_LENGTH'),
};
const C = {
  basePitch: parseFloat(src.match(/double pitch = ([-0-9.]+);/)[1]),
  baseRoll: parseFloat(src.match(/double roll = ([-0-9.]+) \* handed;/)[1]),
  windupPitch: parseFloat(src.match(/double windupPitch = ([-0-9.]+);/)[1]),
  windupRoll: parseFloat(src.match(/double windupRoll = ([-0-9.]+);/)[1]),
  windupBodyYaw: parseFloat(src.match(/double windupBodyYaw = ([-0-9.]+);/)[1]),
  windupBodyPitch: parseFloat(src.match(/double windupBodyPitch = ([-0-9.]+);/)[1]),
  forwardPitch: parseFloat(src.match(/double forwardPitch = ([-0-9.]+);/)[1]),
  forwardBodyYaw: parseFloat(src.match(/double forwardBodyYaw = ([-0-9.]+);/)[1]),
  forwardBodyPitch: parseFloat(src.match(/double forwardBodyPitch = ([-0-9.]+);/)[1]),
};
const BRANCH = branchParams();

console.log('=== 解析到的常量（ArmPose.java）===');
console.log(`  臂长 ${ARM.ARM_LENGTH.toFixed(4)} 格 / 球拍延伸 ${ARM.PADDLE_LENGTH} 格`);
console.log(`  待机 pitch ${C.basePitch}° / 引拍 ${C.windupPitch}° / 前挥 ${C.forwardPitch}°`);
console.log('  各击球类型的引拍差异：');
for (const [k, v] of Object.entries(BRANCH)) {
  console.log(`    ${k.padEnd(16)} 引拍pitch=${String(v.windupPitch).padStart(6)} 前挥pitch=${String(v.forwardPitch).padStart(6)} 引拍bodyPitch=${v.windupBodyPitch}`);
}

/** 手相对躯干中心的位置（模型坐标：y 向下、z 向后） */
function handLocal(pitchDeg) {
  const p = (pitchDeg * Math.PI) / 180;
  return { y: ARM.ARM_LENGTH * Math.cos(p), z: ARM.ARM_LENGTH * Math.sin(p) };
}
/** 球拍中心相对眼睛（近似：躯干中心 ≈ 眼睛下方 0.5 格） */
function paddleOffset(angles) {
  const h = handLocal(angles.pitch);
  const bodyP = (angles.bodyPitch * Math.PI) / 180;
  const y = h.y * Math.cos(bodyP) - h.z * Math.sin(bodyP);
  const z = h.y * Math.sin(bodyP) + h.z * Math.cos(bodyP);
  const pitchTotal = ((angles.pitch + angles.bodyPitch) * Math.PI) / 180;
  const outY = -ARM.PADDLE_LENGTH * Math.sin(pitchTotal);
  const outZ = ARM.PADDLE_LENGTH * Math.cos(pitchTotal);
  // 游戏坐标：前方 = -z
  return { forward: -(z + outZ), up: -(y + outY) };
}

let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
const f = (n) => (n >= 0 ? '+' : '') + n.toFixed(3);

console.log('\n=== 各相位下球拍中心相对眼睛的位置（forward 正 = 身前，up 正 = 更高）===');
console.log('  击球类型 / 相位            前后(格)   上下(格)   判定');
const rows = {};
for (const [key, b] of Object.entries(BRANCH)) {
  for (const [phase, w, fwd] of [['待机', 0, 0], ['引拍', 1, 0], ['前挥', 0, 1]]) {
    const pitch = C.basePitch + b.windupPitch * w + b.forwardPitch * fwd;
    const bodyPitch = b.windupBodyPitch * w;
    const p = paddleOffset({ pitch, bodyPitch });
    rows[`${key}|${phase}`] = p;
    const tag = p.forward > 0 ? '身前' : '⚠ 身后';
    console.log(`  ${(key + ' ' + phase).padEnd(26)} ${f(p.forward).padStart(8)}   ${f(p.up).padStart(8)}   ${tag}`);
  }
}

console.log('\n=== 判定点合理性断言 ===');
for (const key of Object.keys(BRANCH)) {
  const windup = rows[`${key}|引拍`];
  const forward = rows[`${key}|前挥`];
  check(`${key} 前挥时球拍在身前（可击球）`, forward.forward > 0.1,
    `forward=${f(forward.forward)} 格`);
  check(`${key} 引拍时球拍在身后/更后（引拍成立）`, windup.forward < forward.forward,
    `引拍 ${f(windup.forward)} → 前挥 ${f(forward.forward)}`);
}
check('判定点不会跑到身体后面太多（引拍最深处距眼睛 ≤ 0.9 格）',
  Object.entries(rows).filter(([k]) => k.includes('引拍')).every(([, p]) => p.forward > -0.9),
  `最靠后 ${f(Math.min(...Object.entries(rows).filter(([k]) => k.includes('引拍')).map(([, p]) => p.forward)))} 格`);

console.log('\n=== 用户第 2、3 条：动作差异是否真的体现出来 ===');
const pushW = rows['PUSH_FOREHAND|引拍'];
const loopW = rows['LOOP_FOREHAND|引拍'];
check('搓球引拍比拉球更靠前、更低（搓球是"压低往前推"而不是往后拉）',
  pushW.forward > loopW.forward && pushW.up < rows['PUSH_FOREHAND|待机'].up + 0.05,
  `搓球引拍 forward=${f(pushW.forward)} / 拉球引拍 forward=${f(loopW.forward)}`);
check('削球引拍比拉球更高（削球是"举到肩上"）',
  rows['CHOP_FOREHAND|引拍'].up > loopW.up,
  `削球 ${f(rows['CHOP_FOREHAND|引拍'].up)} / 拉球 ${f(loopW.up)}`);
check('反手拉球与正手拉球参数不同（引拍幅度、前挥角度分开给）',
  BRANCH.LOOP_BACKHAND.windupPitch !== BRANCH.LOOP_FOREHAND.windupPitch
  && BRANCH.LOOP_BACKHAND.forwardPitch !== BRANCH.LOOP_FOREHAND.forwardPitch,
  `正手 引拍${BRANCH.LOOP_FOREHAND.windupPitch}/前挥${BRANCH.LOOP_FOREHAND.forwardPitch} vs ` +
  `反手 引拍${BRANCH.LOOP_BACKHAND.windupPitch}/前挥${BRANCH.LOOP_BACKHAND.forwardPitch}`);
check('反手拉球前挥比正手更"往上拉"（前挥角度更负 = 手抬得更高）',
  BRANCH.LOOP_BACKHAND.forwardPitch < BRANCH.LOOP_FOREHAND.forwardPitch,
  `反手 ${BRANCH.LOOP_BACKHAND.forwardPitch}° / 正手 ${BRANCH.LOOP_FOREHAND.forwardPitch}°`);

console.log(`\n===== ${fails === 0 ? '全部通过 ✅' : fails + ' 项不达标 ❌'} =====`);
process.exit(fails === 0 ? 0 : 1);
