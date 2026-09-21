#!/usr/bin/env node
/**
 * 生成球拍模型（v7）——**修正 UV 映射**。
 *
 * 【这一版修的是什么】
 * 之前拍面总是显示成木头的棕色、只有边缘露出红/黑，根因是 **MC 模型 JSON 的 uv 是"16 单位制"**：
 * uv 范围 0~16 对应**整张贴图**，与贴图实际像素尺寸无关。
 *
 * 我此前把 uv 写成"贴图像素坐标"（例如红胶皮写 [0,0,16,16] 想表示"左上 16×16 象限"），
 * 结果每个面采样的是**整张 32×32 贴图**（缩放了 2 倍）—— 于是看到的是一片混色，
 * 而不是那一块纯红胶皮。
 *
 * 正确做法：把"贴图像素坐标"换算成 16 单位制 —— 除以 (贴图尺寸 / 16)。
 * 32×32 贴图 → 除以 2：
 *   红胶皮  像素 (0,0)-(16,16)  → uv [0,0, 8, 8]
 *   黑胶皮  像素 (16,0)-(32,16) → uv [8,0, 16,8]
 *   木芯    像素 (0,16)-(16,32) → uv [0,8, 8,16]
 *   木芯侧  像素 (16,16)-(32,32)→ uv [8,8, 16,16]
 *
 * 运行：node tools/gen_paddle_v7.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const DIR = path.join(ROOT, 'src/main/resources/assets/pingpong/models/item');
const OUT = path.join(DIR, 'pingpong_paddle_v7.json');

const r = (v) => Math.round(v * 100) / 100;

// ---- 尺寸（模型像素，1 像素 = 1/16 格）----
const CX = 8;
const BLADE_TOP = 14.5;
const BLADE_BOTTOM = 4.5;
const BLADE_H = BLADE_TOP - BLADE_BOTTOM;
const BLADE_CY = (BLADE_TOP + BLADE_BOTTOM) / 2;
const RADIUS = BLADE_H / 2;
const LAYERS = 10;
const RUB = 1.2;
const CORE_HALF = 0.8;
const CORE_INSET = 0.2;
const ATLAS = 32;                     // 贴图 32×32
const U = 16 / ATLAS;                 // 像素 → 16 单位制的换算系数（= 0.5）

// ---- UV（16 单位制）----
const UV = {
  red: [0, 0, 16 * U, 16 * U],              // [0,0,8,8]
  black: [16 * U, 0, 32 * U, 16 * U],       // [8,0,16,8]
  wood: [16 * U, 16 * U, 32 * U, 32 * U],   // [8,8,16,16]
  woodSide: [0, 16 * U, 16 * U, 32 * U],    // [0,8,8,16]
};

const elements = [];
const face = (uv) => ({ uv, texture: '#layer0' });
const box = (x0, y0, z0, x1, y1, z1, uv) => ({
  from: [r(x0), r(y0), r(z0)],
  to: [r(x1), r(y1), r(z1)],
  faces: { north: face(uv), south: face(uv), east: face(uv), west: face(uv), up: face(uv), down: face(uv) },
});

// 拍面：10 层拼圆，每层三块
const layerH = BLADE_H / LAYERS;
for (let i = 0; i < LAYERS; i++) {
  const y0 = BLADE_TOP - (i + 1) * layerH;
  const y1 = BLADE_TOP - i * layerH;
  const off = (y0 + y1) / 2 - BLADE_CY;
  const half = Math.sqrt(Math.max(0, RADIUS * RADIUS - off * off));
  const x0 = CX - half;
  const x1 = CX + half;

  elements.push(box(x0, y0, CORE_HALF, x1, y1, CORE_HALF + RUB, UV.red));                     // 正面
  elements.push(box(x0 + CORE_INSET, y0, -CORE_HALF, x1 - CORE_INSET, y1, CORE_HALF, UV.woodSide)); // 木芯
  elements.push(box(x0, y0, -CORE_HALF - RUB, x1, y1, -CORE_HALF, UV.black));                 // 反面
}

// 手柄：握把 + 底端加宽
elements.push(box(7.0, 0.0, -0.9, 9.0, 5.0, 0.9, UV.wood));
elements.push(box(6.7, 0.0, -1.1, 9.3, 1.2, 1.1, UV.wood));

const model = {
  comment: '球拍模型 v7。修正了 UV：MC 的模型 uv 是 16 单位制（0~16 = 整张贴图），'
    + '之前误写成贴图像素坐标，导致每个面都采样整张图（拍面显示成木色）。'
    + '现在 32×32 贴图的四象限分别换算为 [0,0,8,8] / [8,0,16,8] / [8,8,16,16] / [0,8,8,16]。',
  parent: 'item/handheld',
  gui_light: 'front',
  textures: {
    particle: 'pingpong:item/pingpong_paddle',
    layer0: 'pingpong:item/pingpong_paddle',
  },
  elements,
  display: {
    thirdperson_righthand: { rotation: [0, -55, 35], translation: [0, 2, 0.5], scale: [0.55, 0.55, 0.55] },
    thirdperson_lefthand: { rotation: [0, 55, -35], translation: [0, 2, 0.5], scale: [0.55, 0.55, 0.55] },
    firstperson_righthand: { rotation: [0, -50, 18], translation: [1.13, 3.2, 1.13], scale: [0.5, 0.5, 0.5] },
    firstperson_lefthand: { rotation: [0, 50, -18], translation: [1.13, 3.2, 1.13], scale: [0.5, 0.5, 0.5] },
    gui: { rotation: [20, 35, 0], translation: [0, 0, 0], scale: [0.8, 0.8, 0.8] },
    ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
    fixed: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
  },
};

let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
console.log(`=== 球拍模型 v7：${elements.length} 个元素 ===`);
const all = elements.flatMap((e) => [...e.from, ...e.to]);
check('坐标在 [-16, 32] 内', Math.min(...all) >= -16 && Math.max(...all) <= 32, `范围 [${Math.min(...all)}, ${Math.max(...all)}]`);
check('没有零尺寸面', elements.every((e) => e.from.some((v, i) => Math.abs(v - e.to[i]) > 1e-6)));
check('UV 全部落在 0~16（16 单位制）',
  elements.every((e) => Object.values(e.faces).every((f) => f.uv.every((v) => v >= 0 && v <= 16))),
  `UV 种类: ${[...new Set(elements.flatMap((e) => Object.values(e.faces).map((f) => JSON.stringify(f.uv))))].join(' ')}`);
check('拍面直径 ≤ 11 像素', BLADE_H <= 11, `${BLADE_H} 像素`);

if (fails === 0) {
  fs.writeFileSync(OUT, JSON.stringify(model, null, '\t') + '\n', 'utf8');
  fs.writeFileSync(path.join(DIR, 'pingpong_paddle.json'), JSON.stringify({
    comment: '转发入口：几何在 pingpong_paddle_v7.json（UV 已按 16 单位制修正）。',
    parent: 'pingpong:item/pingpong_paddle_v7',
  }, null, '\t') + '\n', 'utf8');
  console.log(`\n已写入 ${path.relative(ROOT, OUT)}`);
  console.log('已更新 pingpong_paddle.json（转发到 v7）');
} else {
  console.error(`\n${fails} 项自检未通过`);
  process.exit(1);
}
