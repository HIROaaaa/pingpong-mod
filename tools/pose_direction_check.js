#!/usr/bin/env node
/**
 * 手臂/躯干姿态的**方向与镜像**验证（五期 M6 第二轮）。
 *
 * 【为什么需要】用户实测反馈「正反手好像反了」「只是手臂微微动了一下」。
 * 幅度好办（改数字），但**方向靠手感猜会错**：上一版就是凭直觉写的 pitch 符号，
 * 一算发现"引拍时手跑到了身前、前挥时手跑到了身后" —— 幅度再大也只是更明显地把动作做反。
 *
 * 这里用真实的 3D 旋转（MC 的 Z→Y→X 顺序）+ 模型几何算出手的位置，断言：
 *   待机  手在身前（z < 0）
 *   引拍  手在**身后**（z > 0）—— 用户要的"往后引拍"
 *   前挥  手回到**身前**（z < 0）
 * 并断言正反手互为镜像、摆幅够大、肘部确实有弯曲量。
 *
 * 【常量来源】角度参数在 util/ArmPose.java（与击球判定共用同一套），
 * 缓动/弯曲系数在 client/PingPongModelPose.java。三条脚本各管一段：
 *   本脚本 → 方向对不对；tools/paddle_point_check.js → 判定点合不合理；
 *   tools/pose_solver.js → 目标是"手该在哪"的反向求解工具。
 *
 * 运行：node tools/pose_direction_check.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const poseSrc = read('src/main/java/com/whale/pingpong/client/PingPongModelPose.java');
const armSrc = read('src/main/java/com/whale/pingpong/util/ArmPose.java');

/** 从 ArmPose.anglesFor 抓基准值（注意 roll 那条带 `* handed` 后缀，正则要容忍） */
function armBase(name) {
  const m = armSrc.match(new RegExp(
    String.raw`double\s+` + name + String.raw`\s*=\s*([-0-9.]+)(?:\s*\*\s*handed)?\s*;`));
  if (!m) throw new Error(`ArmPose 里找不到 ${name}`);
  return parseFloat(m[1]);
}
/** 抓某个击球类型分支里的赋值（自动区分正手/反手分支） */
function branch(key, name, backhand = false) {
  const decl = armSrc.slice(armSrc.indexOf('switch (stroke)'), armSrc.indexOf('double w = MathHelper'));
  const m = decl.match(new RegExp(String.raw`case\s+` + key + String.raw`:[\s\S]*?(?=\n\s*case|\n\s*default)`));
  if (!m) throw new Error(`找不到分支 ${key}`);
  return fromBody(m[0], name, backhand);
}

/** default 分支（攻球走这里，没有显式 case） */
function defaultBranch(name) {
  const decl = armSrc.slice(armSrc.indexOf('switch (stroke)'), armSrc.indexOf('double w = MathHelper'));
  const m = decl.match(/default:[\s\S]*$/);
  if (!m) throw new Error('找不到 default 分支');
  return fromBody(m[0], name, false);
}

function fromBody(raw, name, backhand) {
  let body = raw;
  if (/if\s*\(forehand\)/.test(body)) {
    const parts = body.split(/}\s*else\s*{/);
    body = backhand ? (parts[1] || body) : parts[0];
  }
  const mm = body.match(new RegExp(String.raw`\b` + name + String.raw`\s*=\s*([-0-9.]+)`));
  return mm ? parseFloat(mm[1]) : null;
}

const C = {
  basePitch: armBase('pitch'),
  baseRoll: armBase('roll'),
  windupPitch: armBase('windupPitch'),
  windupRoll: armBase('windupRoll'),
  windupBodyYaw: armBase('windupBodyYaw'),
  forwardPitch: armBase('forwardPitch'),
  forwardBodyYaw: armBase('forwardBodyYaw'),
  bendBase: parseFloat(poseSrc.match(/BEND_BASE\s*=\s*([-0-9.]+)F/)[1]),
  bendWindup: parseFloat(poseSrc.match(/BEND_WINDUP\s*=\s*([-0-9.]+)F/)[1]),
};

// ------------------------------------------------------------------
// 模型几何：x 右为负、y 向下为正、z 后方为正；玩家面朝 -z，"身前" = z 负值
// ------------------------------------------------------------------
const SHOULDER = { right: -5 / 16, left: +5 / 16 };
const HAND_DOWN = { x: 0, y: 11 / 16, z: 0 };
const rad = (d) => (d * Math.PI) / 180;
const rotX = (v, a) => ({ x: v.x, y: v.y * Math.cos(a) - v.z * Math.sin(a), z: v.y * Math.sin(a) + v.z * Math.cos(a) });
const rotY = (v, a) => ({ x: v.x * Math.cos(a) + v.z * Math.sin(a), y: v.y, z: -v.x * Math.sin(a) + v.z * Math.cos(a) });
const rotZ = (v, a) => ({ x: v.x * Math.cos(a) - v.y * Math.sin(a), y: v.x * Math.sin(a) + v.y * Math.cos(a), z: v.z });

/** 手相对躯干中心的位置（手臂先 roll 再 pitch，最后叠躯干转体） */
function handPosition(side, pitchDeg, rollDeg, bodyYawDeg) {
  const handed = side === 'right' ? 1 : -1;
  let v = rotZ(HAND_DOWN, rad(rollDeg * handed));
  v = rotX(v, rad(pitchDeg));
  let p = { x: SHOULDER[side] + v.x, y: 2 / 16 + v.y, z: v.z };
  p = rotY(p, rad(bodyYawDeg * handed));
  return p;
}

function phase(name, side) {
  // 攻球走 default 分支（没有显式 case），它代表最基础的平扫动作，用来验方向足够
  const wp = defaultBranch('windupPitch') ?? C.windupPitch;
  const fp = defaultBranch('forwardPitch') ?? C.forwardPitch;
  const wRoll = defaultBranch('windupRoll') ?? C.windupRoll;
  const wBody = defaultBranch('windupBodyYaw') ?? C.windupBodyYaw;
  const fBody = defaultBranch('forwardBodyYaw') ?? C.forwardBodyYaw;

  if (name === '待机') return handPosition(side, C.basePitch, C.baseRoll, 0);
  if (name === '引拍') return handPosition(side, C.basePitch + wp, C.baseRoll + wRoll, wBody);
  return handPosition(side, C.basePitch + fp, C.baseRoll, fBody);
}

let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
const f = (n) => (n >= 0 ? '+' : '') + n.toFixed(2);

console.log('=== 关键参数（从 Java 源码解析）===');
console.log(`  待机 pitch ${C.basePitch}° / 引拍 +${C.windupPitch}° / 前挥 ${C.forwardPitch}°`);
console.log(`  肘部弯曲：基础 ${C.bendBase}° + 引拍 ${C.bendWindup}°`);

console.log('\n=== 各相位下"手"的位置（相对躯干中心；z 负 = 身前、z 正 = 身后）===');
console.log('  相位        正手 x/y/z                 反手 x/y/z                镜像?');
const pos = {};
for (const name of ['待机', '引拍', '前挥']) {
  const r = phase(name, 'right');
  const l = phase(name, 'left');
  pos[name] = { right: r, left: l };
  const fmt = (p) => `${f(p.x).padStart(6)}/${f(p.y).padStart(6)}/${f(p.z).padStart(6)}`;
  console.log(`  ${name.padEnd(10)}  ${fmt(r)}    ${fmt(l)}   ${Math.abs(r.x + l.x) < 0.01 ? '是' : '否'}`);
}

const ready = pos['待机'].right;
const windup = pos['引拍'].right;
const forward = pos['前挥'].right;

console.log('\n=== 方向断言 ===');
check('① 待机时手在身前（z < 0）', ready.z < 0, `z=${f(ready.z)}`);
check('② 引拍时手在**身后**（z > 0）—— 用户要的"往后引拍"', windup.z > 0,
  `待机 ${f(ready.z)} → 引拍 ${f(windup.z)}`);
check('③ 前挥时手回到**身前**（z < 0）', forward.z < 0, `z=${f(forward.z)}`);
check('④ 引拍比待机抬得更高（y 更小）', windup.y < ready.y,
  `待机 y=${f(ready.y)} → 引拍 y=${f(windup.y)}`);
check('⑤ 摆幅够大：引拍→前挥的行程 ≥ 0.6 格',
  Math.hypot(forward.x - windup.x, forward.y - windup.y, forward.z - windup.z) >= 0.6,
  `行程 ${Math.hypot(forward.x - windup.x, forward.y - windup.y, forward.z - windup.z).toFixed(2)} 格`);
check('⑥ 待机→引拍也有明显行程（≥ 0.3 格，蓄力时看得见手在动）',
  Math.hypot(windup.x - ready.x, windup.y - ready.y, windup.z - ready.z) >= 0.3,
  `行程 ${Math.hypot(windup.x - ready.x, windup.y - ready.y, windup.z - ready.z).toFixed(2)} 格`);
check('⑦ 正反手互为镜像（x 之和 ≈ 0）',
  Math.abs(ready.x + pos['待机'].left.x) < 0.01
  && Math.abs(windup.x + pos['引拍'].left.x) < 0.01
  && Math.abs(forward.x + pos['前挥'].left.x) < 0.01,
  `待机 ${f(ready.x + pos['待机'].left.x)} / 引拍 ${f(windup.x + pos['引拍'].left.x)} / 前挥 ${f(forward.x + pos['前挥'].left.x)}`);
check('⑧ 正反手分处身体两侧（右手在 -x、左手在 +x）',
  ready.x < 0 && pos['待机'].left.x > 0,
  `待机：正手 ${f(ready.x)} / 反手 ${f(pos['待机'].left.x)}`);
check('⑨ 肘部有弯曲量（大臂/小臂不是一根直棍）',
  C.bendBase + C.bendWindup > 40,
  `引拍时总弯曲约 ${(C.bendBase + C.bendWindup).toFixed(0)}°`);

console.log(`\n===== ${fails === 0 ? '方向全部正确 ✅' : fails + ' 项方向有误 ❌'} =====`);
process.exit(fails === 0 ? 0 : 1);
