#!/usr/bin/env node
/**
 * 1.16.5 API 差异扫描（M5 工作清单生成器）。
 *
 * 【为什么先扫描再编译】真的开 1.16.5 目标要下 Gradle 7（117MB）+ 一整套 1.16.5 依赖，
 * 而且编译器的报错是"在映射后的名字上"给出的，第一遍读起来很费劲。
 * 但这两个版本的差异其实是**有限且已知的**（四期已列出六类），用源码扫描就能给出精确工作量：
 * 哪一类差多少处、在哪些文件。先拿到这份清单，再决定怎么开目标最省事。
 *
 * 运行：node tools/api_diff_scan.js
 * 输出：docs/verification/api-diff-1.16.5.md（按差异类别分组 + 每处位置）
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src/main/java');

/** 差异规则：1.20.1 的写法 → 1.16.5 的对应写法 */
const RULES = [
  {
    key: '注册表入口',
    re: /\bRegistries\.(ITEM|BLOCK|ENTITY_TYPE|ITEM_GROUP)\b/,
    from: 'net.minecraft.registry.Registries.ITEM / BLOCK / ENTITY_TYPE / ITEM_GROUP',
    to: 'net.minecraft.util.registry.Registry.ITEM / BLOCK / ENTITY_TYPE / ITEM_GROUP',
    note: '1.16.5 没有 Registries 这个类；改由 util/Registrar 一处适配',
  },
  {
    key: '方块设置构造',
    re: /AbstractBlock\.Settings\.create\s*\(/,
    from: 'AbstractBlock.Settings.create()',
    to: 'AbstractBlock.Settings.of(Material.XX)',
    note: '1.16.5 必须给 Material（木头/金属…），且要额外 import net.minecraft.block.Material',
  },
  {
    key: '玩家眼睛位置',
    re: /\.getEyePos\s*\(/,
    from: 'Entity.getEyePos()',
    to: '没有这个方法：用 new Vec3d(getX(), getEyeY(), getZ())',
    note: '1.16.5 只有 getEyeY()；调用点较多，建议在适配层包一个 eyePos(entity)',
  },
  {
    key: '物品设置',
    re: /new Item\.Settings\s*\(/,
    from: 'new Item.Settings()',
    to: 'new Item.Settings().group(...) —— 1.16.5 用 FabricItemGroupBuilder 而不是 ItemGroupEvents',
    note: '物品栏归属的写法完全不同（ItemGroupEvents 是 1.19.3+ 的 API）',
  },
  {
    key: '创造页签 API',
    re: /FabricItemGroup\b|ItemGroupEvents/g,
    from: 'FabricItemGroup.builder() / ItemGroupEvents.modifyEntriesEvent',
    to: 'FabricItemGroupBuilder.create(id)…build()',
    note: '1.16.5 的实现方式完全不同，需要单独一份 ModItemGroups',
  },
  {
    key: '实体类型构造',
    re: /FabricEntityTypeBuilder/g,
    from: 'FabricEntityTypeBuilder.create(SpawnGroup, factory).dimensions(…).build()',
    to: '1.16.5 同样有 FabricEntityTypeBuilder，但 dimensions 的类型与 build 的泛型不同',
    note: '需按 1.16.5 的签名逐个核对（用 javap 从 1.16.5 的 remapped jar 取签名）',
  },
  {
    key: 'Mixin 目标签名',
    re: /method\s*=\s*"[a-zA-Z]+"|method\s*=\s*"[A-Za-z]+\(/,
    from: 'Camera.update / HeldItemRenderer.renderItem / Mouse.onMouseScroll …',
    to: '1.16.5 的方法名与参数个数都不同（如 HeldItemRenderer 在 1.16.5 是 renderItem 的旧签名）',
    note: '每个 Mixin 都要按 1.16.5 的映射重写一份 —— 这是最费工的一块',
  },
  {
    key: '玩家持久数据',
    re: /writeCustomDataToNbt|readCustomDataFromNbt|getPersistentData/g,
    from: 'Entity.writeCustomDataToNbt(NbtCompound)',
    to: '1.16.5 的 NBT 读写接口名与参数不同（且 NbtCompound 在 1.16.5 叫 CompoundTag）',
    note: '注意 1.16.5 用的还是 Yarn 的 NbtCompound；差异主要在调用点',
  },
  {
    key: '网络注册',
    re: /registerGlobalReceiver|ServerPlayNetworking\.send|PlayerLookup/g,
    from: 'ServerPlayNetworking.registerGlobalReceiver(id, (server,player,handler,buf,sender)->…)',
    to: '1.16.5 的回调参数个数/顺序不同，且 ClientPlayNetworking 多一个 boolean 参数',
    note: '网络层要按版本各写一份适配（业务回调保持不变）',
  },
  {
    key: '按键注册',
    re: /KeyBindingHelper|KeyBinding\(/g,
    from: 'KeyBindingHelper.registerKeyBinding(new KeyBinding(...))',
    to: '1.16.5 同样有 KeyBindingHelper，但 KeyBinding 构造签名不同',
    note: '参数顺序要核对',
  },
];

function walk(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const files = walk(SRC);
/** hit = { key, file, line, text } */
const hits = [];
const countByKey = new Map();

for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  for (const rule of RULES) {
    const re = new RegExp(rule.re.source, rule.re.flags.includes('g') ? rule.re.flags : rule.re.flags + 'g');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (/^\s*(\*|\/\/)/.test(line)) continue;      // 跳过注释行
      re.lastIndex = 0;
      if (re.test(line)) {
        hits.push({ key: rule.key, file: rel, line: i + 1, text: line.trim().slice(0, 110) });
        countByKey.set(rule.key, (countByKey.get(rule.key) || 0) + 1);
      }
    }
  }
}

// ---- 输出 ----
const md = [];
md.push('# 1.16.5 API 差异扫描（M5 工作清单）');
md.push('');
md.push(`> 生成时间：${new Date().toISOString().slice(0, 19).replace('T', ' ')}`);
md.push(`> 扫描范围：${files.length} 个源文件`);
md.push('');
md.push('## 总览');
md.push('');
md.push('| 差异类别 | 命中处数 | 1.20.1 写法 → 1.16.5 写法 |');
md.push('| --- | --- | --- |');
for (const rule of RULES) {
  const n = countByKey.get(rule.key) || 0;
  md.push(`| ${rule.key} | **${n}** | \`${rule.from}\` → \`${rule.to}\` |`);
}
md.push('');
md.push(`**合计 ${hits.length} 处**需要版本分支。`);
md.push('');
md.push('## 明细');
md.push('');
for (const rule of RULES) {
  const list = hits.filter((h) => h.key === rule.key);
  md.push(`### ${rule.key}（${list.length} 处）`);
  md.push('');
  md.push(`- 1.20.1：\`${rule.from}\``);
  md.push(`- 1.16.5：\`${rule.to}\``);
  md.push(`- 备注：${rule.note}`);
  md.push('');
  if (list.length === 0) {
    md.push('（无命中）');
  } else {
    md.push('| 位置 | 代码 |');
    md.push('| --- | --- |');
    for (const h of list.slice(0, 40)) {
      md.push(`| \`${h.file}:${h.line}\` | \`${h.text.replace(/\|/g, '\\|')}\` |`);
    }
    if (list.length > 40) md.push(`| … | 其余 ${list.length - 40} 处略 |`);
  }
  md.push('');
}

const outDir = path.join(ROOT, 'docs/verification');
fs.mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, 'api-diff-1.16.5.md');
fs.writeFileSync(outFile, md.join('\n'), 'utf8');

console.log(`=== 1.16.5 API 差异扫描：${files.length} 个文件 ===`);
for (const rule of RULES) {
  const n = countByKey.get(rule.key) || 0;
  console.log(`  ${String(n).padStart(3)} 处  ${rule.key}`);
}
console.log(`\n合计 ${hits.length} 处。报告已写入 ${path.relative(ROOT, outFile)}`);
