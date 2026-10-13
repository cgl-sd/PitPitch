# PitPitch

PitPitch 是一个足球与 F1 赛事信息 Android 应用，用来查看赛程、比分、比赛详情、球队资料和年度积分，并预约应用自己的比赛提醒。当前版本为 **0.7.3（20）**，采用白色界面、紧凑列表和本地真实数据归档，可离线浏览已保存记录。

[下载 0.7.3 APK](https://github.com/cgl-sd/PitPitch/releases/download/v0.7.3/PitPitch-0.7.3.apk) · [Release 与校验文件](https://github.com/cgl-sd/PitPitch/releases/tag/v0.7.3)

0.7.2 与 0.7.1 均为已被替代的版本；若已安装，请直接下载新版覆盖安装，不要卸载或清空数据。

## 软件功能

### 首页

- 默认显示英超、欧冠和 F1，可在设置中选择其他已接入赛事。首页的足球赛程、F1 赛历与偏好并行读取本地数据，一起呈现，不按到达顺序分批出现。
- 足球按真实轮次展示，每个赛事最多预览三场，相邻比赛之间才显示分隔线，最后一场到底边的间距保持一致；F1 显示“第 N 站”、现场图片及练习、排位、正赛时间，不显示“下一站”标签。
- 卡片按下一场时间排序，不按自然周截断。点击比赛进入详情，点击卡片空白处进入该赛事的完整赛程，铃铛用于预约或取消应用提醒。
- 底栏提供首页、足球、F1，F1 使用右倾方格旗；右侧主队队徽直接进入球队主页，默认主队为曼联。
- 三个主页共用顶部标识栏；足球与 F1 标识同高且保留原比例，F1 首排选择栏与足球统一高度、间距和文字/下划线样式。
- 启动窗口保留应用 logo，下方只显示水平居中的红绿 PitPitch 字标，无汉字副标题或人为等待；足球/F1 赛季按钮为透明底文字下拉，与同排栏目文字基线对齐。

### 足球

- 接入英超、西甲、意甲、德甲、法甲、欧冠、欧联，支持历史赛季选择。
- 赛程、积分与球队数据优先读取随 APK 归档的本地数据，切换联赛/赛季/栏目时立即显示，不出现空白或模板态；已读取过的赛季在内存复用，未读取过的赛季从本地库读取。有网络时才更新内容，失败保留已显示数据。
- 供应商尚未确定对阵（例如淘汰赛抽签前的时间安排）时显示“待定”，场地缺失显示“场地待定”，不会出现空的球队单元。
- 赛程可以连续上下滚动，顶部轮次随可见比赛更新；轮次按钮跳到该轮第一场，默认定位下一场比赛。快速切换联赛时选择、列表和初始位置一起更新，不先显示旧列表位置再跳转；详情返回和同赛事后台刷新保留手动位置。
- 供应商把比赛标记为中止或终止时如实显示该状态并保留未知比分，不把中止时的临时比分当结果；尚未开赛的比赛仍显示“待赛”，待赛本身是正常状态。已入库的旧解析结果会在设备上按自身已保存的原响应重新解析修正，不需要联网。
- 欧冠与欧联的阶段选择只有联赛阶段和淘汰赛；联赛阶段结束后进入该赛事会直接打开淘汰赛，淘汰赛不显示轮次导航行，按一个连续列表滚动。积分表始终按联赛阶段显示，与阶段选择无关。
- 积分表先显示已有内容，进入栏目或切换赛事/赛季后联网更新，当日赛况刷新同时同步积分；失败保留旧表，不从部分赛程推算官方排名。表格下方只用短句说明计分、主要同分排序和基本晋级/降级名额，保留必要赛季差异，欧战多个小组后只展示一次。
- 球员栏目只提供进球榜、助攻榜，默认进球榜，切换后按对应统计降序排列，缺失数值保持未知。
- 比赛详情包含比分、完场/中场或来源比赛时钟、精简技术统计、重要事件和已发布首发阵型，首发页只保留可用阵型，不重复显示人数标题。技术统计保留点球进球，事件只展示进球、红牌和比赛节点。比分区把赛事与开赛时间放在同一行，减少顶部留白。
- 足球、球队和比赛页面的赛事、栏目、阶段、榜单及首发按钮采用紧凑留白；文字按可用宽度等比缩放，字体放大或窄屏时仍完整显示，不换行、不用省略号。球队详情中赛事筛选与主客场分组在屏幕宽度足够时并排一行，分别标注“全部赛事”与“全部”。
- 点击比赛队徽、积分表或底栏主队队徽可进入球队主页，查看本赛季全部历史与未来赛程、队员和所属联赛排名。顶部采用球队主色与队徽水印，联赛排名与队徽同排显示；球队详情中的队员名单不因球员栏目精简而移除。
- **球员姓名直接显示 ESPN 原始英文全名**，不翻译或缩写；足球球员不显示照片。球队、球场采用已核验的常用中文显示名：字典随 APK 打包、运行时离线，供应商响应本身只有英文原名；球场名来自可核验的实体标签或中文体育媒体通用译名（人工核验，非机器翻译），无法唯一确认的场地保留英文原名。

### F1

- 显示完整全年赛程，各条目以“第 N 站”标注真实站次，不显示“下一站”标签、背景高亮或右侧箭头，点击仍进入详情。只有当日或未来且尚无正赛成绩的最早站次展开时段；默认在可视区居中，首末站按列表边界定位，超高条目优先保留标题。历史赛季首次打开从第一站开始，所有条目紧凑；刷新、切换栏目及返回不重置手动位置。切赛季时列表不会越过自身边界遮住顶部栏目。
- 2020–2026 归档的 154 个分站均有本地赛道轮廓，赛程/详情保持统一黑色双轮廓、透明画布和原比例，不另加白底。补齐阿布扎比及其他缺图，真实历史改线分别绑定赛季/站次；图与索引沿用 assets/f1-media 随 APK 打包，无联网生成。首页使用现场图片；周末只展示一次练习，另保留真实排位、冲刺及正赛场次。
- 分站详情提供已发布排位成绩和完整正赛名次/结果/积分，包括退赛、未发车和 0 分车手。
- 年度车手/车队积分使用对应赛季图片、队色与已计分进度条；进度依据已发布成绩，不用日期推算。

### 设置与预约

- 首页齿轮进入设置，可选择主队、开启首页赛事、检查更新及查看“隐私和关于”。关于页显示安装版本与紧凑署名。
- 时间统一设备时区。首次点击铃铛预约应用提醒并提示“已预约”，再次点击取消并提示“已取消”。通知包含订阅的比赛名称和开赛时间，按原开赛 UTC 的前 **5 分钟**触发；不再写入或打开系统日历。
- Android 13 起按需申请通知权限；Android 12 起预约需要用户允许精确提醒。通知、通知渠道或精确提醒权限未获得时不报预约成功，取消不要求这些权限。没有真实开赛时间或五分钟触发点已经过去时不新建提醒。重启、APK 更新及设备时间/时区变化后恢复未来提醒；强行停止应用、权限撤销和设备省电限制仍可能阻止投递。
- 普通偏好可参与系统备份；Room/HTTP 缓存可从 APK/供应商恢复，不占用备份配额。应用提醒和下载关联为设备局部数据，不随备份迁移但覆盖升级保留。同一 F1 正赛在首页、赛程和详情共用一个订阅。旧日历关联文件保留但不再读取；以前写入系统日历的事件仍由日历管理，不擅自删除或修改。
- 主队与偏好在覆盖安装后保留。应用没有账号、收藏、演示模式或 UTC 切换入口。
- 没有单独的手动刷新按钮（“检查更新”只负责版本更新）。刷新一次更新全部数据：点击底栏当前页面图标即刷新足球赛程与当日赛况、F1 赛历与积分；打开应用时也会在本地数据呈现后自动后台刷新一次，期间始终显示本地内容。
- 检查更新读取 [cgl-sd/PitPitch 的最新正式 Release](https://github.com/cgl-sd/PitPitch/releases/latest)，发现更高的 `0.x.x` 版本后询问是否下载。系统负责 APK 下载，应用核验 SHA-256、大小、应用 ID、版本和签名身份，再申请“允许安装此来源”并打开系统安装器；必须由用户确认，不能静默安装。取消安装后可重试，下载状态保留在设备局部存储，不随备份迁移。设备必须能够访问 GitHub 及其文件托管域名；应用不内置代理或绕过网络限制。

## 数据范围与限制

足球固定从 **2020/21 赛季**、F1 从 **2020 赛季**起保存至当前赛季，以后增加新赛季也不删除 2020 年。当前随包归档包含 1,652 份来源响应、14,659 场足球赛程、14,166 条已核验补充轮次和 241 份队徽原图；当前赛季只包含来源已发布资料。

| 内容 | 来源与处理 |
|---|---|
| 足球赛程、比分、积分、球员名单、单场详情 | ESPN 公开端点，按赛事/赛季/球队 ID 验证并归档 |
| 足球补充轮次 | OpenFootball（CC0）及 FixtureDownload，独立关联，不覆盖 ESPN 原始内容 |
| 球队/球场中文显示名与球队主色 | 本地已核验资料，按来源 ID 或精确原名匹配 |
| F1 赛程、成绩与积分 | Jolpica，按 total/offset 读取完整结果，独立排位资料保留 Q 段 |
| 队徽、F1 人物/赛车/赛道图片 | 已整理原图或赛道轮廓，按实体 ID 与赛季关联，许可/来源随资产保留 |

公开端点不是已购买的正式稳定 API，也不是秒级直播或 F1 遥测服务。历史首发、部分统计和时段可能缺失；应用不预测首发，不生成假比分、积分或轮次。联赛杯、足总杯目前未接入，身价和未经可靠核验的教练/个人抢断/成功过人不展示。

球员英文姓名、ID、headshot 链接及其他原始字段保留在归档和数据库，中文球员映射已经移除。最近的英超头像覆盖核查为 585 人中 38 个链接，界面统一不显示、不请求球员头像。比赛时钟有来源秒数才显示分秒；去掉加载提示不代表数据读取无需时间，未缓存详情仍会后台读取。

## 架构与目录

```text
PitPitch/
├── README.md                 # GitHub 仅上传这一份项目文档
├── app/
│   ├── src/main/             # Kotlin、Manifest、资源和打包归档
│   └── data-tools/           # 显式数据同步与整理工具
├── gradle/wrapper/           # 固定 Gradle Wrapper
└── tmp/
    ├── tests/unit/java/      # 单元测试，纳入 Git
    ├── tests/android/        # 设备测试及必要响应样例，纳入 Git
    └── validation/           # 当前验收证据及本机工具环境，忽略
```

应用使用 Kotlin + Jetpack Compose + Material 3；Activity 负责启动，ViewModel + StateFlow 管理状态，Navigation Compose 负责路由。UI 经 Repository 读取 Room 本地后端；网络结果和打包归档进入同一数据库。DataStore 保存用户偏好，图片文件保留在 APK 或应用私有缓存，不作为数据库大 BLOB。

数据库按比赛 ID、赛事、赛季、球队/车手等建索引；显示资料一次性在后台预加载，列表不重复解析文件。已缓存比赛详情优先读取本地，后台更新继续展示已有内容。覆盖升级不清数据库或偏好，APK 导入冲突时已有有效本地事实、原响应和时间优先；缺失资源或可核验的内置局部数据补充非冲突条目。无法证明来源/完整性的旧资源保守保留，不从 APK 补回可能已删除的条目。用户主动联网刷新仍按实际获取时间接受较新完整响应，损坏本地资料可由真实归档修复；数据库升级使用保留数据的 Migration。

赛程、详情、名单和来源时间在同一 Room 事务中更新；旧或损坏响应不能覆盖有效新事实，完整性校验先于缓存落盘。数据库 JSON 原响应也使用分段读取，避免单行 CursorWindow 超限。偏好异常不会使可读足球数据永久空白；真正损坏的偏好先保留诊断副本，再恢复可写状态，不清健康偏好。

选择值通过轻量 SavedStateHandle 恢复，历史详情独立读取其路由季/赛事。榜单请求按键协调，已完成任务及时释放；首页时钟与轮询随后台暂停，退出中的旧详情不能停止新详情任务。详情内存限制为 128 个未使用快照，加仍可见详情和最多 12 场主页预热；淘汰后从 Room 读取，历史事实不删。球队赛程和榜单的静态排序按实际数据变化复用，不改显示顺序。

赛事 JSON 网络请求默认连接/读取 12 秒、总时限 20 秒、最大 16 MiB，并在取消时关闭请求；不自动重试。HTTP、解析、身份或存储错误仍保留真实失败状态和有效缓存。图片继续共用 Coil，磁盘缓存上限 50 MiB。调试构建仅为本机确定性测试允许回环 HTTP，Release 不开放该例外。

项目已进入收尾调整阶段，后续产品调整以用户最新明确指令为准。公开仓库为过滤后的源码快照，只上传 README，不上传 `docs/`、AGENTS、CHANGELOG 或含这些文件的本地开发历史；本地仍保留这些记录及用户参考资料。`.gitattributes` 的 `export-ignore` 用于排除内部文档，发布时使用独立干净仓库接收归档，不能直接推送未过滤的本地分支。资源必须保留的作者、来源、许可及版本文件不在项目文档排除范围内。

## 构建与运行

固定工具链：Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、JDK 17、compile/target SDK 35、最低 Android 8.0（API 26）。先复用本机已有 JDK、SDK 和依赖缓存，不默认升级工具链。

1. Android Studio 打开项目根目录，Gradle JDK 选择 JDK 17。
2. 本机 `local.properties` 或 `ANDROID_HOME` 指向 Android SDK，安装所需 Platform 35/Build-Tools；个人路径不提交。
3. 在终端设置 `JAVA_HOME` 为 JDK 17，优先离线构建：

```bash
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

4. 后续界面/交互调试及设备验证统一在 Android Studio 的 Running Devices 内进行，优先复用其中已连接的设备，不另开独立模拟器。选择已有设备运行 app；Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，更新时覆盖安装以保留数据。

构建分两条用途：**测试装 debug**（`./gradlew --offline :app:assembleDebug`，debug 证书，可直接安装、可覆盖安装）；**对外发布用 release**（`./gradlew --offline :app:assembleRelease`，需签名）。两者证书不同，不能互相覆盖：手机上装过 release 版时，debug 包必须先卸载才能安装（会丢偏好与本地数据），反之亦然。非调试构建与静态检查使用 `./gradlew --offline :app:assembleRelease :app:lintDebug`。默认 Release 产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`，签名后才能安装；签名密钥不放入项目、不提交 Git。在仓库根放置被忽略的 `keystore.properties`（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）后，`assembleRelease` 会用该证书直接产出可安装的已签名 APK；缺少该文件时构建仍成功，只是产物未签名。debug 与 release 证书不同，不能互相覆盖安装。GitHub 版本上传 `PitPitch-0.7.3.apk` 及同名 `.sha256` 校验文件。为兼容当前已安装版本，本轮沿用原有本机证书，不擅自换签名；这是本地安装分发，不是商店签名验收。后续每次更新必须递增 versionCode 并继续使用同一证书；更换证书通常无法直接覆盖安装，不能靠卸载规避数据保留要求。

本机临时 JDK 17 位于 `tmp/validation/jdk17/amazon-corretto-17.jdk/Contents/Home`，仍用于构建，清理时保留；这不是所有开发机器都需要的路径。`setup-wrapper.sh` 仅用于检查/修复固定版本 Wrapper。

设备测试源码通过 Gradle sourceSets 接入 `tmp/tests/android`。例如验证英文姓名、首发/事件及加载界面：

```bash
./gradlew --offline :app:assembleDebugAndroidTest
./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.pitpitch.FootballPlayerDisplayTest,com.example.pitpitch.MatchDetailPresentationTest,com.example.pitpitch.StartupPresentationTest
```

测试资源只供测试，不能作为生产初始化。已取消演示/UTC 等交互的旧测试已清除；其余用例仍须按实际改动运行，不将一次针对性测试等同于整个软件全部验收。

## 数据维护

普通 Gradle 编译不会联网抓取数据。只有明确更新资料时才运行下载工具，下载后核验再发布：

| 工具 | 用途 |
|---|---|
| `app/data-tools/sync_archives.py` | ESPN/Jolpica 归档同步，保留原获取时间与 SHA-256 |
| `tmp/tests/build_football_rounds.py`、`check_football_rounds.py` | 补充轮次整理及精确关联核验 |
| `tmp/tests/bundle_team_badges.py` | 按原始供应商链接整理队徽原图 |
| `tmp/tests/bundle_chinese_names.py`、`check_chinese_names.py` | 球队/球场中文名及身份核验，不处理球员姓名 |
| `tmp/tests/bundle_team_colors.py`、`check_team_colors.py` | 球队主色及来源核验 |
| `app/data-tools/bundle_f1_media.py`、`tmp/tests/bundle_f1_*.py` | F1 历史图片、赛道轮廓、彩色队徽及排位资料 |
| `tmp/tests/check_sports_archive.py`、`check_archive_start.py` | 原响应哈希、数据覆盖、赛季起点与完整性核验 |
| `tmp/tests/build_local_backend_manifest.py` | 离线生成/核验所有打包输入版本 |

修改任何打包资产后执行：

```bash
python3 tmp/tests/build_local_backend_manifest.py
python3 tmp/tests/build_local_backend_manifest.py --verify
python3 tmp/tests/check_sports_archive.py
```

整理工具的下载/核验响应写入 `tmp/validation`，旧响应按需清理；重新整理可能需要显式下载来源，不依赖历史临时文件才能正常编译。不要把下载成功当成数据或设备验收通过。

## 来源、图片与网络行为

- ESPN、Jolpica 为当前赛事资料来源；轮次补充来源为 OpenFootball、FixtureDownload；球队/球场显示资料保留 Wikidata 等身份资料及逐项用名来源。
- 已打包队徽、F1 图片和赛道图优先本地读取，保持比例、透明度、来源链接、署名与校验值。没有匹配资料时使用中性占位，不借用别的球队/车队图片。
- 新增未打包队徽可按供应商原始 HTTPS URL 请求，并由 Coil 缓存；网络请求可能向托管方提供 IP 等请求信息。足球球员头像不产生界面请求；赛事刷新仍会访问对应数据端点。
- 图片、球队/赛事商标的权利属于各权利人，不因公开链接或数据许可变成开源图片，也不表示官方合作。关于页只保留必要署名，完整来源和许可原文随资产保留。
- 参考赛道地图采用 bacinger/f1-circuits（MIT）；补充历史/改线轮廓采用 Wikimedia Commons 已许可原图，并以脚本统一风格，不使用生成模型猜测路线。作者、原图与修改后 SHA-256、来源及修改说明在 assets/f1-media/outline-attributions.json，CC BY-SA 3.0/4.0 许可原文随 APK 保存，公有领域/CC0 作品逐项标注。历史布局不跨赛季借用。轮次 OpenFootball 与结构化资料 Wikidata 为 CC0；启动字标字形的 Roboto（SIL OFL）许可也随 APK 保留。
- 积分与排名说明依据官方规则：[英超同分规则](https://www.premierleague.com/ar/news/4638928/the-run-in-202526-your-relegation-questions-answered)与[欧战资格](https://www.premierleague.com/en/european-qualification-explained)、[西班牙足协赛规](https://rfef.es/sites/default/files/pdf/circulares/primera_y_segunda_division.pdf)、[意大利足协 2022/23](https://www.figc.it/media/173115/304-deroga-art-51-noif-determinazione-classifica-campionato-serie-a-ss-2022-2023.pdf)与[2023/24 规则](https://www.figc.it/media/208461/23-deroga-art-51-noif-determinazione-classifica-campionato-serie-a-ss-2023-2024.pdf)、[德甲赛规](https://media.dfl.de/sites/2/2020/05/Spielordnung-SpOL-2020-05-16-Stand.pdf)、[法甲赛规](https://www.lfp.fr/assets/24_25_Reglement_Championnats_France_26_07_2024_7cdc2a4af9.pdf)、[欧冠联赛阶段同分条款](https://documents.uefa.com/r/Regulations-of-the-UEFA-Champions-League-2026/27/Article-18-Equality-of-points-league-phase-Online)及[欧足联 2021 年客场进球规则修订](https://editorial.uefa.com/resources/026a-129a51620b1a-63bc6117f7c6-1000/20210624_circular_2021_43_en.pdf)。规则按赛事与赛季分支展示，额外席位不提前标为已确认。
- 意甲 2026/27 同分附加赛的欧战决赛球队例外依据 [FIGC 244/A（2026-05-27）](https://files.figc.it/version/c%3AZmUzYzk0MzUtMzU0Zi00%3AYTk3YzZiZGEtYmExYi00/244%20-%20Deroga%20art.%2051%20NOIF%20-%20Determinazione%20classifica%20Campionato%20Serie%20A%20ss%202026%20-%202027.pdf)；欧联新赛制依据[欧足联 2026/27 赛规](https://documents.uefa.com/r/Regulations-of-the-UEFA-Europa-League-2026/27/Article-15-Match-system-qualifying-phase-and-play-offs-Online)。历史说明保留当季适用规则，不将现行赛制套到旧赛季。

## 验证与交付状态

本轮紧凑控件调整与过期比赛状态修正已在本地完成：离线 Debug/Release 构建、84 项单元测试通过（新增过期待赛判定用例），Studio API 37 完整设备回归 187 项全部通过（含新增的大字可读、下载边界、过期比赛显示与真实归档中止状态回归）；lint 为 0 错误、7 条非阻断提示，2546 个打包输入核验通过。以系统字体缩放 1.3 实际浏览足球、欧战积分与球队页，分段与筛选标签完整显示；验收后字体缩放恢复 1.0，设备偏好字节不变。西甲 2026-10-10 巴列卡诺对毕尔巴鄂在列表与详情均显示“赛况待更新 / — : —”，不再显示待赛或预约铃铛，首发仍展示归档中真实公布的名单。实际验证通知/精确提醒许可的拒绝及授权、预约/取消、开赛前五分钟真实应用通知；应用进程退出后由系统闹钟重新启动接收器并投递，临时订阅已清理。当前证据保存在 `tmp/validation/compact-controls` 与 `tmp/validation/github-release-0.7.1`。

GitHub 真实更新链路已完成：以仅降低版本号的验收构建模拟旧版，发现正式 Release、下载与完整校验、已下载状态经进程退出恢复、拒绝安装来源后重试、取消系统安装后重试，最终系统安装器完成 0.7.2（19）覆盖更新。实际订阅仍在，原 11 项偏好字节不变；逐行核验全部 2020–2025 历史比赛/榜单/人员记录和 1652 份内置原响应的时间/哈希保持，SQLite quick_check 为 ok。验收临时借用主机已有代理访问 GitHub，已恢复 Studio 设备原网络设置；没有给生产代码添加代理或重试绕过。

0.7.3（20）已完成发布：本地 Git 提交经 `.gitattributes` 过滤后的源码快照推送到公开仓库 `main`（不含 `docs/`、AGENTS、CHANGELOG），Release v0.7.3 上传 `PitPitch-0.7.3.apk` 与同名 `.sha256`，继续使用原本机证书签名，应用 ID 与签名身份不变。验证：离线 `:app:assembleRelease`、`:app:assembleDebug` 与 74 项单元测试通过；成品 APK 为 0.7.3（20），签名 SHA-1 与 0.7.2 相同、zipalign 通过、SHA-256 `aa420427…`；在 Studio API 37 设备上以 release 包覆盖已安装的调试版 0.7.2（19）安装成功，首次安装时间与 ceDataInode 未变，21 张表行数、11 项 shared_prefs、DataStore 偏好及 no_backup 记录字节一致，SQLite `quick_check` 为 ok，启动后首页正常显示归档赛程（欧冠第 2 轮、英超第 7 轮）。本轮未运行完整设备回归，真实机型、商店签名与发布授权仍未验收；证据保存在 `tmp/validation/publish-0.7.3`。

真机、API 26–36 设备矩阵、完整无障碍人工验收、OEM 省电限制/云备份恢复、生产帧率、专用远端后端契约、正式分发签名/授权和商店发布尚未完成。当前支持本地安装使用；沿用本机调试证书以保留既有安装数据，不等于商店发布签名，模拟器通过不代表所有手机已验收。
