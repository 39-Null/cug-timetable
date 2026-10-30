# 泥地课表 · 产品设计文档

版本：1.1  
更新时间：2026-10-30

---

## 1. 摘要

泥地课表是一个面向中国地质大学（武汉）本科生的 Android 本地课表应用。用户在学校教务页面完成登录与查询后，应用读取当前页面 DOM，在本地解析课程与考试，并持久化到设备 SQLite。网络仅用于学校教务页面和 GitHub 更新检查，不上传本地数据。

设计目标：

- **零账号体系**：不注册、不上传、不维护服务端
- **零广告**：不接入广告/统计 SDK
- **可离线**：导入完成后，除更新检查外全部功能离线可用
- **可演进**：学校差异、解析差异、UI 主题均以可替换模块实现

---

## 2. 产品定位与边界

### 2.1 目标用户

- 中国地质大学（武汉）本科生
- 使用正方教务（jwglxt）查询课表与考试
- 希望课表在本地、无广告、可离线查看

### 2.2 范围内

- 教务网页登录与课表/考试导入
- PDF / 文本 / 手动导入
- 课表网格、周次、日期表头、今日高亮
- 学期档位与历史课表
- 课程详情、实验块、冲突校验
- 深色主题、自定义背景（手动裁剪）
- 桌面小组件

### 2.3 范围外

- 成绩查询、选课、空教室
- 账号托管、云端同步、多端同步
- 通知推送（除桌面小组件外）
- 其他学校（当前仅内置 CUG 解析器）
- iOS / HarmonyOS 原生版本

---

## 3. 信息架构

底部四个一级入口：

| 入口 | 职责 |
|---|---|
| 课表 | 周视图；点击打开学期面板 |
| 导入 | 导入课表 / 导入考试两级菜单 |
| 主题 | 深色、背景图、遮罩、网格线 |
| 设置 | 校区、周数、节次、开学日、教务地址、小组件、反馈 |

主导航不设置「考试」独立页：考试作为课表的一种块类型展示，管理与导入收进「导入 → 导入考试」。

---

## 4. 总体架构

```
┌───────────────────────────────────────────────┐
│ UI 层                                          │
│ MainActivity / TimetableView / ThemeActivity   │
│ ImportPreview / ExamPreview / CropActivity     │
├───────────────────────────────────────────────┤
│ 导入层                                         │
│ WebImport / ExamWebImport（WebView + DOM）     │
│ PdfImport / TextImport / 手动编辑              │
│ HtmlCourseParser / HtmlExamParser / PdfTable   │
├───────────────────────────────────────────────┤
│ 更新与备份层                                   │
│ UpdateManager（GitHub / 校验 / PackageInstaller）│
│ Backup（JSON 导出导入）                        │
├───────────────────────────────────────────────┤
│ 领域层                                         │
│ Course / Exam / Semester                       │
│ Weeks（周次） / 冲突规则 / 课表格位计算         │
├───────────────────────────────────────────────┤
│ 数据层                                         │
│ Db（SQLiteOpenHelper）                         │
│ Prefs（SharedPreferences）                     │
├───────────────────────────────────────────────┤
│ 系统能力                                       │
│ WebView / SAF / AppWidget / AlarmManager       │
└───────────────────────────────────────────────┘
```

关键决策：

- **不使用 Gradle/AndroidX**：项目规模小，手写构建链可以减少依赖与构建不确定性；UI 全部基于 Framework View。
- **导入放在 WebView**：学校登录可能涉及统一身份认证、验证码、二维码、WebVPN；由用户在真实页面操作，应用只读取渲染后的 DOM，避免模拟登录与会话维护。
- **本地优先**：所有业务数据落 SQLite；网络仅用于学校页面和 GitHub 更新检查，不上传本地数据。

---

## 5. 数据模型

### 5.1 semesters

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK | 学期 ID |
| name | TEXT | 如「2026-2027 第1学期」 |
| start | TEXT | 第一周周一，`yyyy-MM-dd` |
| created | INTEGER | 创建时间戳 |
| sort | INTEGER | 历史课表排序号 |

### 5.2 courses

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK | |
| name | TEXT | 课程名 / 实验名 |
| teacher | TEXT | 老师 |
| room | TEXT | 教室 |
| day | INTEGER | 1=周一 … 7=周日 |
| start / end | INTEGER | 起止小节（含） |
| weeks | TEXT | 规范化周次，如 `1-5,7-11`、`1-16\|1` |
| note | TEXT | 备注（QQ群、雨课堂、教材等） |
| color | INTEGER | 颜色索引，-1 自动 |
| code | TEXT | 课程号 |
| clazz | TEXT | 教学班组成 |
| credit | TEXT | 学分 |
| campus | TEXT | 校区 |
| exam_type | TEXT | 考核方式 |
| hours | TEXT | 总学时 |
| type | INTEGER | 0=课程，1=实验 |
| parent | TEXT | 实验所属课程 |
| semester_id | INTEGER | 所属学期 |

### 5.3 exams

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK | |
| course | TEXT | 课程名 |
| time | TEXT | `yyyy-MM-dd HH:mm-HH:mm` |
| place | TEXT | 考场 |
| seat | TEXT | 座位号 |
| note | TEXT | 校区 / 考试方式 |
| semester_id | INTEGER | 所属学期 |

### 5.4 迁移策略

- 数据库版本 3。
- `onUpgrade` / `onOpen` 均执行 `ensureColumns`：按 `PRAGMA table_info` 缺列补齐，每一列独立 try，避免单列失败影响整体。
- `ensureSemesters`：无学期时创建默认学期，并把 `semester_id=0` 的课程/考试归入该学期；旧版本升上来不会丢数据。
- 读取时按列名取值（`str(cursor, "column")`），列不存在返回空串，保证旧库可读。

---

## 6. 课表视图设计

`TimetableView` 是自绘 View，负责网格、课程块、考试块、日期表头与手势。

### 6.1 布局

- 左侧时间栏 `leftWidth = 42dp`
- 表头 `headerHeight = 40dp`，两行：星期 + `M/d`
- 7 列等宽；行高 = 可用高度 / 小节数
- 默认 12 小节；南望山默认 10 小节，11/12 留空

### 6.2 三页横滑

- 同时绘制 `week-1 / week / week+1` 三页
- 手势拖动时按 `scrollOffset` 平移；松手后由 `Scroller` 吸附到目标页
- 滑动超过 16% 屏宽或快速轻扫即切换；边界周有阻尼
- 吸附完成回调 `onWeekChanged(delta)`，由 Activity 更新周次与数据
- 边界周（第 1 周 / 最后一周）不绘制越界页

### 6.3 日期与今日

- `semesterStart + (week-1)*7` 推算 7 天日期
- `todayWeek = Weeks.weekOf(semesterStart, today)`；只有 `week == todayWeek` 时才绘制今日列底色与蓝色日期
- 周次按日历日计算，日期归一化到 UTC 零点，避免毫秒差/夏令时造成差一天
- 冷启动、回前台、点顶部周次、前台跨午夜都会强制定位到当前周
- 手动左右翻周只在本次前台会话内有效；切后台再回来会回到当前周
- 开学日以当前学期数据库记录为准，并同步到 Prefs

### 6.4 块绘制

- 普通课程：按课程名 hash 选色，半透明填充 + 同色描边
- 高度 = `end - start + 1` 行，天然实现「一节大课占两个格子」
- 文本三行：课程名（最多 2 行）→ 教室 → 老师
- 缺失字段显示 `Null`，保持行高与对齐
- 老师名为 2–3 个汉字时，在 7.5–9sp 范围内缩小字号直到放下，不省略；4 字及以上才截断

### 6.5 手势

- 点击课程块：打开详情
- 点击左侧时间栏：修改该小节时间
- 点击空白格：在该天/该节新建课程或实验
- 长按课程块：进入拖动；拖块体换天/换节，拖下沿改结束节
- 拖动结束前检查冲突；冲突则回滚并提示

---

## 7. 导入管线

### 7.1 教务网页导入（课表）

```
用户打开 WebView
   ↓ 学校登录 / 查询
点击「提取课表」
   ↓ evaluateJavascript(document.documentElement.outerHTML)
looksLikeTimetable() 校验
   ↓
HtmlCourseParser.parse()
   ↓ 失败则 document.body.innerText → TextCourseParser
ImportPreviewActivity（可编辑预览）
   ↓ 追加 / 覆盖
Db（写入当前学期）
```

特点：

- 只读取**当前渲染后的 DOM**，不模拟登录、不请求接口
- 正方菜单常用 `window.open` / `target=_blank` 打开子页面；WebView 开启多窗口，`onCreateWindow` 把新窗口 URL 接回主 WebView
- `looksLikeTimetable` 至少识别 4 个星期名 + 含「节/周」才继续，避免在登录页误解析
- 覆盖保存只删除当前学期的课程

### 7.2 考试导入

流程与课表一致，读取「考试信息查询」页：

```
HtmlExamParser.parse()
   ↓ jqGrid → 通用表格 → 行启发式 → 纯文本
ExamPreviewActivity
   ↓ 追加 / 覆盖
Db
```

### 7.3 PDF 导入

- `ACTION_OPEN_DOCUMENT` 选择 PDF
- `PdfTable.extract()` 读取前 3 页表格 → `GridCourseParser`
- 表格解析为空时，`PDFTextStripper` 取全文 → `TextCourseParser`
- 仍为空则把文本带入 `TextImportActivity` 供用户修改

### 7.4 文本导入

`TextCourseParser` 按行解析：周几、节次、周次、地点、老师；适合从任何来源复制的课表文本。

### 7.5 手动与预览

- 空白格点击 → 新建对话框预填星期/起始节，结束节默认 +1
- 预览页统一展示「时间 / 教室 / 老师」，保存前可核对

---

## 8. 解析器设计

### 8.1 正方格子视图（首选）

DOM 特征：

```html
<td rowspan="2" id="3-5"> ... <div class="timetable_con"> ... </div> </td>
```

- `id="天-节"` 直接给出星期与起始节
- `rowspan` 给出跨节数
- 一个 `<td>` 内可能有多个 `.timetable_con`（不同周次的两门课）
- 课程块按 `<div>` 深度提取，兼容内部嵌套标签
- 课程名按 `<span>` 深度提取，兼容 `【调】` 等内嵌标记，并清理调度标记
- 字段来自 `<p>` 内 `title` 属性：节/周、上课地点、教师、教学班名称、教学班组成、考核方式、选课备注、周学时、总学时、学分

设计：**不使用 `<td>` 列下标映射星期**，避免 rowspan/colspan 造成的整体错位。

### 8.2 列表视图（次选）

```html
<td id="jc_3-1-2"><span class="festival">1-2</span></td>
```

`jc_天-起-止` 同样直接给出坐标。

### 8.3 考试 jqGrid

```html
<td aria-describedby="tabGrid_kcmc">课程名</td>
<td aria-describedby="tabGrid_kssj">2026-11-28(19:00-21:00)</td>
<td aria-describedby="tabGrid_cdmc">公教1-401</td>
<td aria-describedby="tabGrid_zwh">5</td>
```

按字段名取值，不依赖列顺序。

### 8.4 周次

`Weeks.extract` 统一规范：

| 输入 | 输出 |
|---|---|
| `1-5,7-11周` | `1-5,7-11` |
| `1-16周(单)` | `1-16\|1` |
| `2-18周（双）` | `2-18\|2` |
| `1,3,5周` | `1,3,5` |

`Course.parseWeeks` 在展示与冲突判断时展开为集合。

### 8.5 兜底

1. 格子/列表 DOM 解析
2. 通用 `<table>` 表头 + 行列
3. 纯文本逐行解析
4. 预览页人工修正

---

## 9. 学期状态机

```
[无学期] --首次启动--> [默认学期(当前)]
[当前学期] --新建--> [新当前学期] + 旧学期进入历史
[当前学期] --切换--> [历史学期(当前)]；原当前进入历史
[历史学期] --删除--> 删除其课程/考试；若删的是当前，切到剩余第一个
```

规则：

- 任意时刻只有一个 `currentSemesterId`
- 课程/考试的 `semester_id` 决定归属
- 导入始终写入当前学期
- 学期面板：当前学期固定顶部；历史课表按 `sort` 排序，可拖拽调整
- 切换学期时同步 `semesterStart`，并重算当前周；历史学期默认从第 1 周开始
- 设置里的开学日同时更新 `Prefs` 与当前学期行

---

## 10. 考试与实验

### 10.1 考试

- 数据：课程、时间、考场、座位、备注
- 课表落位：日期 → 周次（`(date-start)/7+1`）；开考时间 → 最接近的小节；结束时间 → 跨节
- 块样式：红色描边 + 「考试」标签 + 课程名 + 考场 + 开考时间
- 点击考试块：详情（时间/地点/座位/备注），可跳转考试管理
- 小组件：最近 30 天内最近一场

### 10.2 实验

- `type=1`，`parent` 记录所属课程
- 不参与教务导入
- 块样式：琥珀色 + 「实验」标签
- 与普通课程共享拖动、冲突、周次逻辑

---

## 11. 主题与背景

### 11.1 调色板

`Theme` 提供 light/dark 两套 `Palette`（背景、卡片、线、主/次文字、弱文字、主色、危险色）。所有自绘与 `Ui` 组件从调色板取色。

主题切换通过 `Theme.prepare(Activity)` 在 `setContentView` 前设置 `AppTheme` / `AppTheme.Dark`；返回主界面时比较主题签名决定是否 `recreate()`。

### 11.2 背景图

- 用户选图 → 复制到应用私有目录 `theme_pick`
- 进入 `CropActivity`：固定屏幕比例裁剪框，拖动/双指缩放，框外压暗
- 保存：按裁剪框取源图矩形，缩放到 1080 宽，写 `theme_bg`
- 原图保留为 `theme_src`，支持「重新裁剪」
- 主界面窗口背景使用裁剪结果；网格区域透明，露出背景
- 背景遮罩：0–80%，黑色（深色主题）/白色（浅色主题）叠加
- 上边栏透明度：0–100%，作用于顶部标题栏和课表星期表头
- 侧边栏透明度：0–100%，作用于课表左侧节次/时间栏
- 0% 为不透明，100% 为完全透明；无背景图时保持纯色
- 网格线清晰度：0–100%，作用于所有网格线

---

## 12. 桌面小组件

### 12.1 结构

- `TimetableWidgetProvider extends AppWidgetProvider`
- `RemoteViews` 布局：标题 + 周次 + 考试行 + 6 个固定课程行 + 空状态
- 根据 `OPTION_APPWIDGET_MAX_HEIGHT` 决定显示 1–6 行
- 4×2 默认，支持横向/纵向拉伸到 4×4

### 12.2 数据

- 当前学期、开学日、作息表、今日课程、最近考试
- 课程行：开始时间、课程名（实验加前缀）、教室
- 当前正在上的课用主色高亮
- 考试行：`N 天后 · MM-dd：课程 HH:mm 考场`

### 12.3 更新

- 数据变化：保存/删除课程、导入、切换学期、修改作息/开学日、主题变化
- 时间变化：下一节课开始/结束、次日 0 点（AlarmManager，`setAndAllowWhileIdle`）
- 系统事件：开机、时间/时区变化、应用更新
- 点击：打开 `MainActivity`
- 设置页提供 `requestPinAppWidget` 一键固定；不支持的桌面提示手动添加

### 12.4 限制

- RemoteViews 不支持自绘、复杂动画、自定义字体
- 不支持自定义背景图
- 不是 HarmonyOS 服务卡片

### 12.5 内置更新

更新链路：

```
启动 / 设置 → 检查更新
  → GET api.github.com/repos/39-Null/cug-timetable/releases/latest
  → 取 tag、body、assets[].browser_download_url、size、digest
  → 用户确认
  → 下载 APK 到 cache
  → SHA-256 + 包名 + versionCode + 签名校验
  → PackageInstaller 安装
  → 系统确认或自更新完成
```

约束：

- 只使用 GitHub 直链，不接第三方镜像
- 自动检查限制为 6 小时一次；手动检查不受限制
- 不自动下载，必须用户点「立即更新」
- 安装前校验签名必须与当前应用一致；签名不同则提示卸载重装
- Android 8+ 需要「安装未知应用」授权；Android 12+ 使用 mutable PendingIntent；Android 14+ 兼容 `STATUS_PENDING_USER_ACTION`
- 权限：`REQUEST_INSTALL_PACKAGES`、`UPDATE_PACKAGES_WITHOUT_USER_ACTION`

备份接口：

- 当前版本已移除设置页的导出/导入入口
- 后端保留 `Backup`、`Db.restoreAll`、`Prefs.restoreFrom`，后续版本或内部调用可直接复用
- `Backup` 支持 JSON 全量导出/导入：学期、课程、考试、设置、作息时间和背景图 Base64
- 导入先完整解析和校验，再在一个 SQLite 事务里替换全部数据；失败回滚

---

## 13. 冲突与数据校验

冲突判定：

```
同一天
且 节次区间相交
且 周次集合相交
且 不是同一条记录
```

应用位置：

- 手动新增/编辑保存前
- 拖拽调整后
- 导入覆盖前（列表内部冲突）
- 追加导入时逐条跳过冲突

周次不重叠的课程允许共用同一格，这是教务排课的真实情况（如前 9 周一门、后 8 周另一门）。

时间校验：

- 格式 `HH:mm-HH:mm`
- 结束晚于开始
- 单节 20–240 分钟

---

## 14. 构建与发布

无 Gradle，构建链：

```
aapt2 compile → aapt2 link → javac → jar → d8
→ 写入 classes*.dex + assets → zipalign → apksigner
```

- `compileSdk` 使用已安装的最新 platform
- `minSdk 24`，`targetSdk 35`
- PdfBox 依赖由 `scripts/fetch-pdfbox.ps1` 从 Maven Central 拉取 AAR 并解出 `classes.jar` 与 `assets/`
- 版本号只从 `AndroidManifest.xml` 读取
- 正式构建固定使用 `keystore/release.keystore`，密码从 `keystore/release.pass` 或 `CUG_KEYSTORE_PASS` 读取
- keystore 不进入仓库；丢失后无法覆盖升级，务必单独备份

---

## 15. 隐私与安全

- 应用不收集账号、不上传课表
- 登录在 WebView 内完成，应用不读取输入框、不保存密码
- 不读取/导出 Cookie
- 数据仅存应用私有 SQLite / SharedPreferences
- 不落盘教务页面 HTML
- `allowBackup=false`，避免课程数据进入系统云备份
- WebView 只允许 http/https 导航；弹窗 URL 取到后销毁临时 WebView
- `network_security_config` 默认禁止明文，仅 `cug.edu.cn` 子域放行 HTTP
- 权限：`INTERNET`（WebView / GitHub 更新）、`RECEIVE_BOOT_COMPLETED`（小组件跨天刷新）、`REQUEST_INSTALL_PACKAGES` / `UPDATE_PACKAGES_WITHOUT_USER_ACTION`（应用内更新）
- 更新检查只请求 GitHub Releases，不上传课表、账号或备份文件
- 备份文件由用户主动导出，内容包含个人课表，需自行妥善保管
- 教务入口可能使用明文 HTTP（学校统一认证配置），应用无法改变；界面不承诺传输层加密

---

## 16. 可靠性与降级

- 解析四层兜底，任何页面都不至于崩溃
- 数据库列缺失自动补齐；旧库可读
- 导入覆盖只影响当前学期
- 主题/背景变更通过签名比较触发重建，避免缓存不一致
- 小组件定时失败不影响应用内数据
- 更新下载失败、校验失败、签名不一致均不修改现有数据
- 备份导入先解析校验，再事务替换；失败回滚
- 所有危险操作（覆盖导入、删除学期、清空当前学期、导入备份）二次确认

---

## 17. 测试与验收

功能验收清单：

- [x] 教务网页导入 16 门课程，星期/节次/周次/教室/老师正确
- [x] 考试页导入 4 场考试，考场与座位正确
- [x] 课表红块落在正确周次与节次
- [x] 左右滑动切周，日期表头跟随
- [x] 只有系统当天所在周高亮当天
- [x] 新建/切换/删除学期，历史课表拖拽排序
- [x] 空白格新建实验，跨两节显示
- [x] 冲突保存被拦截
- [x] 深色主题、背景手动裁剪、遮罩、网格线
- [x] 小组件添加、显示、点击打开、考试提醒
- [x] 南望山夏/冬作息自动切换
- [x] 设置 → 检查更新返回「已是最新版本」
- [x] 备份后端接口（Backup / Db / Prefs）往返自测通过；前端入口已移除
- [x] 新版本下载、SHA-256/签名校验、PackageInstaller 安装全流程真机验证
- [x] Android 12 安装未知应用授权后继续更新，升级后课表数据保留
- [x] 左右滑动切周阈值缩短到 16% 屏宽；短滑不误切，快速轻扫可切周
- [x] 设置页已无数据备份入口；`Backup` / `Db.restoreAll` / `Prefs.restoreFrom` 后端接口保留
- [x] 教务导入的电脑/手机模式切换后按钮标签、UA 与页面刷新同步生效
- [x] 设置页「检查更新」下方说明文字已移除
- [x] 新增课程周次默认当前周单周
- [x] 新增课程详细信息默认收起，可点击展开；编辑已有课程默认展开
- [x] 冷启动、回前台、点周次标签都定位到当前周
- [x] 手动翻周后切后台再回前台，回到当前周
- [x] 前台跨午夜自动刷新日期与当前周
- [x] 开学日以当前学期数据库记录为准，Prefs 自动同步
- [x] 课程节次超出范围、周次非法时拒绝保存
- [x] 新建/编辑学期拒绝重名与非法开学日
- [x] 追加导入使用事务，批次内冲突被拦截
- [x] 背景图裁剪边缘不越界
- [x] WebView 只允许 http/https，导入页退出销毁 WebView
- [x] 更新包临时文件、完整性校验与全部 dex 打包
- [x] 上边栏/侧边栏透明度独立可调，0% 不透明、100% 完全透明
- [x] 课程标题内嵌 `【调】` 时课程名完整解析，字段不再错位

---

## 18. 已知限制与路线图

限制：

- 仅 CUG 正方解析器
- 教务改版可能导致解析失败，需要更新解析器
- WebVPN 场景下 DOM 可能被包一层 frame
- 小组件刷新受 Doze 影响，可能有分钟级延迟

路线图：

- 多学校配置（School Profile）与解析器插件化
- 课程提醒通知
- 桌面小组件更多尺寸/样式
- 成绩查询（只读）
- 数据导入/导出（JSON）
