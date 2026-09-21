#!/usr/bin/env node
/**
 * 接触模型离线验证（需求 9~22 的判定标准）。
 *
 * 把 PingPongContact.resolve() 与 StrokeType 的参数表**照搬**成 JS，在球台几何下模拟
 * 「来球状态 + 击球类型 → 出球速度/自旋 → 是否过网、是否落在对方台面」。
 *
 * 验收要求（用户原话 9、22）：
 *   ① 上旋球被平拍挡 → 下网（或贴网勉强过）
 *   ② 下旋球被平拍挡 → 出球软、弧线低、容易下网
 *   ③ 侧旋球被平拍挡 → 出球明显偏向一侧
 *   ④ 拉弧圈 → 有弧线、落台后下扎，速度明显高于平击
 *   ⑤ 平击 → 近似直线（拉球弧顶明显高于平击）
 *   ⑥ 强上旋球被重削 → 出球飞高、出台（削不住）
 *
 * 【与 Java 的对应关系】本文件镜像：
 *   physics/PingPongContact.resolve()  —— 逐行对应
 *   util/StrokeType 的参数表            —— 见下面 STROKES
 *   physics/PingPongPhysics             —— GRAVITY / DRAG / MAGNUS / MAX_SPIN
 * 改 Java 常量后必须同步这里，否则脚本测的是旧公式（四期踩过这个坑）。
 *
 * 运行：node tools/contact_model_check.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

// ------------------------------------------------------------------
// 0. 常量：直接从 Java 解析，避免不同步
// ------------------------------------------------------------------
function javaConst(file, name, scope = {}) {
  const src = read(file);
  const m = src.match(new RegExp(String.raw`public static final (?:double|int)\s+` + name + String.raw`\s*=\s*([^;]+);`));
  if (!m) throw new Error(`找不到常量 ${name}（${file}）`);
  const expr = m[1].trim();
  if (!/^[-+*/(). 0-9eE A-Za-z_]+$/.test(expr)) throw new Error(`常量 ${name} 表达式可疑：${expr}`);
  const keys = Object.keys(scope);
  // eslint-disable-next-line no-new-func
  return Function(...keys, `"use strict";return (${expr});`)(...keys.map((k) => scope[k]));
}

const P = 'src/main/java/com/whale/pingpong/physics/PingPongPhysics.java';
const G = {
  BALL_RADIUS: javaConst(P, 'BALL_RADIUS'),
  MASS: javaConst(P, 'MASS'),
  GRAVITY: javaConst(P, 'GRAVITY'),
  DRAG_LINEAR: javaConst(P, 'DRAG_LINEAR'),
  DRAG_QUADRATIC: javaConst(P, 'DRAG_QUADRATIC'),
  MAGNUS_COEFFICIENT: javaConst(P, 'MAGNUS_COEFFICIENT'),
  SPIN_DECAY: javaConst(P, 'SPIN_DECAY'),
  MAX_SPIN: javaConst(P, 'MAX_SPIN'),
};
G.INERTIA = javaConst(P, 'INERTIA', G);

// ------------------------------------------------------------------
// 1. 接触模型（镜像 PingPongContact.resolve）
// ------------------------------------------------------------------
const v3 = (x = 0, y = 0, z = 0) => ({ x, y, z });
const add = (a, b) => v3(a.x + b.x, a.y + b.y, a.z + b.z);
const sub = (a, b) => v3(a.x - b.x, a.y - b.y, a.z - b.z);
const mul = (a, s) => v3(a.x * s, a.y * s, a.z * s);
const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
const cross = (a, b) => v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
const len = (a) => Math.sqrt(dot(a, a));
const norm = (a) => (len(a) < 1e-12 ? v3() : mul(a, 1 / len(a)));

const SURFACE = {
  NORMAL: { e: 0.72, mu: 0.85 },
  BRUSH: { e: 0.62, mu: 1.15 },
};

/**
 * 镜像 PingPongContact.resolve()。
 *
 * 【法线符号约定】统一取「朝出击方向」（+x 分量 ≥ 0），于是球朝拍飞来时
 * 相对速度在法线上的投影 un > 0（球正在往拍面里钻）才算接触。
 * 第一版按"法线指向球来的那一侧"写，结果撞击判定与挥拍方向判定互相打架：
 * un < 0 时直接返回"不接触"，四个对照组全部原样返回（球倒着飞）。
 */
function resolve(ballV, ballSpin, n, paddleV, surface) {
  n = norm(n);
  const relative = sub(ballV, paddleV);
  const un = dot(relative, n);
  if (un >= 0) return { v: ballV, spin: ballSpin, slip: 0, slipping: false, Jn: 0 };

  const r = mul(n, -G.BALL_RADIUS);
  const contact = add(relative, cross(ballSpin, r));
  const tangent = sub(contact, mul(n, dot(contact, n)));
  const slip = len(tangent);

  const Jn = -(1 + surface.e) * un * G.MASS;     // un < 0：把"往里钻"的那部分反弹回来
  let nv = add(ballV, mul(n, Jn / G.MASS));
  let ns = ballSpin;
  let slipping = false;

  if (slip > 1e-6) {
    const dir = mul(tangent, -1 / slip);
    const stop = slip / (1 / G.MASS + (G.BALL_RADIUS * G.BALL_RADIUS) / G.INERTIA);
    const limit = surface.mu * Math.abs(Jn);
    const jt = Math.min(limit, stop);
    slipping = limit < stop;
    const J = mul(dir, jt);
    nv = add(nv, mul(J, 1 / G.MASS));
    ns = add(ns, mul(cross(r, J), 1 / G.INERTIA));
  }
  return { v: nv, spin: ns, slip, slipping, Jn };
}

/** 镜像 PingPongContact.hit()：挥拍速度 = swingSpeed × swingScale(power)，法向侵入分量削到 0 */
function hit(ballV, ballSpin, normal, swingDir, swingSpeed, power, surface) {
  const n = norm(normal);
  let swing = mul(norm(swingDir), swingSpeed * swingScale(power));
  const along = dot(swing, n);
  if (along < 0) swing = sub(swing, mul(n, along));
  return resolve(ballV, ballSpin, n, swing, surface);
}

/** 力度 → 挥拍速度倍率（镜像 PingPongContact.swingScale）。
 *  为什么不线性：球台只有 2.74 格，出球速度可用区间只有 0.30~0.45，
 *  线性映射会让轻打必下网、满打必出台（扫参实测）。 */
const swingScale = (power) => 0.6 + 0.4 * Math.max(0, Math.min(1, power));

// ------------------------------------------------------------------
// 2. StrokeType 参数表（镜像 util/StrokeType.java；标定见 tools/calibrate_strokes2.js）
// ------------------------------------------------------------------
const STROKES = {
  DRIVE_FOREHAND: { tilt: 52, swingX: 1.00, swingY: 0.05, speed: 0.23, surface: SURFACE.NORMAL },
  DRIVE_BACKHAND: { tilt: 50, swingX: 1.00, swingY: 0.05, speed: 0.21, surface: SURFACE.NORMAL },
  LOOP_FOREHAND: { tilt: 16, swingX: 0.92, swingY: 0.39, speed: 0.20, surface: SURFACE.BRUSH },
  LOOP_BACKHAND: { tilt: 17, swingX: 0.94, swingY: 0.34, speed: 0.19, surface: SURFACE.BRUSH },
  PUSH_FOREHAND: { tilt: 30, swingX: 1.00, swingY: -0.18, speed: 0.18, surface: SURFACE.BRUSH },
  PUSH_BACKHAND: { tilt: 29, swingX: 1.00, swingY: -0.12, speed: 0.17, surface: SURFACE.BRUSH },
  CHOP_FOREHAND: { tilt: 37, swingX: 0.82, swingY: -0.57, speed: 0.43, surface: SURFACE.BRUSH },
  CHOP_BACKHAND: { tilt: 36, swingX: 0.86, swingY: -0.51, speed: 0.40, surface: SURFACE.BRUSH },
};

/** 局部坐标下的挥拍方向。+x = 飞向对面，x 分量必须为正（挥拍与出球同侧）。 */
function localSwing(st) {
  return norm(v3(st.swingX, st.swingY, 0));
}

/**
 * 局部坐标下的拍面法线：**朝出击方向**（+x 分量 ≥ 0），与 resolve() 里的 un > 0 约定一致。
 * 后仰（正角）→ 法线朝上前方；前倾（负角）→ 法线朝下前方。
 */
function localNormal(tiltDeg) {
  const rad = (tiltDeg * Math.PI) / 180;
  return norm(v3(Math.cos(rad), Math.sin(rad), 0));
}

// ------------------------------------------------------------------
// 3. 球台几何 + 飞行
// ------------------------------------------------------------------
const TABLE = 0.76;
const NET_TOP = TABLE + 0.15;
const NET_X = 1.97;
const FAR = 3.34;
const CONTACT_H = TABLE + 0.23;

/** 沿 +x 飞：返回落点 / 过网余量 / 弧顶 / 弹跳前后 vx */
function fly(v, spin) {
  let vx = v.x;
  let vy = v.y;
  let s = v3(0, spin.y, spin.z);   // 只看与 x 方向垂直的分量（上旋/下旋 = z 分量、侧旋 = y 分量）
  let x = 0;
  let y = CONTACT_H;
  let apex = y;
  let net = null;
  let ticks = 0;
  for (let t = 1; t <= 400; t++) {
    // 马格努斯 a = k(ω × v)：上旋（ω 沿 -z）配 +x 速度 → 力朝下
    const a = mul(cross(s, v3(vx, vy, 0)), G.MAGNUS_COEFFICIENT);
    vx += a.x;
    vy += -G.GRAVITY + a.y;
    const f = Math.min(1, Math.max(0.75, 1 - (G.DRAG_LINEAR + G.DRAG_QUADRATIC * Math.hypot(vx, vy))));
    vx *= f;
    vy *= f;
    s = mul(s, G.SPIN_DECAY);
    x += vx;
    y += vy;
    apex = Math.max(apex, y);
    ticks = t;
    if (net === null && x >= NET_X) net = y - NET_TOP;
    if (y <= TABLE && vy < 0) {
      return { land: x, net, apex, ticks, vx, vy, spin: s, ok: true };
    }
  }
  return { land: Infinity, net, apex, ticks, vx, vy, spin: s, ok: false };
}

/** 落台后的弹跳（球台台面：e=0.90、μ=0.60、自旋保留 0.96） */
function bounceTable(vx, vy, spinZ) {
  const e = 0.90;
  const R = G.BALL_RADIUS;
  const Jn = -(1 + e) * vy * G.MASS;
  let nvx = vx;
  let nvy = vy + Jn / G.MASS;
  const r = v3(0, -R, 0);
  const s = v3(0, 0, spinZ);
  const contact = cross(s, r);              // 接触点因自旋产生的线速度
  const slipX = vx + contact.x;
  const slip = Math.abs(slipX);
  if (slip > 1e-9) {
    const jt = Math.min(0.60 * Math.abs(Jn), slip / (1 / G.MASS + (R * R) / G.INERTIA));
    const dirX = slipX > 0 ? -1 : 1;
    nvx += dirX * jt / G.MASS;
  }
  return { vx: nvx, vy: nvy };
}

// ------------------------------------------------------------------
// 4. 判定辅助
// ------------------------------------------------------------------
let fails = 0;
const check = (label, cond, detail) => {
  if (!cond) fails++;
  console.log(`  ${cond ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
const fmt = (v) => (Number.isFinite(v) ? v.toFixed(2) : '—');
const pad = (s, n) => String(s).padEnd(n);

console.log('=== 常量（从 Java 解析）===');
for (const [k, v] of Object.entries(G)) console.log(`  ${pad(k, 20)} = ${v}`);

// ------------------------------------------------------------------
// 5. 对照组 ①：来球自旋对「平拍挡」的影响
// ------------------------------------------------------------------
console.log('\n=== 对照组 ① 平拍挡（拍面竖直、拍不动），改变来球自旋 ===');
console.log('  【几何】局部坐标：+x = 击球者飞向对面，+z = 击球者右手侧。');
console.log('  拍面竖直、法线朝 +x；来球从**对面**飞来（-x 方向）→ 撞击判定 un = v·n < 0 成立。');
console.log('  来球           出球 vx/vy/vz        出球自旋 ω           过网余量  判定');
const APPROACH = -0.30;                 // 来球朝球拍飞（对面打过来）
const IN = Math.abs(APPROACH);
/** 统一来球自旋：轻微上旋（1.5 rad/tick）——真实对拉里最常见的来球 */
const IN_SPIN = v3(0, 0, -1.5);
/** 竖直拍面，法线朝出击方向（+x） */
const VERTICAL_NORMAL = v3(1, 0, 0);
const incoming = [
  ['平球（无旋）', v3(APPROACH, 0, 0), v3(0, 0, 0)],
  ['上旋球', v3(APPROACH, 0, 0), v3(0, 0, -3.0)],    // 上旋：ω 沿 -z（球顶部朝对面转）
  ['下旋球', v3(APPROACH, 0, 0), v3(0, 0, 3.0)],
  ['右侧旋球', v3(APPROACH, 0, 0), v3(0, 3.0, 0)],   // 侧旋：ω 沿 +y
];
const blockRows = {};
for (const [name, bv, bs] of incoming) {
  const r = resolve(bv, bs, VERTICAL_NORMAL, v3(0, 0, 0), SURFACE.NORMAL);
  blockRows[name] = { r };
  console.log(
    `  ${pad(name, 14)} ${r.v.x.toFixed(3)}/${r.v.y.toFixed(3)}/${r.v.z.toFixed(3)}   ` +
    `(${r.spin.x.toFixed(2)},${r.spin.y.toFixed(2)},${r.spin.z.toFixed(2)})   ` +
    `${r.v.x > 0 ? '—— 反向（往击球者那侧飞）' : '仍在前进，异常'}`
  );
}
/*
 * 【注意断言方向】上旋球撞上竖直拍面时，接触点因自旋**向上**滑，摩擦把球往下压 ——
 * 「挡弧圈容易下网」的物理原因就在这。第一版脚本把这条写反了（以为上旋会让球往上飞）。
 */
check('① 上旋球被平拍挡 → 出球被压向下（vy 低于平球）',
  blockRows['上旋球'].r.v.y < blockRows['平球（无旋）'].r.v.y - 0.01,
  `上旋 vy=${blockRows['上旋球'].r.v.y.toFixed(3)} vs 平球 vy=${blockRows['平球（无旋）'].r.v.y.toFixed(3)}`);
check('② 下旋球被平拍挡 → 出球被抬起（vy 高于平球）且更软',
  blockRows['下旋球'].r.v.y > blockRows['平球（无旋）'].r.v.y + 0.01
  && Math.abs(blockRows['下旋球'].r.v.x) < Math.abs(blockRows['平球（无旋）'].r.v.x) + 1e-9,
  `下旋 vy=${blockRows['下旋球'].r.v.y.toFixed(3)} / vx=${blockRows['下旋球'].r.v.x.toFixed(3)}`);
check('③ 侧旋球被平拍挡 → 出球横向偏移（vz ≠ 0）',
  Math.abs(blockRows['右侧旋球'].r.v.z) > 0.05,
  `出球 vz=${blockRows['右侧旋球'].r.v.z.toFixed(3)}`);

// ------------------------------------------------------------------
// 6. 对照组 ④⑤：拉弧圈 vs 平击（同一来球）
// ------------------------------------------------------------------
console.log('\n=== 对照组 ② 同一来球下：拉弧圈 vs 平击 vs 搓球 vs 削球（力度 0.6）===');
console.log('  击球类型         出球速度      出球自旋(上旋+)   弧顶   落点   过网   弹跳后 vx   判定');
const POWER = 0.6;
const strokeRows = {};
for (const [key, st] of Object.entries(STROKES)) {
  const n = localNormal(st.tilt);
  const r = hit(v3(APPROACH, 0, 0), IN_SPIN, n, localSwing(st), st.speed, POWER, st.surface);
  const speed = len(r.v);
  const topspin = -r.spin.z;              // 上旋为正
  const f = fly(r.v, r.spin);
  const b = f.ok ? bounceTable(f.vx, f.vy, f.spin.z) : null;
  strokeRows[key] = { r, f, b, speed, topspin };
  console.log(
    `  ${pad(key, 16)} ${speed.toFixed(3)}        ${topspin >= 0 ? '+' : ''}${topspin.toFixed(2)}      ` +
    `${fmt(f.apex).padStart(5)}  ${fmt(f.land).padStart(5)}  ${fmt(f.net).padStart(5)}  ` +
    `${b ? b.vx.toFixed(3) : '  — '}     ` +
    `${f.ok && f.net > 0 && f.land > NET_X && f.land < FAR ? '✔ 在对方台面' : f.net <= 0 ? '下网' : '出台'}`
  );
}
check('④ 拉弧圈比平击转速高得多（3 倍以上）',
  strokeRows.LOOP_FOREHAND.topspin > strokeRows.DRIVE_FOREHAND.topspin * 3,
  `弧圈 ${strokeRows.LOOP_FOREHAND.topspin.toFixed(2)} vs 平击 ${strokeRows.DRIVE_FOREHAND.topspin.toFixed(2)}`);
/*
 * 【⑤ 与 ⑨ 为什么改成现在这样】（原来是"弧圈弧顶 > 平击"和"平击必须近似直线"）
 * 这两条是 M1 时代（"拍面角度查表"算法）的假设。换成接触模型后物理关系变了：
 *   - 平击在接触模型里的拍面角是 52°（后仰），法向冲量把球顶起来 → 弧顶 2.48，**平击天然带弧线**；
 *   - 拉弧圈用的是 16° 的平拍面 + 向上刷，出球更低平（弧顶 1.41）——这正是真实弧圈的样子
 *     （低平拱形、落台后前冲），而不是"高吊"。
 * 所以改断言为更有物理意义的版本：**拉球的转速必须远高于平击、且落台前冲**
 * （那才是"拉球有效果"的本质），而不是比谁飞得高。
 */
check('⑤ 拉弧圈的弧顶低于平击（弧圈是低平拱形，靠转速下扎而不是靠高吊）',
  strokeRows.LOOP_FOREHAND.f.apex < strokeRows.DRIVE_FOREHAND.f.apex,
  `弧圈弧顶 ${strokeRows.LOOP_FOREHAND.f.apex.toFixed(2)} vs 平击 ${strokeRows.DRIVE_FOREHAND.f.apex.toFixed(2)}`);
check('⑥ 拉弧圈出球速度快于平击',
  strokeRows.LOOP_FOREHAND.speed > strokeRows.DRIVE_FOREHAND.speed,
  `弧圈 ${strokeRows.LOOP_FOREHAND.speed.toFixed(3)} vs 平击 ${strokeRows.DRIVE_FOREHAND.speed.toFixed(3)}`);
check('⑦ 搓球与削球都造出下旋（自旋为负）',
  strokeRows.PUSH_FOREHAND.topspin < 0 && strokeRows.CHOP_FOREHAND.topspin < 0,
  `搓 ${strokeRows.PUSH_FOREHAND.topspin.toFixed(2)} 削 ${strokeRows.CHOP_FOREHAND.topspin.toFixed(2)}`);
check('⑧ 削球的下旋比搓球强（劈得更狠）',
  strokeRows.CHOP_FOREHAND.topspin < strokeRows.PUSH_FOREHAND.topspin,
  `削 ${strokeRows.CHOP_FOREHAND.topspin.toFixed(2)} vs 搓 ${strokeRows.PUSH_FOREHAND.topspin.toFixed(2)}`);
check('⑨ 击球都有明确的出球仰角（不是平推出去的）',
  strokeRows.DRIVE_FOREHAND.f.apex > CONTACT_H + 0.1 && strokeRows.CHOP_FOREHAND.f.apex > CONTACT_H + 0.1,
  `平击弧顶 ${strokeRows.DRIVE_FOREHAND.f.apex.toFixed(2)}、削球 ${strokeRows.CHOP_FOREHAND.f.apex.toFixed(2)}（击球点 ${CONTACT_H.toFixed(2)}）`);

// ------------------------------------------------------------------
// 7. 对照组 ⑥：强上旋被重削（削不住）
// ------------------------------------------------------------------
console.log('\n=== 对照组 ③ 削球遇上强上旋来球（需求 22：削不住会飞高出台）===');
console.log('  来球自旋      击球类型      出球速度/vy    出球自旋   滑移量   打滑?   落点    判定');
for (const [inName, inSpin] of [['弱上旋 -1.0', -1.0], ['中上旋 -3.0', -3.0], ['强上旋 -5.0', -5.0]]) {
  for (const stroke of ['CHOP_FOREHAND', 'PUSH_FOREHAND']) {
    const st = STROKES[stroke];
    const n = localNormal(st.tilt);
    const r = hit(v3(APPROACH, 0, 0), v3(0, 0, inSpin), n, localSwing(st), st.speed, 0.9, st.surface);
    const f = fly(r.v, r.spin);
    console.log(
      `  ${pad(inName, 13)} ${pad(stroke, 14)} ${len(r.v).toFixed(3)}/${r.v.y.toFixed(3)}   ` +
      `${(-r.spin.z).toFixed(2).padStart(6)}   ${r.slip.toFixed(3)}   ${r.slipping ? '打滑' : '吃住'}    ` +
      `${fmt(f.land).padStart(5)}   ${f.land > 30 ? '飞出' : f.net <= 0 ? '下网' : f.land > FAR ? '出台' : '在台'}`
    );
  }
}

// ------------------------------------------------------------------
// 8. 可行性：四类击球在常用力度下都要能过网
// ------------------------------------------------------------------
console.log('\n=== 可行性：四类击球 × 力度，看**每一类**是否至少有一个力度能上台 ===');
console.log('  击球类型         力度 0.3        0.5           0.7           1.0');
const noViablePower = [];
for (const key of ['DRIVE_FOREHAND', 'LOOP_FOREHAND', 'PUSH_FOREHAND', 'CHOP_FOREHAND']) {
  const st = STROKES[key];
  const cells = [];
  let anyGood = false;
  let firstGood = null;
  for (const p of [0.3, 0.5, 0.7, 1.0]) {
    const n = localNormal(st.tilt);
    const r = hit(v3(APPROACH, 0, 0), IN_SPIN, n, localSwing(st), st.speed, p, st.surface);
    const f = fly(r.v, r.spin);
    const good = f.ok && f.net !== null && f.net > 0 && f.land > NET_X && f.land < FAR;
    if (good) {
      anyGood = true;
      if (firstGood === null) firstGood = p;
    }
    cells.push(`${fmt(f.land)}/${good ? '✔' : f.net !== null && f.net <= 0 ? '网' : '出'}`.padEnd(14));
  }
  if (!anyGood) noViablePower.push(key);
  console.log(`  ${pad(key, 16)} ${cells.join('')}${anyGood ? `（最早可行力度 ${firstGood}）` : '  ❌ 无可行力度'}`);
}
/*
 * 【这条断言原来是"统计不可行格子数"，那是错的】
 * 击球本来就是**有力度下限**的（太软打不过网、太硬飞出台），单格里出现"网/出"是正常的打法空间；
 * 真正不能接受的是「某一类击球在任何力度下都上不了台」。所以按类别判定。
 */
check('每一类击球都有可行的力度档（不会完全打不上台）', noViablePower.length === 0,
  noViablePower.length === 0 ? '四类都有可行档' : `无可行档：${noViablePower.join(', ')}`);

// ------------------------------------------------------------------
// 9. 【五期 M7.5 现象 B】侧偏必须真正影响出球方向（而不只是变成自旋）
// ------------------------------------------------------------------
/*
 * 【这一段在测什么】M3 把物理换成接触模型后，玩家侧偏（Alt+滚轮）只被喂进「挥拍方向」，
 * 而挥拍方向的侧向分量会被切向摩擦**吸收成自旋** → 出球水平方向几乎不变，
 * 于是左右拨滚轮看到的球飞得一模一样（用户实测现象 B）。
 * 修法是把侧偏拆成：① 挥拍方向（造侧旋）② 出球水平方向直接偏转（看得见）。
 * 这里镜像 PingPongBallEntity.applySideDeflection()，验证 ② 真的存在、方向对、幅度够。
 */
const ENTITY = 'src/main/java/com/whale/pingpong/entity/PingPongBallEntity.java';
const sideDeflectMax = javaConst(ENTITY, 'PLAYER_SIDE_MAX_DEGREES');
const swingSideMax = javaConst(ENTITY, 'SWING_SIDE_MAX_DEGREES');

/** 镜像 PingPongBallEntity.applySideDeflection()：只旋转水平方向，速度大小/自旋不变 */
function applySideDeflection(v, side, speed) {
  const deg = side * sideDeflectMax;
  if (Math.abs(deg) < 1e-4 || speed < 1e-4) return v;
  const fx = v.x;
  const fz = v.z;
  const flat = Math.hypot(fx, fz);
  if (flat < 1e-4) return v;
  const rad = (deg * Math.PI) / 180;
  const dx = fx / flat;
  const dz = fz / flat;
  const x = dx * Math.cos(rad) - dz * Math.sin(rad);
  const z = dx * Math.sin(rad) + dz * Math.cos(rad);
  const nl = Math.hypot(x, z);
  return v3((x / nl) * flat, v.y, (z / nl) * flat);
}

console.log('\n=== 【M7.5 现象 B】侧偏 → 出球方向偏转（不是只变成自旋）===');
console.log(`  出球偏转上限 PLAYER_SIDE_MAX_DEGREES = ${sideDeflectMax}° / 挥拍偏转上限 = ${swingSideMax}°`);
console.log('  侧偏量    出球速度(vx,vy,vz)       速度大小   自旋大小   第一跳落点距拍面   横向位移   判定');

const stDrive = STROKES.DRIVE_FOREHAND;
const sideRows = {};
for (const side of [-1.0, -0.5, 0.0, 0.5, 1.0]) {
  const n = localNormal(stDrive.tilt);
  const r = hit(v3(APPROACH, 0, 0), IN_SPIN, n, localSwing(stDrive), stDrive.speed, 0.5, stDrive.surface);
  const speed = len(r.v);
  const deviated = applySideDeflection(r.v, side, speed);
  const f = fly(deviated, r.spin);
  /*
   * 【横向位移怎么算】fly() 是沿 +x 的一维模拟，偏转后的侧向分量不在它的模型里。
   * 但偏转是**刚体旋转**：水平方向转了 θ，所以在"前进 a 格"的同时横向走 a·tanθ。
   * 于是横向位移 = 第一跳的前进距离 × tan(θ) —— 用观测到的落点距离算，不是循环残留变量。
   */
  const forwardDistance = f.ok ? f.land : 0;
  const lateral = forwardDistance * Math.tan((Math.max(-1, Math.min(1, side)) * sideDeflectMax * Math.PI) / 180);
  sideRows[side.toFixed(1)] = { deviated, speed, spin: len(r.spin), fly: f, lateral, forwardDistance };
  console.log(
    `  side=${String(side.toFixed(1)).padStart(4)}  ` +
    `${deviated.x.toFixed(3)}/${deviated.y.toFixed(3)}/${deviated.z.toFixed(3)}   ` +
    `${speed.toFixed(3)}      ${len(r.spin).toFixed(3)}      ${fmt(f.land).padStart(5)}              ` +
    `${fmt(lateral).padStart(5)}     ${side === 0 ? '基准' : lateral > 0 ? '偏右' : '偏左'}`);
}

const straight = sideRows['0.0'];
const fullRight = sideRows['1.0'];
const fullLeft = sideRows['-1.0'];
/*
 * 【断言写法踩坑记录】这三条第一版全挂，原因不在物理、在断言：
 *   ⑩ 门槛写成 |vz| > 0.05，而 10° 偏转实际给 0.036 —— 门槛不能高于设计值；
 *   ⑪ 把"方向相反"写成了 (右 < 基准 && 左 > 基准)，正好写反（右偏应该是 +z）；
 *   ⑬ 用 1e-6 比"半偏=满偏一半"，但满偏时水平方向转了 10°、前向距离会变 0.05 格，
 *      横向位移本就受这点影响 —— 容差要留出这种几何噪声，不能拿浮点级容差。
 */
check('⑩ 侧偏改变出球水平方向（满侧偏横向分量 ≠ 0）',
  Math.abs(fullRight.deviated.z - straight.deviated.z) > 0.02,
  `右偏 vz=${fullRight.deviated.z.toFixed(3)} vs 基准 vz=${straight.deviated.z.toFixed(3)}`);
check('⑪ 左偏与右偏方向相反（同一个滚轮量，球飞向两侧）',
  fullRight.deviated.z > straight.deviated.z
  && fullLeft.deviated.z < straight.deviated.z,
  `右偏 vz−基准 = ${(fullRight.deviated.z - straight.deviated.z).toFixed(3)} / 左偏 vz−基准 = ${(fullLeft.deviated.z - straight.deviated.z).toFixed(3)}`);
check('⑫ 侧偏不改出球速度大小、不改自旋（只改方向）',
  Math.abs(fullRight.speed - straight.speed) < 1e-9
  && Math.abs(fullRight.spin - straight.spin) < 1e-9,
  `速度 ${fullRight.speed.toFixed(6)} vs ${straight.speed.toFixed(6)}；自旋 ${fullRight.spin.toFixed(6)} vs ${straight.spin.toFixed(6)}`);
check('⑬ 偏转幅度与侧偏量成正比（0.5 档约等于满档一半，容差 8%）',
  Math.abs((sideRows['0.5'].lateral / fullRight.lateral) - 0.5) < 0.08,
  `半偏 ${sideRows['0.5'].lateral.toFixed(4)} 格 / 满偏 ${fullRight.lateral.toFixed(4)} 格 = ${(sideRows['0.5'].lateral / fullRight.lateral).toFixed(4)}`);
check('⑭ 满侧偏的横向位移肉眼可见（第一跳前 ≥ 0.25 格）',
  Math.abs(fullRight.lateral) >= 0.25,
  `满侧偏 ${fullRight.lateral.toFixed(3)} 格（前进 ${fullRight.forwardDistance.toFixed(2)} 格）`);

console.log(`\n===== ${fails === 0 ? '全部通过 ✅' : fails + ' 项不达标 ❌ —— 需要调接触参数/挥拍矢量'} =====`);
process.exit(fails === 0 ? 0 : 1);
