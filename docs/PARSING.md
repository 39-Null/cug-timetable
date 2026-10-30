# 教务页面解析说明

本文记录中国地质大学（武汉）正方教务（jwglxt）课表页与考试页的 DOM 结构，以及本项目解析器的取值规则。

> 教务系统升级后 DOM 可能变化；解析器均带多级兜底，失败时进入预览/文本编辑页。

---

## 1. 课表页：格子视图

正方课表页的每个课程格通常形如：

```html
<td rowspan="2" id="3-5" class="td_wrap">
  <div class="timetable_con text-left">
    <span class="title"><font color="blue">高等数学A*</font></span>
    <p><span title="节/周">...</span><font> (5-6节)1-5周,7-11周</font></p>
    <p><span title="上课地点">...</span><font> 未来城校区  公教2-305</font></p>
    <p><span title="教师 ">...</span><font> 张三</font></p>
    <p><span title="教学班名称">...</span><font> 高等数学A-0002</font></p>
    <p><span title="教学班组成">...</span><font> 191241;191242</font></p>
    <p><span title="考核方式">...</span><font> 考试</font></p>
    <p><span title="选课备注">...</span><font> QQ群：123456，雨课堂</font></p>
    <p><span title="课程学时组成">...</span><font> 讲课:40,实验:16</font></p>
    <p><span title="总学时">...</span><font> 40</font></p>
    <p><span title="学分">...</span><font> 2.5</font></p>
  </div>
</td>
```

解析规则：

1. **坐标来自 `id="天-节"`**，不用 `<td>` 的列下标。`id="3-5"` 表示周三第 5 节。
2. **跨节数来自 `rowspan`**。`rowspan="2"` → 第 5-6 节。
3. 一个 `<td>` 内可能有多个 `.timetable_con`（同一格、不同周次的两门课），逐个解析。
4. 字段通过 `<p>` 内 `title` 属性识别：
   - `节/周`：节次与周次
   - `上课地点`：`未来城校区  公教2-305` → 拆成 campus + room
   - `教师`：老师
   - `教学班名称`：课程号（如 `高等数学A-0002`）
   - `教学班组成`：班级/学号串
   - `考核方式`：考试/考查
   - `选课备注`：QQ群、雨课堂、教材
   - `总学时` / `学分`
5. 课程名取 `.title` 文本，去掉末尾 `*`。

> 早期按列下标映射是因为只考虑了简单表格；含 `rowspan` 时同一行里被合并的格子不出现，列下标会整体左移，所以必须用 `id`。

---

## 2. 课表页：列表视图

部分页面提供列表视图：

```html
<td id="jc_3-1-2"><span class="festival">1-2</span></td>
```

`jc_天-起-止` 直接给出星期与节次区间，单元格内的 `.timetable_con` 与格子视图一致。

---

## 3. 考试页：jqGrid

考试信息查询页使用 jqGrid，字段在 `aria-describedby` 中：

```html
<td aria-describedby="tabGrid_kcmc">高等数学A</td>
<td aria-describedby="tabGrid_kssj">2026-11-28(19:00-21:00)</td>
<td aria-describedby="tabGrid_cdmc">公教1-401</td>
<td aria-describedby="tabGrid_cdxqmc">未来城校区</td>
<td aria-describedby="tabGrid_zwh">5</td>
<td aria-describedby="tabGrid_ksfs">笔试</td>
```

映射：

| key | 含义 |
|---|---|
| `kcmc` | 课程名 |
| `kssj` | 考试时间 |
| `cdmc` | 考场 |
| `cdxqmc` | 校区 |
| `zwh` | 座位号 |
| `ksfs` | 考试方式 |

时间统一归一化为 `yyyy-MM-dd HH:mm-HH:mm`。

---

## 4. 周次解析

`Weeks.extract` 将多种写法归一：

| 原始 | 规范化 |
|---|---|
| `1-5,7-11周` | `1-5,7-11` |
| `第1-5,7-11周` | `1-5,7-11` |
| `1-16周(单)` | `1-16\|1` |
| `2-18周（双）` | `2-18\|2` |
| `1,3,5周` | `1,3,5` |

`Course.parseWeeks` 展开为 `Set<Integer>`，用于：

- 判断某一周是否显示该课程
- 冲突检测：周次集合有交集才算冲突

---

## 5. 字段清洗

- 课程名：去尾部 `*`/空格/残留括号
- 教室：去掉校区前缀，空格折叠
- 课程号：去掉连字符两侧空格
- 班级：`;` 转空格
- 学分/学时：只保留数字与小数点
- 备注：`QQ群`/`qq群` 统一大小写；纯数字 QQ 群补 `QQ群 ` 前缀

---

## 6. 兜底顺序

```
格子视图 id 解析
  → 列表视图 jc_ 解析
  → 通用 table 表头/行列
  → 纯文本逐行
  → 预览页人工修正
```

考试：

```
jqGrid 字段
  → 通用表格表头
  → 行内日期/时间/考场启发式
  → 纯文本
```

---

## 7. 适配新页面

1. 在教务页面点一次「提取课表/提取考试」之前，先确认页面已渲染出结果
2. 若解析失败，检查：
   - 课程格是否有 `id="天-节"`
   - 字段是否仍在 `title` 属性里
   - 考试页字段名是否仍为 `tabGrid_*`
3. 新增解析分支时，优先按语义字段（id、aria-describedby、title）取值，避免依赖列顺序
4. 保留兜底链，任何单点失败都不应导致应用崩溃

---

## 8. 菜单弹窗

教务左侧菜单常用 `window.open(url)` 或 `<a target="_blank">` 打开「学生个人课表」「考试信息查询」等子页面。

WebView 默认不处理多窗口请求，点击会表现为**无反应**。应用侧必须：

```
setSupportMultipleWindows(true)
setJavaScriptCanOpenWindowsAutomatically(true)
WebChromeClient.onCreateWindow(...)
```

`onCreateWindow` 中创建一个临时 WebView，拦截其首个 URL，转交主 WebView：

```
mainWeb.loadUrl(url)
```

这样菜单子页面会在同一个 WebView 内打开，后续「提取课表/提取考试」才能读到 DOM。
