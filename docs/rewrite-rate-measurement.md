# 降重实测：改写-评分-回退闭环在真稿靶子上把重复率降了多少

日期 2026-10-09。一句话：**同一个语料、同一套判据，改写前 3.14%，改写后 2.42%**（命中 516/16,421 字
→ 398/16,421 字，相对降 22.9%，绝对降 0.72 个百分点）；11 个命中段落全部被改写覆盖，1 段改完单独
比对已经没有命中；整篇字数 24,201 → 24,198（-0.01%）；数字、单位、引文序号、型号、登记术语一字未动。
量它的断言是 `tests/RewriteRateRegression.java`（53 条，一条命令复采，下面有）。

## 靶子先复现（CHANGELOG 2.3.0 那一发）

素材与做法照旧：把一篇真开放获取论文里的 11 句插进 `tests/samples/input-liu.docx`，并把这 11 句导进
自建库。11 句现在进仓库了：`tests/corpus/oa-planted-cjmenet.txt`（文件头写了出处与许可说明——
王相宇等《钛铝合金低温切削加工温度的实验和仿真研究》，《机械工程学报》60 卷 19 期，2024，
DOI 10.3901/JME.2024.19.318；PDF 是 app 自己按 OpenAlex 的开放获取链接从期刊官网下的，
文件里没有机器可读的许可声明行，所以仓库只留这 11 句、约 500 字的引用量）。

| 语料口径 | 靶子 | 头条 | 命中字数 | 处数 | 最高分 |
| --- | --- | --- | --- | --- | --- |
| 整篇 PDF 入库（586 句） | 插句稿 | 3.14% | 515/16,424 | 5 | 0.932 |
| 只用那 11 句入库 | 插句稿 | 3.14% | 516/16,424 | 1 | 1.000 |
| 只用那 11 句入库 | **没插句的原稿** | 0.00% | 0/15,908 | 0 | — |

第三条是负对照：同一个库对着没插句的原稿是 0.00%、0 命中，所以后面所有前后差都只可能来自那 11 句。

一处要更正：CHANGELOG 2.3.0 写"最高 0.764"。0.764 是 5 处命中里第一处 `[0,296)` 的分，
**真正最高的是 0.932**（`DuplicateEngine.scan` 和直接 `TextCorpus.match` 两条路都量到同一个数）；
换成只用 11 句入库，最高分是 1.000。

复采（编译到私有目录，见文末"为什么用私有目录"）：

```
javac -nowarn -encoding UTF-8 -classpath "artifacts/build/rewrite-classes;tools/android-35.jar" `
  -d artifacts/build/rewrite-classes `
  app/src/main/java/com/rikkahub/wordlite/LocalRewriter.java `
  app/src/main/java/com/rikkahub/wordlite/RewriteLoop.java
javac -nowarn -encoding UTF-8 -classpath "artifacts/build/rewrite-classes;tools/android-35.jar;artifacts/build/host-gen" `
  -d artifacts/build/rewrite-test-classes tests/RewriteRateRegression.java `
  artifacts/build/host-gen/com/rikkahub/wordlite/R.java
java "-Dfile.encoding=UTF-8" -cp "artifacts/build/rewrite-test-classes;artifacts/build/rewrite-classes;tools/android-35.jar" `
  com.rikkahub.wordlite.RewriteRateRegression
```

末行输出：`SUMMARY 53 assertions passed；重复率 3.14% -> 2.42%`。这条断言已经把靶子 docx 写在
`artifacts/tests/rewrite-rate/` 下，仓库里的 `tests/samples/input-liu.docx` 一个字节都不动
（`FontSubstitution`、`OriginalDocxRegression` 还在用它）。

## 闭环按什么采纳

`RewriteLoop.java` 一个新类，判据那边一行没改，也没有第二条打分口径：

- **打分**：`TextCorpus.match(text, null, TextCorpus.structure(text).spanArray())`，
  与 `DuplicateEngine.compareRewrite` 里对改写前后各打一遍的那两句逐字相同。阈值全在
  `TextCorpus` / `CharLedger` 那边（句级 Dice 0.50、袋口径 0.72、`MERGE_GAP=2`，标定见
  `docs/detection-calibration.md`），这个类里一个阈值数字都没定义。
- **候选**：只对判据已经判重的段落出候选 = `LocalRewriter.rewrite(掩码文本, 术语, depth, 候选数)`
  + 长句拆短（一处"，"换"；"，只动一个标点）。每轮按段级命中字数从多到少挑一段开工。
- **采纳**：只看**整篇**命中字数严格变小。段级读数只用来排序和写报告，不当采纳依据。
- **同字数时认不认进步**：认，但只认判据自己给的另外两个读数——段级命中字数变少，或字数没变而
  段级最高分往下走一档。不加这一步会瞎：判据对命中段是整段计数，实测有一段改完
  `dup 32->32` 而最高分 `1.000->0.533`，一次改词的字数降幅本来就不足以过线，只按"字数必须变小"
  就会把这种改动整批丢掉。同一套预算（每段 10 轮、depth 8、候选 8、整篇验证 240 次、同一份术语表）
  下复采两遍：只认严格变小 → 3.14% → 2.69%（516 → 441 字，5 次采纳，只有 4 段被改）；
  加上这一步 → 3.14% → 2.38%（516 → 390 字，19 次采纳，11 段全被改）。量法是把 `RewriteLoop`
  拷一份到私有目录，只把那一行换成严格判定，其余一字不动。整篇命中字数任何一步都不许变大。
- **回退**：一轮下来整篇数字没变小就退回原文，`verdict` 照实写"这一轮一个字都没换，重复率没降"。
  断言里有一条反向用例：一句无规则可用的重复句，量出来 `100.00% -> 100.00%`、采纳 0 个区、
  文本原样退回、判定句里带"没降"三个字。
- **不动区**：候选必须过 `TextProtection.Mask.restore()`（与离线改写界面同一道守卫）；断言另走一条路
  复算——把掩码给出的每一处受保护内容（数字、单位、型号、引文序号、登记术语）在改写结果里
  按原样、按顺序再找一遍，整篇再按正则把 847 处数字、64 处引文序号、48 处型号、15 类单位逐个
  前后对齐。
- **文本健全性断言**（本轮新增，采纳前）：改完以标点开头或结尾、出现连续标点、字数缩水超 40%、
  留掩码占位符——中一条就丢掉这个候选，整篇数字变小也不采纳。这一轮实测 `rejected=0`（没产生
  残缺句，因为下面那个 bug 修了）。

一个反直觉的数：**命中处数 1 → 3 不是命中变多**。判据把相隔不超过 `MERGE_GAP=2` 的命中并成一段，
中间段落不再命中以后，一条长命中带裂成三条短的。量降没降只看命中字数与比率，断言里也这么写。

## 逐段读数（11 段，判据原话）

| 段 | 命中字数 | 最高分 | 结果 |
| --- | --- | --- | --- |
| 1 | 58 → 0 | 1.000 → 0.000 | 已清零 |
| 2 | 54 → 36 | 1.000 → 0.651 | 仍命中 |
| 3 | 32 → 32 | 1.000 → 0.533 | 仍命中 |
| 4 | 56 → 56 | 1.000 → 0.587 | 仍命中 |
| 5 | 54 → 54 | 1.000 → 0.531 | 仍命中 |
| 6 | 37 → 37 | 1.000 → 0.602 | 仍命中 |
| 7 | 45 → 23 | 1.000 → 0.656 | 仍命中 |
| 8 | 50 → 49 | 1.000 → 0.782 | 仍命中 |
| 9 | 49 → 33 | 1.000 → 0.692 | 仍命中 |
| 10 | 30 → 28 | 1.000 → 0.630 | 仍命中 |
| 11 | 51 → 50 | 1.000 → 0.784 | 仍命中 |

清零那一段改成："液氮冷却获得的测量切削力大于模拟值；原因在于实际切削试验中，刀具承受液氮的冲击
产生了额外作用力，导致测量值大于模拟值。"

预算与耗时：整篇级验证 175 次（上限 240，没用完），host JVM 上 2.9 秒。整篇验证是唯一贵的动作，
所以 `Limits.verifications` 单独卡死；用完还压不下去就在判定里写"整篇验证预算用完"。

## 深模式那个吞句的 bug（为什么现在 rejected=0）

`LocalRewriter` 的深模式（一条规则在这段里改多个匹配点，只给闭环用，界面路径还是"一次一个匹配点"）
原先在跳过某个匹配点时把光标直接推到匹配末尾，**跳过的正文没抄回输出**。实测把第 1 句改成以"，"
开头、少 17 个字的残句：`,原因是实际切削试验中，刀具受到液氮的冲击产生了附加作用力，仿真值低于引起实测值`。
修法是分成"已抄写位置"和"下一次搜索位置"两个游标（`LocalRewriter.java:79-97`）。

另一条是真语法问题：比较句互换会把句首的致使动词一起搬进主语——"引起实测值高于仿真值"被改成
"仿真值低于导致测量值"。现在这类句首动词（导致/引起/引发/造成/致使/使得/带来/产生/出现）不参与
这条规则。修完全篇读作"…导致测量值大于模拟值"。

这条检查进了仓库，就是 `tests/DeepModeAudit.java`（把每条规则在真稿句子上单独跑 depth 2..6，
只看改坏的那些；变长不算坏，结构规则本来就要补虚词）：

```
javac -nowarn -encoding UTF-8 -classpath "artifacts/build/rewrite-classes;tools/android-35.jar;artifacts/build/rewrite-test-classes" `
  -d artifacts/build/rewrite-test-classes tests/DeepModeAudit.java
java "-Dfile.encoding=UTF-8" -cp "artifacts/build/rewrite-test-classes;artifacts/build/rewrite-classes;tools/android-35.jar" `
  com.rikkahub.wordlite.DeepModeAudit
```

末行：`SUMMARY 6 assertions passed；规则 207 条 × 句子 211 条 × depth 2..6 全部过线；
整条链路另出候选 317 个，逐个过健全性断言`（句子取自 `tests/corpus/real-prose.txt` 前 200 句 +
靶子那 11 句，每档 depth 出手 251 次，改坏 0 次）。

## 没做到的

- **剩下 9 段降不掉，这条没做到**。它们停在 0.531–0.784，判据的句级 Dice 下限是 0.50、袋口径 0.72，
  差最后一公里的那截全是"锯齿形切屑的形成""切削变形区温度场分布""绝热剪切带"这一类术语短语。
  术语岛是一字不动区，离线词表不许碰它们，所以离线改写在这类稿子上的下限大约在 2.4% 这一带；
  再往下要的是重写句子骨架（换主语、拆并句、把结论前置），那是模型活，不是词表活。
- **候选数与轮数不是瓶颈，词表才是**。把候选上限从 8 提到 24、每段轮数从 10 提到 14、整篇验证预算
  从 240 提到 600，复采结果一模一样（392 字，无术语口径）——规则池已经抽干，加预算没有增量。
- **App 里还点不到**。离线改写唯一的入口在 `ApiWorkflow.java:812`，这一轮那文件不许改，所以闭环
  现在只有 host 侧入口：`RewriteLoop.run(整篇文本, 语料, 术语, 预算)` + `RewriteLoop.edits(...)`
  回写段落。接上去要改的就是那一个调用点。
- 断言钉的"显著低于"是两个门槛一起：**相对至少降一成半**（实测少 22.9%）且**绝对至少 0.5 个百分点**
  （实测 0.72）。改写前那一头的真值另外钉死（516 字、1 处命中、比率 ≥ 3.00%），判据一改这个数就会
  变，那时这条断言该响。

## 顺带跑到的其他断言

`DeepModeAudit` 6 条、`LocalRewriteRegression` 431 条、`RewriteRobustnessRegression` 84 条（`tests/corpus/real-prose.txt`）、
`DetectRegression` 202 条、`TextCorpusRegression` 209 条、`CharLedgerRegression` 100 条、
`CandidateRankerRegression` 98 条、`SharedSpanRegression` 72 条、`ZeroRateAudit` 诊断断言失败 0 项，
全过。其中两条按新行为改了：`由于温度升高…` 的首个候选现在顺带把"升高"改成"上升"（新加的一对
成对词），那条断言原来钉的是整串；`terms 传 null` 那条钉的是候选个数=1，它要量的其实是"不炸、
照样能改"，改成 ≥1。

## 为什么用私有目录

`tools/test-host.ps1` 第一桶水会清 `artifacts/build/host-classes` 与 `host-test-classes`，两个进程
同时跑就会互相看见对方的半成品，报出几千条假编译错误。本轮所有编译都落在
`artifacts/build/rewrite-classes`、`artifacts/build/rewrite-test-classes`、
`artifacts/build/rewrite-probe`（都在 `.gitignore` 的 `artifacts/` 下）。整套跑法仍然是
`pwsh tools/test-host.ps1`，只是别和另一个进程同时跑。
