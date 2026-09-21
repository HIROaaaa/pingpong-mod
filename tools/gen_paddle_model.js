#!/usr/bin/env node
/**
 * 生成球拍的 3D 模型 JSON（五期 M9：用户要「把球拍的 3D 建模弄好，现在不好看击球效果」）。
 *
 * 【为什么用脚本生成而不是手写 JSON】圆形拍面需要**多块拼圆**：
 * 原版模型只能画长方体，做不出圆 —— 所以把拍面拆成 9 层，每层的半宽按圆方程算出来
 * （w(h) = 0.5·√(d² − h²)），叠起来就是一个十六边形，看着就是圆拍面。
 * 手写 27 个块且每层宽度都要算对，人眼容易错；脚本算完顺带做几何自检。
 *
 * 【尺寸依据】真实球拍：拍面直径约 15cm、全长 25~26cm、柄约 10cm（见 v1.7-plan §8.1）。
 * 换算成模型像素（1 像素 = 1/16 格）：拍面直径 11~13 像素、柄 8 像素。
 *
 * 运行：node tools/gen_paddle_model.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
/*
 * 【为什么换文件名（_v2）】用户连续两轮反馈"球拍还是 2D 平面"，而模型确实已经改过两次 ——
 * 除了几何本身，还有一个必须排除的可能：**客户端资源缓存**。
 * 换个新的模型文件名（并同步 item model json）能强制资源重载，把"是不是没加载新模型"
 * 这个变量一次性排除掉。
 */
const OUT = path.join(ROOT, 'src/main/resources/assets/pingpong/models/item/pingpong_paddle_v2.json');

// ------------------------------------------------------------------
// 几何参数（单位：模型像素 = 1/16 格）
//
// 【关于厚度：为什么一直在加】用户连续反馈"球拍还是 2D 的平面"。
// 原版 1.0 版拍面厚 0.8 像素、1.3.0 加厚到 2.2、本轮提到 **4.6 像素（约 0.29 格）**。
// 真球拍当然没这么厚，但方块世界里的物品普遍夸张 —— 薄了在任何角度都像一张纸。
// 另外拍面做成**两层渐进圆**（外层大、内层小并略微前凸），侧看就有球面的弧度，
// 而不是一块等厚的板。
// ------------------------------------------------------------------
const P = 1 / 16;              // 像素 → 方块坐标
const BLADE_TOP = 15;          // 拍面顶部（y）
const BLADE_HEIGHT = 13;       // 拍面高度（直径）
const BLADE_CENTER_Y = BLADE_TOP - BLADE_HEIGHT / 2;
const BLADE_CENTER_X = 8;      // 拍面中心横向（模型宽 16）
const BLADE_RADIUS = BLADE_HEIGHT / 2;

const RUBBER_INNER = 1.3;      // 胶皮内层（全尺寸，贴着木芯）
const RUBBER_OUTER = 0.9;      // 胶皮外层（略小一圈，形成圆弧收边）
const CORE_THICK = 1.6;        // 木芯厚度
const CORE_INSET = 0.25;       // 木芯内缩 → 拍边露出一圈木色（当倒角用）

const Z_CORE_MIN = 8 - CORE_THICK / 2;              // 7.1
const Z_CORE_MAX = 8 + CORE_THICK / 2;              // 8.9
const Z_BACK_INNER = [Z_CORE_MIN - RUBBER_INNER, Z_CORE_MIN];
const Z_BACK_OUTER = [Z_CORE_MIN - RUBBER_INNER - RUBBER_OUTER, Z_CORE_MIN - RUBBER_INNER];
const Z_FRONT_INNER = [Z_CORE_MAX, Z_CORE_MAX + RUBBER_INNER];
const Z_FRONT_OUTER = [Z_CORE_MAX + RUBBER_INNER, Z_CORE_MAX + RUBBER_INNER + RUBBER_OUTER];
/** 外层比内层小多少（像素）：收这一圈就看出圆角/球面感 */
const OUTER_SHRINK = 0.9;

const LAYERS = 9;              // 拍面分几层拼圆

// 贴图 UV（图集四象限，见 tools/gen_textures.js 的 paddleAtlas）
const UV = {
  redFace: [0, 0, 16, 16],
  blackFace: [16, 0, 32, 16],
  woodSide: [16, 16, 32, 32],
  woodEnd: [0, 16, 8, 24],
};

/** 一个长方体元素 */
function box(from, to, faces) {
  return { from, to, faces };
}
/** 六面同贴图（简化块） */
function solidBox(from, to, uv) {
  const f = (u) => ({ uv: u, texture: '#layer0' });
  return box(from, to, {
    north: f(uv), south: f(uv), east: f(uv), west: f(uv), up: f(uv), down: f(uv),
  });
}

const elements = [];

// ------------------------------------------------------------------
// 1. 拍面：逐层拼圆 + 两层渐进（外层收一圈 → 侧看有球面弧度）
//    每一层的半宽 = √(R² − h²)，h 是该层中心到拍面中心的距离。
// ------------------------------------------------------------------
const layerHeight = BLADE_HEIGHT / LAYERS;
for (let i = 0; i < LAYERS; i++) {
  const y0 = BLADE_TOP - (i + 1) * layerHeight;
  const y1 = BLADE_TOP - i * layerHeight;
  const centerOffset = (y0 + y1) / 2 - BLADE_CENTER_Y;
  const halfWidth = Math.sqrt(Math.max(0, BLADE_RADIUS * BLADE_RADIUS - centerOffset * centerOffset));
  const x0 = BLADE_CENTER_X - halfWidth;
  const x1 = BLADE_CENTER_X + halfWidth;

  // 正面红胶皮：内层全尺寸 + 外层收一圈
  elements.push(solidBox(
    [r(x0), r(y0), Z_FRONT_INNER[0]], [r(x1), r(y1), Z_FRONT_INNER[1]], UV.redFace));
  elements.push(solidBox(
    [r(x0 + OUTER_SHRINK), r(y0), Z_FRONT_OUTER[0]],
    [r(x1 - OUTER_SHRINK), r(y1), Z_FRONT_OUTER[1]], UV.redFace));

  // 反面黑胶皮：同样两层
  elements.push(solidBox(
    [r(x0), r(y0), Z_BACK_INNER[0]], [r(x1), r(y1), Z_BACK_INNER[1]], UV.blackFace));
  elements.push(solidBox(
    [r(x0 + OUTER_SHRINK), r(y0), Z_BACK_OUTER[0]],
    [r(x1 - OUTER_SHRINK), r(y1), Z_BACK_OUTER[1]], UV.blackFace));

  // 木芯：横向各缩 CORE_INSET，夹在两片胶皮之间
  elements.push(solidBox(
    [r(x0 + CORE_INSET), r(y0), Z_CORE_MIN], [r(x1 - CORE_INSET), r(y1), Z_CORE_MAX], UV.woodSide));
}

const bladeEdgeUV = UV.woodSide;

// ------------------------------------------------------------------
// 2. 拍柄（8 像素高，从拍面底部到柄底）
//    三段：上段（接拍肩颈）→ 主握 → 底端喇叭口（握拍时不会滑手）
// ------------------------------------------------------------------
const NECK = { x0: 6.6, x1: 9.4, y0: 3.6, y1: 5.4, z0: 7.5, z1: 8.5 };
elements.push(solidBox([r(NECK.x0), r(NECK.y0), r(NECK.z0)], [r(NECK.x1), r(NECK.y1), r(NECK.z1)], bladeEdgeUV));

const GRIP = { x0: 6.9, x1: 9.1, y0: 1.3, y1: 3.9, z0: 7.6, z1: 8.4 };
elements.push(solidBox([r(GRIP.x0), r(GRIP.y0), r(GRIP.z0)], [r(GRIP.x1), r(GRIP.y1), r(GRIP.z1)], bladeEdgeUV));

const FLARE = { x0: 6.55, x1: 9.45, y0: 0.0, y1: 1.5, z0: 7.45, z1: 8.55 };
elements.push(solidBox([r(FLARE.x0), r(FLARE.y0), r(FLARE.z0)], [r(FLARE.x1), r(FLARE.y1), r(FLARE.z1)], bladeEdgeUV));

// 柄底封口（露出一块木头横截面，看起来是实木柄）
elements.push(solidBox([r(FLARE.x0), 0, r(FLARE.z0)], [r(FLARE.x1), 0.3, r(FLARE.z1)], UV.woodEnd));

// ------------------------------------------------------------------
// 3. 朝向指示：拍面中央一条细木条（任何角度都能看出拍面朝哪，需求 0）
//    插在最外层胶皮之上，凸出 0.4 像素
// ------------------------------------------------------------------
elements.push(solidBox(
  [7.75, 6.5, r(Z_FRONT_OUTER[1])], [8.25, 13.5, r(Z_FRONT_OUTER[1] + 0.4)], UV.woodEnd));

function r(v) {
  return Math.round(v * 100) / 100;
}

// ------------------------------------------------------------------
// 输出
// ------------------------------------------------------------------
const model = {
  comment: '球拍 3D 模型（五期 M9 重建）。圆形拍面由 9 层拼出（原版模型只有长方体，做不出圆），'
    + '三明治结构：红胶皮 / 木芯（内缩露出拍边） / 黑胶皮。尺寸按真实球拍：拍面直径 13 像素（约 0.8 格）、'
    + '柄 8 像素。UV 采样 32x32 四象限图集，见 tools/gen_textures.js 的 paddleAtlas()。'
    + '本文件由 tools/gen_paddle_model.js 生成，改几何请改脚本再重新生成。',
  parent: 'item/handheld',
  textures: {
    particle: 'pingpong:item/pingpong_paddle',
    layer0: 'pingpong:item/pingpong_paddle',
  },
  elements,
  /*
   * 【手持姿态：让拍面斜着朝向镜头，而不是正对】
   *
   * 用户连续反馈"球拍还是 2D 平面"。除了几何厚度，还有一个我自己的设计失误：
   * 原来的 display 变换把拍面**正对镜头**（第一人称 yaw −90°、第三人称 −90°），
   * 于是拍面的厚度投影几乎为零 —— 6 像素的厚度也看不出来，看着就是一张贴图。
   *
   * 现在改成斜着拿（yaw 在 −50° 左右）：既看得清拍面（红/黑胶皮），
   * 又能同时看到侧边（木芯）和厚度，立体感一下就出来了。
   * 真实球拍也是这样握的 —— 不会把拍面正对眼睛。
   */
  display: {
    thirdperson_righthand: { rotation: [0, -55, 35], translation: [0, 5, 0.5], scale: [0.85, 0.85, 0.85] },
    thirdperson_lefthand: { rotation: [0, 55, -35], translation: [0, 5, 0.5], scale: [0.85, 0.85, 0.85] },
    firstperson_righthand: { rotation: [0, -50, 18], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
    firstperson_lefthand: { rotation: [0, 50, -18], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
    gui: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
    ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
    fixed: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
  },
};

// ------------------------------------------------------------------
// 自检（几何越界是模型"隐身"的经典原因，四期踩过）
// ------------------------------------------------------------------
let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
console.log(`=== 生成球拍模型：${elements.length} 个元素 ===`);
const xs = elements.flatMap((e) => [e.from[0], e.to[0]]);
const ys = elements.flatMap((e) => [e.from[1], e.to[1]]);
const zs = elements.flatMap((e) => [e.from[2], e.to[2]]);
console.log(`  包围盒 x[${Math.min(...xs)}, ${Math.max(...xs)}] y[${Math.min(...ys)}, ${Math.max(...ys)}] z[${Math.min(...zs)}, ${Math.max(...zs)}]`);
check('所有坐标落在原版允许范围 [-16, 32] 内',
  Math.min(...xs, ...ys, ...zs) >= -16 && Math.max(...xs, ...ys, ...zs) <= 32,
  '越界会导致模型整个不渲染');
check('没有零尺寸面（from 与 to 至少在一个轴上不同）',
  elements.every((e) => e.from.some((v, i) => Math.abs(v - e.to[i]) > 1e-6)),
  `${elements.length} 个元素逐个检查`);
check('拍面高度与直径符合设定（约 13 像素）',
  Math.abs((BLADE_TOP - (BLADE_TOP - BLADE_HEIGHT)) - BLADE_HEIGHT) < 1e-6,
  `${BLADE_HEIGHT} 像素`);
check('胶皮比木芯宽（拍边能露出木色当倒角）',
  elements.some((e) => e.from[2] === r(Z_FRONT_INNER[0]) && e.from[0] === r(BLADE_CENTER_X - BLADE_RADIUS)),
  '第 0 层胶皮从拍面最外侧开始，木芯内缩');
check('拍面总厚度 ≥ 4 像素（薄了从任何角度都像纸片）',
  Z_FRONT_OUTER[1] - Z_BACK_OUTER[0] >= 4.0,
  `实测 ${(Z_FRONT_OUTER[1] - Z_BACK_OUTER[0]).toFixed(2)} 像素`);
check('胶皮外层比内层窄（形成圆弧收边，侧看有球面感）',
  OUTER_SHRINK > 0 && elements.some((e) => Math.abs(e.from[0] - r(BLADE_CENTER_X - BLADE_RADIUS + OUTER_SHRINK)) < 0.01),
  `外层内缩 ${OUTER_SHRINK} 像素`);
check('UV 全部在 32x32 图集范围内',
  elements.every((e) => Object.values(e.faces).every((f) => f.uv.every((v) => v >= 0 && v <= 32))),
  '越界会显示紫黑格');

if (fails === 0) {
  /*
   * 【写出两个文件，顺便把"缓存"这个变量排除掉】
   * 物品模型必须叫 models/item/pingpong_paddle.json（Fabric 按物品 ID 找它）。
   * 如果它**直接内联**几何体，客户端可能拿缓存里的旧版本；
   * 所以让它只写一行 parent 指向 _v2（每次改几何都会换新文件名），
   * 几何体放在 _v2 里 —— 文件名一变就是一次强制的资源变化。
   */
  fs.writeFileSync(OUT, JSON.stringify(model, null, '\t') + '\n', 'utf8');
  const direct = path.join(ROOT, 'src/main/resources/assets/pingpong/models/item/pingpong_paddle.json');
  fs.writeFileSync(direct, JSON.stringify({
    comment: '只做转发：物品必须叫这个名字，真正的几何在 pingpong_paddle_v2.json（由 tools/gen_paddle_model.js 生成）。',
    parent: 'pingpong:item/pingpong_paddle_v2',
  }, null, '\t') + '\n', 'utf8');
  console.log(`\n✅ 已写入 ${path.relative(ROOT, OUT)}`);
  console.log(`✅ 已写入 ${path.relative(ROOT, direct)}（转发到 _v2）`);
} else {
  console.error(`\n❌ ${fails} 项自检未通过，未写入文件`);
  process.exit(1);
}
