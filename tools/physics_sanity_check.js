/*
 * 物理手感自查脚本：纯 JS 复刻 PingPongPhysics + 出球逻辑，
 * 不启动 Minecraft 就能验证「自旋是否真的改变了飞行轨迹与弹跳方向」。
 *
 * 运行：node tools/physics_sanity_check.js
 *
 * 注意：这里的常数是 PingPongPhysics.java / PingPongBallEntity.java 的镜像，
 *       改手感时两边要一起改（脚本末尾会用断言卡住明显不合理的参数）。
 */
const R = 0.14;
const M = 1.0;
const I = (2 / 3) * M * R * R;
const G = 0.030;
const DRAG_LINEAR = 0.010;
const DRAG_QUAD = 0.020;
const K_MAGNUS = 0.005;
const SPIN_DECAY = 0.988;
const RESTITUTION = 0.72;
const FRICTION = 0.65;
const SPIN_RETAIN = 0.85;
const MAX_SPIN = 5.5;
const MAX_TILT_DEGREES = 22.0;
const BASE_HIT_SPEED = 0.85;

const v3 = (x = 0, y = 0, z = 0) => ({ x, y, z });
const add = (a, b) => v3(a.x + b.x, a.y + b.y, a.z + b.z);
const sub = (a, b) => v3(a.x - b.x, a.y - b.y, a.z - b.z);
const mul = (a, s) => v3(a.x * s, a.y * s, a.z * s);
const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
const cross = (a, b) => v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
const len = (a) => Math.sqrt(dot(a, a));
const norm = (a) => (len(a) < 1e-9 ? v3() : mul(a, 1 / len(a)));

/** PingPongPhysics.bounce 的镜像 */
function bounce(v, spin, n) {
  n = norm(n);
  const vn = dot(v, n);
  if (vn > 0) return { v, spin };
  const Jn = -(1 + RESTITUTION) * vn * M;
  let nv = add(v, mul(n, Jn / M));

  const r = mul(n, -R);
  const contact = add(v, cross(spin, r));
  const tangent = sub(contact, mul(n, dot(contact, n)));
  const slip = len(tangent);

  let ns = spin;
  if (slip > 1e-9) {
    const dir = mul(tangent, -1 / slip);
    const stopImpulse = slip / (1 / M + (R * R) / I);
    const jt = Math.min(FRICTION * Math.abs(Jn), stopImpulse);
    const J = mul(dir, jt);
    nv = add(nv, mul(J, 1 / M));
    ns = add(spin, mul(cross(r, J), 1 / I));
  }
  ns = mul(ns, SPIN_RETAIN);
  const l = len(ns);
  return { v: nv, spin: l > MAX_SPIN ? mul(ns, MAX_SPIN / l) : ns };
}

/** PingPongBallEntity.hitByPaddle 里「拍面角度 → 出球方向 + 自旋」的镜像 */
function paddleHit(tilt, sideTilt) {
  // 视线水平朝 +x，pitch = 0；tilt > 0 = 后仰 = 抬高出球角
  const pitchRad = ((0 - tilt * MAX_TILT_DEGREES) * Math.PI) / 180;
  const dir = norm(v3(Math.cos(pitchRad), -Math.sin(pitchRad), 0));
  const flat = norm(v3(dir.x, 0, dir.z));
  const topspinAxis = norm(cross(v3(0, 1, 0), flat));
  const spin = add(mul(topspinAxis, tilt * MAX_SPIN), mul(v3(0, 1, 0), sideTilt * MAX_SPIN));
  return { velocity: mul(dir, BASE_HIT_SPEED), spin };
}

/** 从 1 格高、水平出球开始模拟；返回落点、最高点、弹跳前后速度 */
function simulate(velocity, spin, maxBounces = 1) {
  let p = v3(0, 1.0, 0);
  let v = velocity;
  let s = spin;
  let apex = p.y;
  let bounces = 0;
  for (let t = 1; t <= 600; t++) {
    const acc = add(v3(0, -G, 0), mul(cross(s, v), K_MAGNUS));
    v = add(v, acc);
    v = mul(v, Math.max(0.75, 1 - (DRAG_LINEAR + DRAG_QUAD * len(v))));
    p = add(p, v);
    s = mul(s, SPIN_DECAY);
    apex = Math.max(apex, p.y);

    if (p.y <= R && v.y < 0) {
      p = v3(p.x, R, p.z);
      const vBefore = { ...v };
      const res = bounce(v, s, v3(0, 1, 0));
      v = res.v;
      s = res.spin;
      bounces++;
      if (bounces >= maxBounces) {
        return { t, x: p.x, z: p.z, y: p.y, apex, vBefore, vAfter: v, spinAfter: s };
      }
    }
  }
  return { t: 600, x: p.x, z: p.z, y: p.y, apex, vBefore: v, vAfter: v, spinAfter: s, timeout: true };
}

const cases = [
  ['上旋 (滚轮上)', 1, 0],
  ['平拍', 0, 0],
  ['下旋 (滚轮下)', -1, 0],
  ['侧旋 (Alt+滚轮)', 0, 1],
];

console.log('=== 飞行：同样力度、不同拍面角度的第一次落点 ===');
console.log('（水平视线朝 +x，出球速度 0.85 格/tick，起始高度 1.0 格）\n');
console.log('对照组 = 同一出射角度但完全没有自旋，用来单独看「马格努斯」做了多少事。\n');
const results = {};
for (const [name, tilt, side] of cases) {
  const { velocity, spin } = paddleHit(tilt, side);
  const withSpin = simulate(velocity, spin);
  const noSpin = simulate(velocity, v3(0, 0, 0)); // 同角度、零自旋
  results[name] = { ...withSpin, noSpinX: noSpin.x };
  console.log(
    `${name.padEnd(18)} 落点 x=${withSpin.x.toFixed(2)}（零自旋同角度 = ${noSpin.x.toFixed(2)}）` +
    `  z=${withSpin.z.toFixed(2)}  最高 ${withSpin.apex.toFixed(2)}  ${withSpin.t} tick  ` +
    `弹跳前 vx=${withSpin.vBefore.x.toFixed(2)} vy=${withSpin.vBefore.y.toFixed(2)}  ` +
    `弹跳后 vx=${withSpin.vAfter.x.toFixed(2)} vy=${withSpin.vAfter.y.toFixed(2)}`
  );
}

const top = results['上旋 (滚轮上)'];
const flat = results['平拍'];
const back = results['下旋 (滚轮下)'];
const side = results['侧旋 (Alt+滚轮)'];

// ---- 隔离实验：出射角度完全相同，只改自旋，单独看马格努斯 / 弹跳摩擦的效果 ----
const H = (elevDeg) => {
  const r = (elevDeg * Math.PI) / 180;
  return v3(Math.cos(r) * BASE_HIT_SPEED, Math.sin(r) * BASE_HIT_SPEED, 0);
};
// 上旋轴 = up × 水平方向，此处水平方向为 +x，所以上旋 = -z
const TOP_SPIN = v3(0, 0, -MAX_SPIN);
const BACK_SPIN = v3(0, 0, MAX_SPIN);

const horNoSpin = simulate(H(0), v3(0, 0, 0));
const horTop = simulate(H(0), TOP_SPIN);
const horBack = simulate(H(0), BACK_SPIN);
const chopNoSpin = simulate(H(22), v3(0, 0, 0));
const chopBack = simulate(H(22), BACK_SPIN);

console.log('\n=== 隔离实验（出射角度固定，只改自旋）===');
console.log(`水平出球   零自旋落点 ${horNoSpin.x.toFixed(2)}  上旋 ${horTop.x.toFixed(2)}  下旋 ${horBack.x.toFixed(2)}`);
console.log(`仰角 22°   零自旋落点 ${chopNoSpin.x.toFixed(2)}  下旋(削球) ${chopBack.x.toFixed(2)}`);
console.log(`弹跳：上旋 vx ${horTop.vBefore.x.toFixed(2)} → ${horTop.vAfter.x.toFixed(2)}` +
	`   下旋 vx ${horBack.vBefore.x.toFixed(2)} → ${horBack.vAfter.x.toFixed(2)}`);

const checks = [
  ['上旋球比同角度零自旋落得更近（马格努斯下压）', horTop.x < horNoSpin.x - 0.3],
  ['下旋球比同角度零自旋飞得更远（球发飘）', horBack.x > horNoSpin.x + 0.3],
  ['削球（仰角 22° + 下旋）比同角度零自旋飞得更远', chopBack.x > chopNoSpin.x + 0.3],
  ['上旋球弹跳后向前加速（前冲弧圈）', top.vAfter.x > top.vBefore.x],
  ['下旋球弹跳后被摩擦力明显减速（搓球）', back.vAfter.x < back.vBefore.x * 0.7],
  ['弹跳后自旋方向不变、强度下降', Math.sign(back.spinAfter.z) === Math.sign(paddleHit(-1, 0).spin.z) && Math.abs(back.spinAfter.z) < MAX_SPIN],
  ['侧旋让球横向偏移', Math.abs(side.z) > 0.3],
  ['上旋球出手更高、下旋球更平（拍面角度生效）', top.apex > 1.5 && back.t < flat.t],
  ['没有任何一球飞出 30 格（防滑翔机）', [top, flat, back, horBack, chopBack].every((r) => r.x < 30)],
  ['所有球都真的落地了（没超时）', [top, flat, back, horBack, chopBack].every((r) => !r.timeout)],
];

console.log('');
let allPass = true;
for (const [name, ok] of checks) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
  if (!ok) allPass = false;
}
console.log(allPass ? '\n全部通过 ✅' : '\n有断言失败 ❌ 需要调参');
process.exit(allPass ? 0 : 1);
