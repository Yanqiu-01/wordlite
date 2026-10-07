# 移动 Office 里有没有"查重 / AIGC 检测 / 降重"：字符串与符号证据

对象：`artifacts/ms-office.apk`（343,748,748 字节，16.0.17328.20180，arm64-v8a）。
结论一句话：**没有查重率、没有 AIGC 检测、没有降重；但 Word 本体里确实编进了一套
"相似来源 + 引用建议"（Similarity）的服务端对象模型，在这个包里没有任何用户可见入口。**

## 1. 搜索覆盖范围（先把口径讲清楚）

检索词（每个词都按 UTF-8 / UTF-16LE / UTF-16BE 三种编码各查一遍，大小写不敏感）：

```
查重  降重  相似度  重复率  论文检测  AIGC  AI-generated
plagiar  originality  similarity  paraphras  dedup  simHash
```

覆盖条目：

| 层 | 条目数 | 字节 | 说明 |
| --- | --- | --- | --- |
| `resources.arsc` | 1 | 14,324,024 | `aapt2 dump resources` 解出 28,286 个资源项、228,292 行 |
| `res/**` + `classes*.dex` + `assets/**` + `resources.arsc` | 9,856 | 119,357,862 | `feature_probe.py`，内嵌 7z 一并解开后搜 |
| `lib/**`（98 个 .so） | 98 | 273,357,208 | `sweep_dir.py` |
| 原生资源包解出后的成员 | 199 | 45,797,013 | `res.7z`、`wintlandroid.dll`、`msointlandroid.dll`、`officeandroid.odf`、`*.nls` 等 |

资源里确实带中文，阴性结果不是"没中文所以搜不到"：`resources.arsc` 里 `zh-rCN` 2,650 条、
`zh-rTW` 2,673 条字符串值；编码自检 `文档` 命中 68 处，说明中文能正常解码。
`res.7z` 只带了 en-US + es-ES/fr-FR/id-ID/pt-BR/tr-TR 五种语言的 Word 字符串表，
中文 Word 界面字符串不在这个包里（装完联网再取）。这条限制写在下面第 4 节。

## 2. 检索命令

```powershell
# 资源层
& $aapt dump resources E:\download\claude\Wordlite\artifacts\ms-office.apk | Set-Content _resources_dump.txt -Encoding UTF8
Select-String -LiteralPath _resources_dump.txt -Pattern '查重','降重','相似度','重复率','AIGC','plagiar','similarity','originality' -Encoding UTF8

# Java/Kotlin 层 + assets + 内嵌 7z
py feature_probe.py E:\download\claude\Wordlite\artifacts\ms-office.apk      # 结果 _feature_probe.txt

# native 资源包（先解包再搜）
py unpack_native_res.py E:\download\claude\Wordlite\artifacts\ms-office.apk extract\native-res
py sweep_dir.py extract\native-res

# 98 个 .so
py sweep_dir.py extract\_libs_all
py probe_ascii_context.py similarity extract\_libs_all\libwlibandroid.so
py probe_pool_neighbors.py EnableSimilarityChecker extract\classes4.dex
```

## 3. 命中与未命中

### 3.1 完全未命中（0 处，跨上述全部条目）

```
查重   降重   相似度   重复率   论文检测   AIGC   plagiar   paraphras
```

没有"重复率百分比"、没有"降重/改写"、没有 AIGC 检测的任何字符串或符号痕迹。

### 3.2 "AI generated" 命中的是免责声明，不是检测

`resources.arsc` 里 8 处 `AI generated` / `AI-generated content may be incorrect`，
资源名分别是 `ai_chat_interface_ai_generated_hint`、`feedback_ai_disclaimer`、
`idsCopilotAIGenerated`、`idsCopilotAIGeneratedTooltip`、`idsCoPilotFreHint`、
`lenshvc_copilot_ai_disclaimer`、`lenshvc_copilot_tooltip_content_ai_generation`、`tooltip_content_ai_gen`。
全部是"这段是 Copilot 生成的，可能有错"的标注/免责文案，语义方向相反（自己标注 ≠ 检出别人）。

### 3.3 `dedup` 命中是内部去重，与查重无关

`dedupe`（classes6.dex）、`mDeDupedEventsHolder`（classes5.dex）、
`assets/deckview.android.bundle`、`assets/officemobile.android.bundle`、
`libmso50android.so`、`libxlnextandroid.so`。均为事件/对象去重的内部命名。
`simHash` 0 命中。

### 3.4 `originality` 是 PowerPoint 的功能

`resources.arsc` 4 处命中全是资源名与文件路径：`drawable/ic_originality`、`drawable/ic_originality_landscape`。
classes6.dex 里 6 处：`Lcom/microsoft/office/powerpoint/view/fm/OriginalitySlides;`、
`getoriginalitySlides` / `originalitySlides` / `setoriginalitySlides` 及上面两个图标名。
`libppt_android.so` 里 1 处 UTF-16 `originality`，邻居是 `Dissolve`、`Doors`、`Shred`、`Wind` 这些切换动画名。
→ PowerPoint 的幻灯片模板/设计功能，与文本查重无关。

### 3.5 真正值得记的一条：Word 的 Similarity 子系统

`libwlibandroid.so` 里 `similarity` 命中 59 处，去重后 47 个不同符号，展开后是**一整套 C++ 类型**：

```
Microsoft::Office::AugLoop::Similarity::SimilarSource
Microsoft::Office::AugLoop::Similarity::SimilaritySummary
Microsoft::Office::AugLoop::Similarity::SimilarityAnnotation
Microsoft::Office::AugLoop::Similarity::ISimilarityAnnotation
Microsoft::Office::AugLoop::Similarity::CitationSuggestion
Microsoft::Office::AugLoop::Similarity::CitationSuggestionsByStyle
Microsoft::Office::AugLoop::Similarity::CitationSuggestionsByStyleMap
Microsoft::Office::AugLoop::Similarity::CitationSuggestionsByFormatMap
Microsoft::Office::AugLoop::Signals::SimilarityCheckSignal
Microsoft::Office::AugLoop::Signals::ISimilarityCheckSignal
```

全部通过 `Microsoft::AugLoop::Client::RegisterObjectType<...>` 注册（AugLoop 是 Office 的云端增强服务客户端，
注册的是可序列化对象类型）。配套三个开关名：

```
EditorSDX.SimilarityEnabled
EditorSDX.SimilarityEnterpriseEnabled
EditorSDX.SimilarityConsumerEnabled
```

遥测/事件名：`AugLoop_Similarity_SimilarityAnnotation`、`AugLoop_Similarity_SimilaritySummary`、
`AugLoop_Similarity_SimilarSource`、`AugLoop_Similarity_CitationSuggestion`、
`AugLoop_Signals_SimilarityCheckSignal`、`similarityAnnotationCount`。
另有 `EditorSDX.SimilarityConsumerEnabled` / `EditorSDX.SimilarityEnterpriseEnabled`，
说明消费版和企业版分开灰度。

Java 侧还有 flight 名 `EnableSimilarityChecker`（classes4.dex）。
它的字符串池邻居是一整排 `EnableOfficeMobile*` / `EnablePPT*` / `EnableRehearse*`：

```
EnablePPTImmersivePrint / EnableRehearsePPTMobile / EnableScrollBarDragging
EnableSimilarityChecker / EnableSkiGraphImport / EnableSkiInsights / EnableSkiJuno
```

→ 是功能开关标识符，不是功能实现名。

**证据边界**：`EditorSDX.*` 与 `Enable*` 都由服务端下发。这套 Similarity 在 16.0.17328.20180 里的状态是
"类型和开关已编译进二进制，但 APK 内 0 条用户可见字符串、0 个布局、0 个中文名称"，
也就是没有可点击入口。是否在登录某账号后由服务端打开、打开后长什么样，静态分析看不出来，**未验证**。
另外从符号看，它的产出形态是 `SimilarSource` + `CitationSuggestion`（找相似来源并建议怎么加引用），
**不是**"重复率百分比 + 降重"。

## 4. 这个负结果的适用范围

成立的：这个 APK 文件里，任何用户能看到的文案、任何 Java/Kotlin 类名、任何 native 符号、
以及 Word 自己的 en-US 字符串表里，都没有查重率/AIGC 检测/降重。

不成立的（不要外推）：装到机上登录后服务端能不能开 Similarity；其它区域包（如国产版 WPS 化的
Office 变体）有没有；以及 `res.7z` 里没带的 100 多种语言的中文 Word 文案。

## 5. 对 Word Lite 的意义

Word Lite 的 `DuplicateEngine` / `TextCorpus` / `AigcDetector` / `LocalRewriter` / `LocalLibrary`
在移动 Office 里**没有对应功能**。移动 Office 唯一沾边的那套 Similarity 走的是云端 AugLoop、
面向英文文献的"补引用"场景，而且在本包里不可见。所以"查重/AIGC/降重"这一整块是 Word Lite 的
独占面，不是"别人也有只是小"。

