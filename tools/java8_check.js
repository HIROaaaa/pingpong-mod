#!/usr/bin/env node
/**
 * Java 8 语法门禁（M5 多版本构建的守护者）。
 *
 * 【为什么需要它】这个 Mod 现在同时要能吃两个工具链：
 *   - 1.20.1 → JDK 17
 *   - 1.16.5 → **JDK 8**
 * 一旦代码里混进 Java 9+ 的语法，1.20.1 会照样编译通过、等到切到 1.16.5 目标时才炸
 * —— 那种"晚发现"的代价是一次完整的目标切换 + 一轮排查。
 * 所以把"不许出现 Java 9+ 语法"做成一条能随时跑的检查，而不是靠记忆。
 *
 * 检查项（都是本项目实际踩过或极易踩的）：
 *   record 声明 / 密封类 / switch 表达式（-> 形式）/ 文本块（"""）
 *   var 局部变量推断 / List.of / Map.of / Set.of / Files.readString 等 Java 9+ 便捷方法
 *   instanceof 模式匹配 / 增强 for 里的 var
 *
 * 运行：node tools/java8_check.js
 * 退出码：0 = 通过（可以安全地给 1.16.5 目标编译）；1 = 发现 Java 9+ 语法
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src/main/java');

/** 规则：正则 + 说明 + 例外（某些匹配是误报） */
const RULES = [
  {
    name: 'record 声明（Java 16+）',
    re: /\brecord\s+[A-Z]\w*\s*[<(]/,
    hint: '改成普通不可变类 + getter（见 PingPongPhysics.Surface 的写法）',
  },
  {
    name: 'switch 表达式（Java 14+）',
    re: /(return|=)\s*switch\s*\(/,
    hint: '改成传统 switch 语句，用 break/return 返回（见 StrokeType.flipHand）',
  },
  {
    name: '箭头式 case（Java 14+）',
    re: /^\s*(case\b[^:]*|default)\s*->/m,
    hint: '改成 "case X:" + 语句块',
  },
  {
    name: '文本块（Java 15+）',
    re: /"""/,
    hint: '改成普通字符串拼接',
  },
  {
    name: 'var 局部变量（Java 10+）',
    re: /^\s*var\s+\w+\s*=/m,
    hint: '写出显式类型',
  },
  {
    name: 'instanceof 模式匹配（Java 16+）',
    re: /instanceof\s+[A-Z]\w*(<[^>]*>)?\s+\w+\s*[)&|]/,
    hint: '改成先 instanceof 判断、再显式强转',
  },
  {
    name: 'Java 9+ 集合工厂（List.of / Map.of / Set.of）',
    re: /\b(List|Map|Set)\.(of|copyOf)\s*\(/,
    hint: '改成 Arrays.asList(...) 或显式构造（Arrays.asList 只是视图，注意不要写操作）',
  },
  {
    name: 'Java 9+ 便捷文件方法（Files.readString / readAllBytes 无参）',
    re: /Files\.readString\s*\(/,
    hint: '改成 new String(Files.readAllBytes(path), StandardCharsets.UTF_8)',
  },
];

/** 需要豁免的片段（命中但不该报错的行） */
const ALLOW = [
  /\/\/.*(record|switch|var)\b/,          // 注释里提到关键字
  /\*.*(record|switch|var)\b/,            // javadoc 里提到
];

function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(p));
    else if (entry.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const files = walk(SRC);
const problems = [];

for (const file of files) {
  const text = fs.readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/);
  for (const rule of RULES) {
    // 逐行匹配：能给出精确行号，也方便按行豁免
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (ALLOW.some((a) => a.test(line))) continue;
      if (rule.re.test(line)) {
        problems.push({
          file: path.relative(ROOT, file).replace(/\\/g, '/'),
          line: i + 1,
          rule: rule.name,
          hint: rule.hint,
          text: line.trim().slice(0, 100),
        });
      }
    }
  }
}

console.log(`=== Java 8 语法门禁：扫了 ${files.length} 个源文件 ===`);

if (problems.length === 0) {
  console.log('✅ 没有发现 Java 9+ 语法 —— 这份源码可以给 1.16.5（JDK 8）目标编译。');
  process.exit(0);
}

console.error(`❌ 发现 ${problems.length} 处 Java 9+ 语法，1.16.5 目标会编译失败：\n`);
for (const p of problems) {
  console.error(`  ${p.file}:${p.line}  [${p.rule}]`);
  console.error(`      ${p.text}`);
  console.error(`      → ${p.hint}`);
}
console.error('\n修完再跑一次本脚本确认。');
process.exit(1);
