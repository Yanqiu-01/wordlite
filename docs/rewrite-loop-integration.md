# 改写闭环的界面入口（给联网查重那一路接）

一句话：`RewriteLoop.run(整篇文本, 语料, 术语, 预算, 进度回调)` 阻塞跑完，返回一个带着**改前改后两个数**
和逐段读数的 `Result`。界面要做的就三件事：后台线程调它、把 `Listener` 的两个方法接到进度条与取消按钮、
把 `RewriteLoop.edits(...)` 给出的替换逐条写回段落。闭环里没有任何 Android 依赖。

实测数据在 `docs/rewrite-rate-measurement.md`；这篇只写入口形状。

## 输入

| 参数 | 类型 | 从哪来 |
| --- | --- | --- |
| `text` | `String` | 要改的那份文本。整篇就用 `TextSelection.all(document).text`（界面现有那条路），别自己拼段落；只改选区就传 `TextSelection.paragraph(block, start, end).text`，闭环只会在那一段里找命中。 |
| `corpus` | `TextCorpus` | 与查重报告同一份：自建库加已抓回的正文，`LocalLibrary.index(corpus)` 出来的那个对象。为空时 `run()` 立刻返回 `measured=false`。 |
| `terms` | `List<String>` | 登记术语，与查重时同一份；传 `null` 等于空表。术语是掩码的一部分，改完一个字都不许动。 |
| `limits` | `Limits` | `depth/candidates/rounds/regions/verifications`，默认 6/3/4/12/24。靶子实测用的是 8/8/10/12/240（见测量文档的复现命令）。 |
| `listener` | `Listener` | 可以传 `null`，传了就有进度与取消。 |

## 返回：`Result`

界面文案真正要用的是这几个字段：

- `text` —— 采纳完的整篇文本。一个区都没压下去时它**等于原文**（回退）。
- `measured` / `reason` —— `false` 表示量不出基线（没有语料，或可比对字数为 0），界面该说"先做一次查重
  或先导入自建库，才知道改写有没有用"。
- `rateBefore` / `rateAfter` / `drop()` —— `double`，百分数值（3.14 就是 3.14%）。显示用
  `DuplicateEngine.percent(...)`，别再乘一遍 100。
- `dupBefore` / `dupAfter` / `comparedChars`、`hitsBefore` / `hitsAfter`、`segments`、`segmentsChanged`、
  `segmentsCleared`、`regions` / `regionsImproved`、`verified`、`rejected`、`budgetHit`、`cancelled`。
- `verdict` —— 一句人话，里面已经带了两端的百分数与降幅。直接显示它，别自己拼"改写成功"。
- `RewriteLoop.details(r)` —— 逐段读数（`dupBefore/dupAfter`、`scoreBefore/scoreAfter`、`cleared`、
  `reason`），"这段没降下来"的列表用它。
- `delta` —— `DuplicateEngine.RewriteDelta`，就是查重页那个改写前后对比，同一个口径，不用二次计算。

**判成功只有一个口径**：`r.rateAfter < r.rateBefore`。`regionsImproved > 0` 不等于成功——采纳判定本身
就要求整篇命中字数变小，所以两个数没动就意味着什么都没采纳，这时 `text` 也等于原文。

## 线程、进度、取消

- `run()` 阻塞，必须后台线程调（跟查重扫描放同一个执行器就行）。
- `Listener.onProgress(String stage, int done, int total)`：在同一个后台线程回调，要刷界面自己 post 回
  主线程；不许阻塞、不许抛（抛了会被咽掉，但那一次进度就丢了）。`stage` 是给人看的短句，实测会看到：
  `对着语料比对原文` → `判出 1 处命中` → `改写第 1/1 个命中区` → `在命中区里出候选并逐篇验证` →
  `复核改写结果`。`done/total` 是**整篇级验证的次数**，这是真进度：整篇验证是唯一贵的动作，实测占九成以上
  的时间。
- `Listener.cancelled()`：每出一批候选、每做一次整篇验证之前各问一次，返回 `true` 就收尾。语义是
  **停止采纳**，不是回滚：已经采纳的改动全留（每一条都让整篇命中字数变小），`Result.cancelled=true`，
  `verdict` 末尾写"调用方取消，提前收尾"。所以取消之后照样能显示前后两个数——实测取消那一轮
  3.14% → 2.79%，只用了 34 次整篇验证（跑完要 175 次）。取消按钮只要把一个 flag 立起来，不用打断线程。
- 预算：`Limits.verifications` 是硬上限（默认 24）。到点收尾，`budgetHit=true`，`verdict` 会说
  "整篇验证预算用完，剩下的命中区没验证"。耗时实测（host JVM）：11 段命中 175 次 ≈ 3.0 秒，
  25 段 521 次 ≈ 10.1 秒，45 段 1080 次 ≈ 22.8 秒。手机上按这个数往上留余量；界面上起步建议
  `12 × 命中段数`，并且一定要有取消按钮。
- 一次 `run()` 一个 `Result`，不重入。同一份文本两个线程一起跑没有意义。

## 把结果写回文档

```java
TextSelection selection = TextSelection.all(document);
RewriteLoop.Result r = RewriteLoop.run(selection.text, corpus, terms, limits, listener);
for (RewriteLoop.Edit e : RewriteLoop.edits(selection, r, selection.text)) {
    // e.paragraphIndex / e.start / e.end 是文档段落里的偏移，e.replacement 是这段的新文本
}
```

- 段落数没变才会有条目（`edits()` 内部校验过），所以按段落号替换安全；**从最后一条往前替换**，
  偏移不会被前面段落的长度变化打乱。靶子实测给出 11 条替换，段落号递增。
- 返回空列表 = 什么都没改（回退了，或本来就没命中）。该显示 `verdict`，不是报错。
- 只改了一个标点也算 `changed`（长句拆短就是只动标点），别当失败。

## 什么时候必须显示"没降下来"

| 情况 | 界面上该怎么办 |
| --- | --- |
| `measured == false` | 说没有可比对基线，给去查重/去导入自建库的入口。 |
| `rateAfter == rateBefore` | `verdict` 里已经有"这一轮一个字都没换，重复率没降"，照抄，不要说成功。 |
| `budgetHit == true` | 说预算用完、剩下的命中区没验证，给"再跑一轮"。 |
| `cancelled == true` | 说停在哪一步，前后两个数照样给。 |
| 某段 `cleared == false` 且 `dupAfter > 0` | 用 `details(r)` 里那句"改了，但单独比对仍命中 N 字（最高分 x.xxx）"，逐段列出来。 |
