#!/usr/bin/env node
/**
 * 把 CHANGELOG 里 1.9.0 ~ 1.9.22 共 23 条中间版本记录合并成「v1.9.0（就地重做）」一条，
 * 原始条目不丢，整体归档到 docs/changelog-archive/v1.9.x-details.md。
 *
 * 背景（用户决定，2026-09-21）：动作系统 M6 与球拍建模 M9 是同一批开发里互相纠缠的两条线，
 * 在那天被拆成 1.9.0 ~ 1.9.24 二十多个版本号去发本地 jar 实测，版本线被切得太碎。
 * 用户拍板：合回一个版本，就用 v1.9.0 这个号，GitHub 上的 v1.9.0/v1.9.1 一并撤下重发。
 *
 * 用法：node tools/merge_changelog_190.js [--dry]
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const CHANGELOG = path.join(ROOT, 'CHANGELOG.md');
const ARCHIVE = path.join(ROOT, 'docs', 'changelog-archive', 'v1.9.x-details.md');
const DRY = process.argv.includes('--dry');

const src = fs.readFileSync(CHANGELOG, 'utf8');

// 1.9 段落的真实边界：从第一条 1.9 标题，到下一个非 1.9 的标题之前
const startRe = /^## \[1\.9\.\d+\]/m;
const startMatch = startRe.exec(src);
if (!startMatch) throw new Error('CHANGELOG 里找不到 1.9.x 标题');

const afterStart = src.slice(startMatch.index + 1);
const nextMatch = /^## \[(?!1\.9\.)\d+\.\d+\.\d+\]/m.exec(afterStart);
if (!nextMatch) throw new Error('找不到 1.9 段落之后的版本标题');

const bodyStart = startMatch.index;
const bodyEnd = startMatch.index + 1 + nextMatch.index;
const oldBlock = src.slice(bodyStart, bodyEnd).replace(/\s+$/, '');

// 段落自检：确认真的覆盖 1.9.0 ~ 1.9.22 全部 23 条
const versions = [...oldBlock.matchAll(/^## \[(1\.9\.\d+)\]/gm)].map((m) => m[1]);
const uniq = [...new Set(versions)];
if (uniq.length !== 23) {
  throw new Error(`1.9 段落应含 23 个不同版本号，实际 ${uniq.length}：${uniq.join(',')}`);
}
const expected = Array.from({ length: 23 }, (_, i) => `1.9.${i}`);
const missing = expected.filter((v) => !uniq.includes(v));
if (missing.length) throw new Error(`缺失版本号：${missing.join(',')}`);

const merged = `## [1.9.0] - 2026-09-21（**未提交**）

**动作系统（M6）与球拍建模（M9）合并版** —— 这一天里动作和球拍互相纠缠，被拆成 1.9.0 ~ 1.9.24
二十多个版本号反复发本地 jar 实测；本版把它们合回**一个版本号 v1.9.0**，版本线只保留这一条
（原本已发布的 v1.9.0 / v1.9.1 一并撤下重发）。

逐条开发记录没有丢：完整过程归档在 [\`docs/changelog-archive/v1.9.x-details.md\`](docs/changelog-archive/v1.9.x-details.md)。

### 动作：把「玩家骨骼 + 力矩弯曲」这条路真正走通

- **取消原版左键攻击**：左键只驱动物理挥拍状态机，不再先播一次原版攻击动画。
- **真实肘关节**：手臂几何切两段、加前臂（\`ForearmPart\`），而不是靠顶点变形——后者在
  playerAnimator 静默降级时完全不生效，是"加了弯曲代码却没效果"的真因。
- **弯曲生效的前提**：playerAnimator 在静态初始化时检测 bendy-lib，没装就把 bend 换成空实现，
  **不报错、什么都不弯**。现在会先探测再决定是否调用，并写进诊断输出。
- **动作不再僵硬**：相位切换从 if/else 分段改成 smoothstep 交叉淡入淡出；缓动从"按帧收敛"改成
  "每 1/20 秒收敛多少"，30fps 与 240fps 不再差出 8 倍。
- **正反手真镜像**、**蓄力即引拍**、**判定点跟随动画实际拍位**、**姿态按玩家 UUID 隔离**（联机不再互相覆写）。
- 修掉"多出来一个身体"——其实是头发层跟着头一起偏移。

### 球拍：从"纸片/木色"到能渲染出厚度

- 多层拼圆拍面 + 三明治拍身（红胶皮 / 木芯 / 黑胶皮），拍面 3D 有厚度。
- **修掉贴图全错的根因**：MC 模型 UV 是 **16 单位制**（0~16 = 整张贴图），此前当成贴图像素坐标用，
  导致每个面都在采样整张图、拍面显示成木色。
- 试过 \`builtin/entity\` + 代码渲染（\`BuiltinItemRenderer\`）这条路，渲染器确实被调用、几何也对，
  但**屏幕上连纯色测试方块都不可见**，已回退到 JSON 模型（代码保留未注册）。
- **已知未完成**：握持角度仍偏侧、看到的多是木色（用户实测判定「还是搞不好」，本线暂停）。

### 开发工具（本轮顺带建立，之后排查都靠它）

- \`/pingpong diag\`：一次打出 mod 版本 / 主手是否球拍 / Mixin 注入命中次数 / 持拍帧数 /
  bendy-lib 是否可用 / bend 成功失败次数 / 最近异常——把不可见的中间状态变成可读的数字。
- \`/pingpong bend <度数>\`：游戏内实时调弯矩，不再靠发版试错。
- **开发端自动截图**：\`runClient\` 直接进世界，开自检后自动派发球拍、切创造、截图到 \`run/screenshots/\`
  ——"改完让用户重启游戏再看"的等待就此终结。

**依赖提醒**：客户端需装 playerAnimator（硬依赖）；**想看到手臂弯曲还必须装 bendy-lib**（48KB，client-only）。

`;

const newSrc = src.slice(0, bodyStart) + merged + src.slice(bodyEnd);

const archiveText = `# v1.9.x 中间版本开发记录（归档）

这里是从 \`CHANGELOG.md\` 移出来的 **v1.9.0 ~ v1.9.22** 逐条版本记录，时间都是 2026-09-21。

**为什么要归档**：动作系统（M6）与球拍建模（M9）那一天的迭代被切成了二十多个版本号，
用户 2026-09-21 拍板「改球拍和动作的版本合到一个版本，之前那些中间版本都删掉」，
于是合并成 \`CHANGELOG.md\` 里的单条 **v1.9.0（就地重做）**，逐条过程留在这里备查。

原样保留，未做任何改写。

---

${oldBlock}
`;

if (DRY) {
  console.log(`[dry] 1.9 段落：${oldBlock.length} 字符，${uniq.length} 个版本号`);
  console.log(`[dry] 合并后 CHANGELOG：${newSrc.length} 字符（原 ${src.length}）`);
  console.log(`[dry] 归档文件：${ARCHIVE}`);
} else {
  fs.mkdirSync(path.dirname(ARCHIVE), { recursive: true });
  fs.writeFileSync(ARCHIVE, archiveText, 'utf8');
  fs.writeFileSync(CHANGELOG, newSrc, 'utf8');
  console.log(`OK 合并 ${uniq.length} 条 -> 1 条；CHANGELOG ${src.length} -> ${newSrc.length} 字符`);
  console.log(`OK 归档 -> ${path.relative(ROOT, ARCHIVE).replace(/\\/g, '/')} (${archiveText.length} 字符)`);
}
