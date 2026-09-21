#!/usr/bin/env node
/**
 * 乒乓球动作姿态的**方向求解器**（五期 M6 第二轮）。
 *
 * 【为什么写这个】用户实测反馈「只是手臂微微动了一下」「正反手好像反了」。
 * 前一版的角度是"凭直觉凑"的，`tools/pose_direction_check.js` 一算就发现方向反了：
 * 引拍时手跑到了身前、前挥时手跑到了身后 —— 幅度改多大都只是"更明显地把动作做反"。
 *
 * 这个脚本反过来做：**先说清手该去哪（目标位置），再反解出该给多少度**。
 * 目标位置按真人打乒乓球的动作定：
 *   待机  手在身前偏外、抬起来（球拍举在身前看得见）
 *   引拍  手往**后上方**拉（拍子收到耳后/肩后）
 *   前挥  手往**前下方**扫到身体中线附近（拍子打到身前）
 *
 * 旋转模型与 MC 一致：ModelPart 先 roll(Z) → yaw(Y) → pitch(X)，手臂 pivot 在肩，
 * 手在臂下端。右手臂 pivot x 比左手大 10，所以两支手臂天生对称。
 *
 * 运行：node tools/pose_solver.js        （打印推荐角度，供写回 Java）
 */
'use strict';

const rad = (d) => (d * Math.PI) / 180;

// 手臂几何：肩到手的长度 11；右手臂 pivot 相对躯干中心的 x = -5，左手臂 = +5
const ARM_LEN = 11;
const SHOULDER_X = { right: -5, left: +5 };
const HAND_DOWN = { x: 0, y: ARM_LEN, z: 0 };   // 手在臂下端（模型 y 向下为正）

function rotX(v, a) { const c = Math.cos(a), s = Math.sin(a); return { x: v.x, y: v.y * c - v.z * s, z: v.y * s + v.z * c }; }
function rotY(v, a) { const c = Math.cos(a), s = Math.sin(a); return { x: v.x * c + v.z * s, y: v.y, z: -v.x * s + v.z * c }; }
function rotZ(v, a) { const c = Math.cos(a), s = Math.sin(a); return { x: v.x * c - v.y * s, y: v.x * s + v.y * c, z: v.z }; }

/**
 * MC 的实际应用顺序（见 ModelPart.translate/rotate）：
 *   逐级 rotate(...) → 从**根往叶**作用，所以父级累积 = pitch(X) · yaw(Y) · roll(Z)。
 * 手臂相对肩只有**一个**点 (0,11,0)，所以手的位置 = 肩位置 + (pitch·yaw·roll)(0,11,0)。
 */
function rotateMc(v, pitchDeg, yawDeg, rollDeg) {
  return rotX(rotY(rotZ(v, rad(rollDeg)), rad(yawDeg)), rad(pitchDeg));
}

/**
 * 手臂的**两段旋转**模型 —— 这是让"横向扫动"真正可用的关键。
 *
 * 单段旋转有个死结：(0,11,0) 绕 Y 转不改变 x 分量，所以 yaw 转多少手都不会往身体中线靠，
 * 手臂只能在一个竖直平面里前后摆 —— 那正是用户看到"只是微微动了一下"的原因之一。
 *
 * 真人挥拍其实是两段：**先把大臂抬到某个仰角（pitch），再由肩/躯干把整条臂横扫过去（yaw）**。
 * 所以这里用 `yaw · (pitch · 手臂)`：先 pitch 定仰角，再绕竖直轴扫向身体中线。
 * 这个模型与 Java 里「paddleArm.pitch/yaw/roll 各自设值」不完全等价 ——
 * Java 侧是先转 roll 再 yaw 再 pitch（顺序相反），所以下面解出的角度要按 Java 的顺序复核。
 */
function armRotate(pitchDeg, yawDeg, rollDeg) {
  // 先 pitch（抬/放），再 yaw（横扫），最后 roll（转臂）
  let v = rotX(HAND_DOWN, rad(pitchDeg));
  v = rotY(v, rad(yawDeg));
  v = rotZ(v, rad(rollDeg));
  return v;
}

/** 给定角度，算手相对躯干中心的位置（躯干不转时） */
function handPos(side, pitchDeg, yawDeg, rollDeg) {
  const rel = armRotate(pitchDeg, yawDeg, rollDeg);
  return { x: SHOULDER_X[side] + rel.x, y: 2 + rel.y, z: rel.z };
}
/** 加上躯干转体（绕 Y） */
function withBodyYaw(p, bodyYawDeg) {
  const r = rotY(p, rad(bodyYawDeg));
  return { x: r.x, y: r.y, z: r.z };
}

// ------------------------------------------------------------------
// 手的目标位置（手工定，单位=模型像素，1 像素 = 1/16 格）
//   坐标系：x 右为负（左臂在 +x 侧）、y 向下为正（0 = 躯干中部）、z 后方为正
//   玩家面朝 -z，所以"身前" = z 负值
//
// 【可达性约束（求解器教我的）】手臂只能绕肩**旋转**，长度固定，且旋转矩阵里
// x 分量与角度无关（rotY/rotZ/rotX 对 (0,11,0) 的作用都不改变 x）——也就是说
// 手始终落在「过肩部的 YZ 平面」上的圆里，x 恒等于肩的 x。
// 所以目标点必须定在这两个约束内：|手 yz 偏移| ≈ 11，x ≈ ±5。
// 横向位置靠**躯干转体**（bodyYaw）来补，这也是真人打球时身体会转的原因。
// ------------------------------------------------------------------
const TARGET = {
  // 待机：手抬到身前偏下方一点（球拍举在身前看得见，但不是僵直地前伸）
  ready: { x: -5.0, y: 5.0, z: -9.8 },
  // 引拍：手拉到身后偏上（拍子收到耳后/肩后）
  windup: { x: -5.0, y: 0.0, z: +11.0 },
  // 前挥：手扫到正前方（拍子打到身前，正对球）
  forward: { x: -5.0, y: 3.0, z: -10.6 },
};

/** 网格搜索：找最接近目标位置的角度组合 */
function solve(side, target, opts = {}) {
  const pitchRange = opts.pitch || [-100, 100];
  const yawRange = opts.yaw || [-70, 70];
  const rollValues = opts.roll || [0];
  let best = null;
  for (let p = pitchRange[0]; p <= pitchRange[1]; p += 1) {
    for (let y = yawRange[0]; y <= yawRange[1]; y += 1) {
      for (const r of rollValues) {
        const pos = handPos(side, p, y, r);
        const d = Math.hypot(pos.x - target.x, pos.y - target.y, pos.z - target.z);
        if (best === null || d < best.d) best = { d, p, y, r, pos };
      }
    }
  }
  return best;
}

console.log('=== 手臂长度的合理性检查 ===');
const straight = handPos('right', 0, 0, 0);
console.log(`  垂手时手的位置：x=${straight.x.toFixed(2)} y=${straight.y.toFixed(2)} z=${straight.z.toFixed(2)}`);
console.log('  （y = 2 + 11 = 13，即手在躯干下方 13 像素 —— 与原版站立时手臂位置一致）\n');

console.log('=== 反解各相位所需角度（右手臂 / 正手侧）===');
console.log('  相位      目标位置 x/y/z           解出 punch/yaw    求得的实际位置        误差');
const solved = {};
for (const [name, t] of Object.entries(TARGET)) {
  const s = solve('right', t);
  solved[name] = s;
  console.log(
    `  ${name.padEnd(9)} ${t.x.toFixed(1).padStart(5)}/${t.y.toFixed(1).padStart(5)}/${t.z.toFixed(1).padStart(5)}` +
    `   pitch=${String(s.p).padStart(4)} yaw=${String(Math.round(s.y)).padStart(4)}` +
    `   ${s.pos.x.toFixed(1).padStart(5)}/${s.pos.y.toFixed(1).padStart(5)}/${s.pos.z.toFixed(1).padStart(5)}` +
    `   ${s.d.toFixed(2)}`);
}

// ------------------------------------------------------------------
// 换算成 Java 常量的增量（Java 里是"待机 + 增量×进度"的形式）
// ------------------------------------------------------------------
const ready = solved.ready;
const windup = solved.windup;
const forward = solved.forward;

console.log('\n=== 写回 Java 的推荐常量（相对待机的增量）===');
console.log(`  READY_ARM_PITCH   = ${ready.p.toFixed(1)}F`);
console.log(`  READY_ARM_YAW     = ${ready.y.toFixed(1)}F`);
console.log(`  WINDUP_PITCH      = ${(windup.p - ready.p).toFixed(1)}F     // 待机→引拍：pitch 增量`);
console.log(`  WINDUP_YAW        = ${(windup.y - ready.y).toFixed(1)}F     // 待机→引拍：yaw 增量（乘手型符号）`);
console.log(`  FORWARD_PITCH     = ${(forward.p - ready.p).toFixed(1)}F     // 待机→前挥：pitch 增量`);
console.log(`  FORWARD_YAW       = ${(forward.y - ready.y).toFixed(1)}F     // 待机→前挥：yaw 增量（乘手型符号）`);

// ------------------------------------------------------------------
// 方向断言：这些是"必须成立"的语义，写回 Java 后由 pose_direction_check.js 守住
// ------------------------------------------------------------------
let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
console.log('\n=== 方向断言（解出的角度是否真的符合"引拍在后、前挥在前"）===');
check('① 引拍时手在身后（z 为正）', windup.pos.z > 0, `z=${windup.pos.z.toFixed(2)}`);
check('② 前挥时手在身前（z 为负）', forward.pos.z < 0, `z=${forward.pos.z.toFixed(2)}`);
check('③ 引拍时手比待机更高', windup.pos.y < ready.pos.y, `待机 y=${ready.pos.y.toFixed(2)} → 引拍 y=${windup.pos.y.toFixed(2)}`);
check('④ 引拍→前挥的手部行程足够（≥ 10 像素）',
  Math.hypot(forward.pos.x - windup.pos.x, forward.pos.y - windup.pos.y, forward.pos.z - windup.pos.z) >= 10,
  `行程 ${Math.hypot(forward.pos.x - windup.pos.x, forward.pos.y - windup.pos.y, forward.pos.z - windup.pos.z).toFixed(2)} 像素`);

console.log('\n=== 反手（左臂）镜像验证：同一组角度应当得到 x 镜像的位置 ===');
const backWindup = handPos('left', windup.p, -windup.y, 0);
const backForward = handPos('left', forward.p, -forward.y, 0);
console.log(`  正手引拍 x=${windup.pos.x.toFixed(2)} / 反手引拍 x=${backWindup.x.toFixed(2)}`);
console.log(`  正手前挥 x=${forward.pos.x.toFixed(2)} / 反手前挥 x=${backForward.x.toFixed(2)}`);
/*
 * 【断言写法】左右肩的 x 分别是 -5 与 +5，所以"镜像"的判据是两者**之和等于 0**
 * （第一版写成"等于 10"是错的：右肩 -5 的镜像就是 +5，相加为 0）。
 */
check('⑤ 正反手引拍位置互为镜像（x 之和 ≈ 0）',
  Math.abs(windup.pos.x + backWindup.x) < 0.01,
  `和 = ${(windup.pos.x + backWindup.x).toFixed(3)}（右肩 -5 的镜像是 +5，相加为 0）`);
check('⑥ 正反手前挥位置互为镜像（x 之和 ≈ 0）',
  Math.abs(forward.pos.x + backForward.x) < 0.01,
  `和 = ${(forward.pos.x + backForward.x).toFixed(3)}`);
check('⑦ 正反手不是同一套动作（yaw 取反后 z 也要一致、手确实分处两侧）',
  Math.abs(windup.pos.z - backWindup.z) < 0.01
  && Math.sign(windup.pos.x) !== Math.sign(backWindup.x),
  `引拍 z：正手 ${windup.pos.z.toFixed(2)} / 反手 ${backWindup.z.toFixed(2)}`);

console.log(`\n===== ${fails === 0 ? '方向全部正确 ✅' : fails + ' 项方向有误 ❌'} =====`);
process.exit(fails === 0 ? 0 : 1);
