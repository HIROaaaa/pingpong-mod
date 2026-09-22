#!/usr/bin/env node
/**
 * 反手拉球动作反解（用户 2026-09-22 反馈，第二版）。
 *
 * 用户原话：「反手应该让手从胸前开始往正下方拉，现在反手拉球动作和正手一样」。
 *
 * 【第一版搞反了】我先把"手在胸前"理解成"手抬到胸口高度"，解出 windupPitch=+18 /
 * forwardPitch=+96，结果球拍跑到**身后** 0.73 格（paddle_point_check 一跑就 FAIL）。
 * 方向错在：pitch 正值 = 手往身后抬，而"胸前/身前"必须是负值。
 *
 * 【第二版的目标】按"从上往下拉"这条动作轨迹反解：
 *   引拍 = 手抬到**身前上方**（球拍举在胸前高度）
 *   前挥 = 手往**正下方**扫（球拍拉到腹部以下）
 * 两处都在身前 —— 这才是玩家看得见的"从胸前往下拽"。
 *
 * 用法：node tools/_anti_backhand_solve.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const SRC = 'tools/pose_solver.js';
let src = readFileSync(SRC, 'utf8');

// 目标位置单位 = 模型像素（y 向下为正、z 身后为正、玩家面朝 -z）
const TARGETS = `
const TARGET = {
  ready:   { x: -5.0, y:  5.0, z:  -9.8 },
  windup:  { x: -5.0, y: -3.0, z: -10.5 },
  forward: { x: -5.0, y: 10.5, z:  -3.0 },
};`;

src = src.replace(/const TARGET = \{[\s\S]*?\};/, TARGETS);
const cut = src.indexOf('// ------------------------------------------------------------------\n// 换算成 Java 常量的增量');
if (cut > 0) src = src.slice(0, cut);

writeFileSync('tools/_tmp_solve.mjs', src, 'utf8');
console.log(execFileSync('node', ['tools/_tmp_solve.mjs'], { encoding: 'utf8' }));

console.log('=== 换算成 ArmPose 的增量（基准 pitch = -30）===');
console.log('  引拍增量 = 解出的 windup.pitch - (-30)');
console.log('  前挥增量 = 解出的 forward.pitch - (-30)');
