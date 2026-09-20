#!/usr/bin/env node
/**
 * 验证「引拍 → 击球点圆弧」（需求 12）与「台内伸手」（需求 19）。
 *
 * 镜像 util/TableGeometry 的圆弧公式，打印引拍进度 0→1 时击球点相对眼睛的位移，
 * 并断言：
 *   ① 引拍越深 → 击球点越靠后（背离球台方向）且越靠下；
 *   ② 正手位移幅度明显大于反手（用户要求 12：「正手和反手移动的幅度也不一样」）；
 *   ③ 击球点是**绕肘画弧**而不是直线后移（正手圆弧角度 70°，反手 40°）；
 *   ④ 台内时击球点额外向球台方向伸出 0.25 格。
 *
 * 运行：node tools/stroke_point_check.js
 */
'use strict';

// ---- 常量（镜像 TableGeometry）----
const PADDLE_HEIGHT = 0.45;
const FORWARD = 0.55;
const SIDE_OUT = 0.38;
const FOREHAND_ARC_RADIUS = 0.45;
const ELBOW_BACK = 0.25;
const BACKHAND_ARC_RADIUS = 0.28;
const BACKHAND_BACK = 0.12;
const IN_TABLE_REACH = 0.25;
const IN_TABLE_DISTANCE = 1.15;
const TABLE_HALF_ALONG = 1.5;
const TABLE_HALF_ACROSS = 1.0;

/** 镜像 TableGeometry.paddlePoint（以眼睛为原点、forward = +x、side = +y） */
function paddleOffset(hand, windUp, inTable) {
  const p = Math.max(0, Math.min(1, windUp));
  const sideSign = hand === 'FOREHAND' ? 1 : -1;
  let backOut;
  let drop;
  let sideOut = SIDE_OUT * sideSign;
  if (hand === 'FOREHAND') {
    const angle = (70 * p * Math.PI) / 180;
    backOut = ELBOW_BACK * p + FOREHAND_ARC_RADIUS * Math.sin(angle);
    drop = PADDLE_HEIGHT - FOREHAND_ARC_RADIUS * (1 - Math.cos(angle)) * 0.6;
    sideOut += FOREHAND_ARC_RADIUS * Math.sin(angle) * 0.35 * sideSign;
  } else {
    const angle = (40 * p * Math.PI) / 180;
    backOut = BACKHAND_BACK * p + BACKHAND_ARC_RADIUS * Math.sin(angle);
    drop = PADDLE_HEIGHT - BACKHAND_ARC_RADIUS * (1 - Math.cos(angle)) * 0.5;
    sideOut -= BACKHAND_ARC_RADIUS * Math.sin(angle) * 0.15 * sideSign;
  }
  const reach = inTable ? IN_TABLE_REACH : 0;
  // forward 分量：FORWARD − backOut + reach（越大越靠球台）
  return { forward: FORWARD - backOut + reach, side: sideOut, up: drop };
}

/** 镜像 TableGeometry.inTable：玩家到台缘外接矩形的水平距离 < 1.15 就算台内 */
function inTable(px, pz) {
  const dx = Math.abs(px);
  const dz = Math.abs(pz);
  const outsideX = Math.max(0, dx - TABLE_HALF_ALONG);
  const outsideZ = Math.max(0, dz - TABLE_HALF_ACROSS);
  return Math.hypot(outsideX, outsideZ) < IN_TABLE_DISTANCE;
}

let fails = 0;
const check = (label, cond, detail) => {
  if (!cond) fails++;
  console.log(`  ${cond ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
const pad = (s, n) => String(s).padEnd(n);

console.log('=== 引拍进度 → 击球点位移（相对眼睛；forward 越大越靠球台）===');
console.log('  手型     引拍     forward     side      高度    相对无引拍的后移量');
for (const hand of ['FOREHAND', 'BACKHAND']) {
  const base = paddleOffset(hand, 0, false);
  for (const p of [0, 0.25, 0.5, 0.75, 1.0]) {
    const o = paddleOffset(hand, p, false);
    const back = base.forward - o.forward;
    console.log(
      `  ${pad(hand, 9)} ${p.toFixed(2)}   ${o.forward.toFixed(3).padStart(7)}  ${o.side.toFixed(3).padStart(7)}  ` +
      `${o.up.toFixed(3).padStart(6)}   ${back.toFixed(3).padStart(6)} 格`
    );
  }
}

const fhFull = paddleOffset('FOREHAND', 1, false);
const bhFull = paddleOffset('BACKHAND', 1, false);
const fhBase = paddleOffset('FOREHAND', 0, false);
const bhBase = paddleOffset('BACKHAND', 0, false);
const fhBack = fhBase.forward - fhFull.forward;
const bhBack = bhBase.forward - bhFull.forward;

console.log('\n=== 断言 ===');
check('① 引拍到底时击球点明显后移（正手 > 0.4 格）', fhBack > 0.4, `正手后移 ${fhBack.toFixed(3)} 格`);
check('② 引拍到底时击球点下沉', fhFull.up < fhBase.up - 0.05,
  `${fhBase.up.toFixed(3)} → ${fhFull.up.toFixed(3)}`);
check('③ 正手引拍幅度明显大于反手（用户要求 12）', fhBack > bhBack * 1.5,
  `正手 ${fhBack.toFixed(3)} vs 反手 ${bhBack.toFixed(3)} 格`);
/*
 * ④「绕肘画弧」的判定：如果只是直线后移，那么 side 位移应该与 forward 位移成正比（直线）。
 * 圆弧的特征是 side 与 forward 的比值随角度变化 —— 这里检查中途点是否偏离直线插值足够多。
 */
{
  const mid = paddleOffset('FOREHAND', 0.5, false);
  const t = (fhBase.forward - mid.forward) / fhBack;          // 后移进度
  const linearSide = fhBase.side + (fhFull.side - fhBase.side) * t;
  const deviation = Math.abs(mid.side - linearSide);
  check('④ 击球点走的是圆弧而不是直线（中途点偏离直线插值）', deviation > 0.005,
    `偏离 ${deviation.toFixed(4)} 格（圆弧特征）`);
}

console.log('\n=== 台内判定（需求 19）===');
console.log('  玩家位置(相对台心)     到台缘距离   台内?   击球点前伸');
for (const [px, pz] of [[0, 0], [2.0, 0], [2.4, 0], [2.6, 0], [3.0, 0], [1.8, 1.6]]) {
  const dx = Math.abs(px);
  const dz = Math.abs(pz);
  const outside = Math.hypot(Math.max(0, dx - TABLE_HALF_ALONG), Math.max(0, dz - TABLE_HALF_ACROSS));
  const it = inTable(px, pz);
  const o = paddleOffset('FOREHAND', 0, it);
  console.log(
    `  (${px.toFixed(1)}, ${pz.toFixed(1)})`.padEnd(24) +
    `${outside.toFixed(2)} 格`.padEnd(14) + `${it ? '是' : '否'}`.padEnd(8) +
    `${o.forward.toFixed(3)}`
  );
}
check('⑤ 贴着台缘（0.43 格）算台内、伸手 0.25 格', inTable(2.2, 0) && !inTable(3.0, 0),
  `(2.2,0) 台内=${inTable(2.2, 0)}，(3.0,0) 台内=${inTable(3.0, 0)}`);
check('⑥ 台内时击球点比台外更靠球台', paddleOffset('FOREHAND', 0, true).forward > paddleOffset('FOREHAND', 0, false).forward,
  `${paddleOffset('FOREHAND', 0, false).forward.toFixed(3)} → ${paddleOffset('FOREHAND', 0, true).forward.toFixed(3)}`);

console.log(`\n===== ${fails === 0 ? '全部通过 ✅' : fails + ' 项不达标 ❌'} =====`);
process.exit(fails === 0 ? 0 : 1);
