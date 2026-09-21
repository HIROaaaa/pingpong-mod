#!/usr/bin/env node
/**
 * 生成球拍物品模型的**入口 JSON**（几何改由代码绘制）。
 *
 * 【v5：照 L_Ender's Cataclysm 的做法】
 * 用户提示"灾变的 3D 武器是正常的"，于是翻它的源码（`new1.20.1-master`）：
 *
 *   · 物品模型 json 只写三样东西：
 *       `"parent": "minecraft:builtin/entity"`（告诉游戏"几何自己画"）
 *       `"gui_light": "front"`（GUI 里按正面受光渲染）
 *       `"display": {...}`（各场景的旋转/缩放）
 *   · 几何写在 Java 里（它用 `Incinerator_Model` 的 `addBox(...)` 一个个拼）；
 *   · 由自定义渲染器（`CMItemstackRenderer extends BlockEntityWithoutLevelRenderer`）统一绘制。
 *
 * 本项目对应实现：
 *   · 几何 → `client/PaddleItemRenderer`（用原版 `ModelPart.Cuboid` 在代码里拼圆盘 + 手柄）
 *   · 注册 → `BuiltinItemRendererRegistry.INSTANCE.register(...)`（Fabric 的对应入口）
 *
 * 于是不再受"JSON 只能拼长方体"的限制 —— 圆盘轮廓、厚度、手柄都能按像素精确画。
 *
 * 运行：node tools/gen_paddle_entry.js
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'src/main/resources/assets/pingpong/models/item/pingpong_paddle_v5.json');
const DIRECT = path.join(ROOT, 'src/main/resources/assets/pingpong/models/item/pingpong_paddle.json');

const model = {
  comment: '球拍物品模型的入口：几何由 client/PaddleItemRenderer 用代码绘制（照灾变的做法）。'
    + 'display 参考 L_Ender\'s Cataclysm 的武器配置：GUI 里斜俯视 + 小缩放，'
    + '并加 gui_light=front 让正面受光（否则薄件在 GUI 里像一张贴图）。',
  parent: 'minecraft:builtin/entity',
  gui_light: 'front',
  display: {
    gui: { rotation: [-90, -45, -90], translation: [-2.5, -2.5, 0], scale: [0.32, 0.32, 0.32] },
    ground: { rotation: [180, 0, 0], translation: [0, 20, 0], scale: [1, 1, 1] },
    head: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.8, 0.8, 0.8] },
    fixed: { rotation: [0, 90, 0], translation: [0, -4, 0], scale: [0.34, 0.35, 0.35] },
    thirdperson_righthand: { rotation: [0, 180, 0], translation: [0, 4, 2.25], scale: [0.8, 0.8, 0.8] },
    thirdperson_lefthand: { rotation: [0, 180, 0], translation: [0, 4, 2.25], scale: [0.8, 0.8, 0.8] },
    firstperson_righthand: { rotation: [10, -170, 0], translation: [2, 0, 0], scale: [0.35, 0.35, 0.35] },
    firstperson_lefthand: { rotation: [10, -170, 0], translation: [2, 0, 0], scale: [0.35, 0.35, 0.35] },
  },
};

let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};
console.log('=== 生成球拍物品模型入口（几何由代码绘制）===');
check('parent = minecraft:builtin/entity',
  model.parent === 'minecraft:builtin/entity', model.parent);
check('gui_light = front',
  model.gui_light === 'front', String(model.gui_light));
check('display 覆盖常用场景',
  ['gui', 'thirdperson_righthand', 'firstperson_righthand', 'ground', 'fixed'].every((k) => model.display[k]),
  Object.keys(model.display).join(', '));
check('不再输出几何（elements 交给 PaddleItemRenderer）', model.elements === undefined);

if (fails === 0) {
  fs.writeFileSync(OUT, JSON.stringify(model, null, '\t') + '\n', 'utf8');
  fs.writeFileSync(DIRECT, JSON.stringify({
    comment: '转发入口：物品模型必须叫这个名字，内容在 pingpong_paddle_v5.json（几何由代码渲染）。',
    parent: 'pingpong:item/pingpong_paddle_v5',
  }, null, '\t') + '\n', 'utf8');
  console.log(`\n已写入 ${path.relative(ROOT, OUT)}`);
  console.log(`已写入 ${path.relative(ROOT, DIRECT)}（转发到 _v5）`);
} else {
  console.error(`\n${fails} 项自检未通过`);
  process.exit(1);
}
