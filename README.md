# 泥地课表 · CUG Timetable

面向 **中国地质大学（武汉）** 本科生的本地课表 Android 应用。

通过学校教务网页登录后导入课表与考试，全部数据保存在本机；无广告、无账号体系、无自建服务器。

> 应用桌面名称为「泥地课表」，界面内标题为「我的课表」。

---

## 功能

### 课表

- 周一至周日 × 12 小节网格（南望山 10 小节），左右滑动平滑切周
- 表头显示 `周一 10/26` 形式日期；**只有系统日期所在的那一周、那一天才高亮**
- 周次支持 `1-5,7-11`、`1-16周(单)`、`2-16周(双)` 等写法
- 一门课可跨多节显示（如 1-2 节），长按块体拖动换天/换节，拖下沿改结束节次
- 课程块显示课程名 / 教室 / 老师，缺失显示 `Null`；3 个汉字的老师名会缩小字号完整显示

### 导入

- **教务网页导入**：应用内 WebView 打开正方教务，用户自行登录并查询，点击「提取课表」读取当前页面 DOM 并本地解析
- **PDF 导入**：系统文件选择器选 PDF，本地解析表格或文本；失败自动进入文本编辑页
- **文本导入**：粘贴任意含「周几 / 第几节 / 周次 / 地点」的文本
- **手动添加**：点课表空白格即可在该位置新建课程
- 导入前有预览页，可追加或覆盖当前学期

### 考试

- 从教务「考试信息查询」页导入（正方 jqGrid）
- 也可手动添加或粘贴文本导入
- 按考试日期换算周次、按开考时间落到课表大致节次，显示为红色「考试」块
- 桌面小组件显示 **最近 30 天内最近的一场考试**

### 学期档位

- 每个学期独立保存课程与考试
- 点底部「课表」打开学期面板：当前学期 + 历史课表
- 支持新建、重命名、删除；历史课表卡片左侧 `≡` 可拖拽排序
- 导入永远写入当前选中的学期
- 旧版本数据首次启动会自动归入默认学期

### 实验

- 课程之外的第二种块类型，琥珀色「实验」标签
- 只能手动创建，不支持教务导入
- 可填写所属课程；交互与普通课程一致（拖动、改时长）

### 冲突与校验

- 同一天、节次重叠、**周次有交集**才判定为冲突；周次不重叠的课可以共用一格
- 手动保存、拖拽调整、导入覆盖前都会检查
- 侧边栏每小节时间可改，校验 `HH:mm-HH:mm` 格式、先后顺序与 20–240 分钟时长

### 主题

- 深色 / 浅色主题，对话框、导入页、小组件一起跟随
- 本地图片背景：选图后进入**手动裁剪**页，拖动/双指缩放决定可见区域，按屏幕比例保存，不做二次拉伸
- 背景遮罩 0–80%、网格线清晰度 0–100%

### 桌面小组件

- 标准 Android AppWidget（非 HarmonyOS 服务卡片）
- 4×2 默认，可拉伸到 4×4，按高度显示 1–6 行
- 显示：第几周 + 今天星期、今日课程、最近考试
- 点击任意位置打开 App；支持设置页一键固定到桌面

---

## 截图

仓库不包含任何个人课表截图。可参考 `docs/USAGE.md` 的界面说明。

---

## 安装

从 Releases 下载 APK，或自行构建（见下）。

- 最低系统：Android 7.0（API 24）
- 目标 SDK：35
- 包名：`io.local.timetable`

首次使用建议先到「设置」填写 **开学第一周周一**，日期表头与自动周次才会生效。

---

## 构建

本项目**不使用 Gradle / AndroidX**，用 `aapt2 + javac + d8 + zipalign + apksigner` 手工构建，便于在无 Android Studio 的环境复现。

```powershell
# 1. 取 PdfBox 依赖（首次需要联网）
powershell -ExecutionPolicy Bypass -File scripts/fetch-pdfbox.ps1

# 2. 构建
powershell -ExecutionPolicy Bypass -File build.ps1
# 输出: build/cug-timetable.apk
```

需要：Windows PowerShell 5+、JDK 8+、Android SDK（build-tools + platform）。

详细环境变量、签名与常见问题见 [docs/BUILD.md](docs/BUILD.md)。

---

## 项目结构

```
.
├─ AndroidManifest.xml
├─ build.ps1                  # aapt2 → javac → d8 → 打包 → 对齐 → 签名
├─ scripts/fetch-pdfbox.ps1   # 下载 PdfBox-Android 并解出 jar/assets
├─ java/io/local/timetable/
│  ├─ MainActivity.java       # 课表主页、学期、设置、导入入口
│  ├─ WebImportActivity.java  # 教务课表导入
│  ├─ ExamWebImportActivity.java
│  ├─ ImportPreviewActivity.java / ExamPreviewActivity.java
│  ├─ ThemeActivity.java / CropActivity.java
│  ├─ db/Db.java              # SQLite：学期 / 课程 / 考试
│  ├─ model/                  # Course / Exam / Semester
│  ├─ parse/                  # 正方课表、考试、文本、PDF 表格解析
│  ├─ ui/TimetableView.java   # 自绘课表、三页滑动、拖拽
│  ├─ ui/CropView.java        # 背景手动裁剪
│  ├─ widget/                 # AppWidget Provider / RemoteViews
│  └─ util/                   # Prefs / Weeks / Theme / Ui
├─ res/
│  ├─ layout/                 # 主界面、小组件、预览
│  ├─ drawable-*/ic_launcher.png
│  └─ values/                 # 颜色、主题、字符串
└─ docs/
   ├─ DESIGN.md               # 成品设计文档
   ├─ USAGE.md                # 使用说明
   ├─ BUILD.md                # 构建与签名
   ├─ PARSING.md              # 教务 DOM 解析说明
   ├─ PRIVACY.md              # 隐私与权限
   └─ CHANGELOG.md
```

---

## 技术栈

- Java 8，纯 Android Framework（无 AndroidX、无 Compose）
- 自绘 `View` 课表 + `Scroller` 三页横滑
- SQLite（`SQLiteOpenHelper`），不使用 Room
- WebView 内完成学校登录，`evaluateJavascript` 读取渲染后 DOM
- PdfBox-Android 2.0.27.0（Apache-2.0）解析 PDF
- `AppWidgetProvider` + `RemoteViews` 实现桌面小组件

---

## 隐私

- 不收集账号、不上传课表、不接入统计/广告 SDK
- 登录完全发生在学校页面的 WebView 中，应用不读取、不保存密码
- 课程、考试、学期数据只存在应用私有 SQLite
- 不落盘任何教务页面 HTML
- 详细说明见 [docs/PRIVACY.md](docs/PRIVACY.md)

---

## 已知限制

- 仅适配中国地质大学（武汉）正方教务；其他学校需要新增解析器
- 校外访问教务可能需要学校 WebVPN
- 桌面小组件是标准 Android AppWidget，无法注册为 HarmonyOS 服务卡片
- 不支持 iOS / 鸿蒙原生
- 教务页面改版后解析器可能需要同步调整

---

## 免责声明

本项目为非官方个人项目，与学校及教务系统厂商无关。请仅使用自己的账号导入自己的课表，并遵守学校相关管理规定。

---

## 许可证

代码采用 [MIT License](LICENSE)。

应用图标（`res/drawable-*/ic_launcher.png`）由项目所有者提供，不属于 MIT 许可范围。
