# 构建与签名

本项目不使用 Gradle / AndroidX，构建链为：

```
aapt2 compile → aapt2 link → javac → jar → d8
→ 写入 classes.dex 与 assets → zipalign → apksigner
```

---

## 1. 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Windows 10/11 + PowerShell 5+ |
| JDK | JDK 8 或更高（`JAVA_HOME` 或 PATH 中的 javac/java/jar/keytool） |
| Android SDK | 至少一个 `build-tools` 与一个 `platform`（含 `android.jar`） |
| 网络 | 首次构建需要联网下载 PdfBox |

SDK 查找顺序：

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. `%LOCALAPPDATA%\Android\Sdk`

build-tools 与 platform 自动取已安装的最高版本。

---

## 2. 首次构建

```powershell
# 下载 PdfBox-Android 2.0.27.0，解出 libs/pdfbox-classes.jar 与 libs/assets/
powershell -ExecutionPolicy Bypass -File scripts/fetch-pdfbox.ps1

# 构建
powershell -ExecutionPolicy Bypass -File build.ps1
```

输出：

```
build/cug-timetable.apk
```

安装：

```powershell
adb install -r build/cug-timetable.apk
```

---

## 3. 依赖说明

- PdfBox-Android `2.0.27.0`（Apache-2.0）
- 来源：Maven Central
  `com.tom-roush:pdfbox-android:2.0.27.0`
- `scripts/fetch-pdfbox.ps1` 会从 AAR 中提取：
  - `classes.jar` → `libs/pdfbox-classes.jar`
  - `assets/` → `libs/assets/`

`libs/` 被 `.gitignore` 忽略，不进入仓库。

---

## 4. 签名

### 4.1 本地调试

`build.ps1` 首次运行会自动生成：

```
keystore/debug.keystore
alias: androiddebugkey
storepass / keypass: android
```

该文件已被 `.gitignore` 忽略。用它签出的 APK 只适合本机安装/升级。

### 4.2 正式发布

1. 用 `keytool` 生成自己的 keystore
2. 修改 `build.ps1` 末尾的 `apksigner` 参数，或构建后手动签名：

```powershell
apksigner sign --ks my-release.jks --out cug-timetable-release.apk build/aligned.apk
```

3. **务必保管好 keystore**；丢失后无法覆盖安装升级

---

## 5. 常见问题

**`Android SDK not found`**

- 设置 `ANDROID_HOME`，或安装到默认路径

**`missing JDK tool`**

- 安装 JDK 并设置 `JAVA_HOME`，确保 `bin/javac.exe` 存在

**`PdfBox not found`**

- 手动执行 `scripts/fetch-pdfbox.ps1`
- 或从 Maven Central 下载 AAR 后按脚本方式解压

**安装时报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**

- 新旧 APK 签名不同。先卸载旧版（会清数据），或使用同一个 keystore 重新签名

**`aapt2` 报资源错误**

- 检查 `res/` 下是否有非法文件名（只允许小写字母、数字、下划线）
- 本项目的图标位于 `res/drawable-*/ic_launcher.png`

**Windows 下 assets 路径写成反斜杠**

- 构建脚本使用 .NET `ZipArchive` 写入 assets，不使用 `aapt2 -A`
