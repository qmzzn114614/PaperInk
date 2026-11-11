# 纸墨 PaperInk 插件市场（paperink-plugins）托管与发布指南

> 本文说明纸墨 App 的**插件市场索引仓库**如何搭建、托管、维护，以及插件作者如何把自己的插件提交到市场。
> 插件本身是一个**独立 APK**，通过系统安装到平板上；市场仓库只存放一个 `index.json` 索引和每个插件的说明页。
>
> 文中所有 `qmzzn114614` 均为占位符，复制命令前请整体替换（连同尖括号）成真实用户名。

---

## 1. 整体思路

```text
插件作者                          市场仓库（paperink-plugins）                 用户平板
────────                          ──────────────────────────                  ────────
开发独立 APK                       index.json（插件清单）                      打开「插件」面板
  → 签名、构建                       plugins/<插件id>/README.md                 → App 拉取 index.json
  → 传 GitHub Release                plugins/<插件id>/icon.png                 → 看到插件列表
  → Fork + 改 index.json             .nojekyll                                 → 点条目下载/安装 APK
  → 提 Pull Request                  → GitHub Pages 托管                        → App 扫描并加载插件
```

- **索引仓库固定叫 `paperink-plugins`**，与主仓库 `PaperInk` 分开：
  - 主仓库只放 App 源码；
  - 插件市场独立托管，插件作者只需要对 `paperink-plugins` 提 PR，不需要动主仓库。
- App 端从市场索引的 URL 拉取 `index.json`，解析出插件列表（名称、简介、版本、下载地址等）。
- App 当前的市场入口在**书架首页 →「插件」面板**内。

---

## 2. 市场仓库目录结构

```text
paperink-plugins/
├─ index.json                         # 插件清单（App 拉取的就是它）
├─ .nojekyll                          # 空文件：告诉 GitHub Pages 不要跑 Jekyll
├─ README.md                          # 市场说明、插件列表表格、提交方式
└─ plugins/
   ├─ com.example.drawboardbookmark/  # 插件 id（与 APK 内声明一致）
   │  ├─ README.md                    # 插件详情：功能、权限、截图、版本历史
   │  └─ icon.png                     # 列表图标（可选，建议 128×128）
   └─ com.example.ocr/
      ├─ README.md
      └─ icon.png
```

> 约定：`plugins/<id>/` 目录名必须与 `index.json` 里的 `id`、以及插件 APK 内 `meta-data` 声明的插件 id 完全一致。插件 id 推荐直接使用插件 APK 的 `applicationId`（如 `com.example.ocr`），避免重名。

---

## 3. `index.json` 完整示例

`index.json` 是一个 UTF-8 编码的 JSON 对象。推荐最简结构如下（包含两个示例插件）：

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-11-11T00:00:00+08:00",
  "marketName": "纸墨 PaperInk 插件市场",
  "plugins": [
    {
      "id": "com.example.drawboardbookmark",
      "name": "画板书签",
      "description": "在页面边缘画一个五角星即可添加书签，并在侧边栏显示自定义书签列表。",
      "version": "1.0.0",
      "author": "example",
      "authorUrl": "https://github.com/qmzzn114614",
      "apkUrl": "https://github.com/qmzzn114614/paperink-plugins/releases/download/drawboardbookmark-v1.0.0/drawboardbookmark-1.0.0.apk",
      "homepage": "https://github.com/qmzzn114614/paperink-plugins/tree/main/plugins/com.example.drawboardbookmark",
      "downloads": 0,
      "minHostVersion": "0.6.0",
      "tags": ["bookmark", "ink"],
      "capabilities": ["bookmark.provider", "panel.provider"],
      "sha256": "<64 位小写十六进制 SHA-256，构建后 Get-FileHash 得到>",
      "iconUrl": "https://raw.githubusercontent.com/qmzzn114614/paperink-plugins/main/plugins/com.example.drawboardbookmark/icon.png",
      "sizeBytes": 345678
    },
    {
      "id": "com.example.ocr",
      "name": "OCR 取字",
      "description": "对当前页做 OCR，把识别出的文字复制到剪贴板或导出的文本框里。",
      "version": "0.9.2",
      "author": "another-author",
      "authorUrl": "https://github.com/qmzzn114614",
      "apkUrl": "https://github.com/qmzzn114614/paperink-plugins/releases/download/ocr-v0.9.2/ocr-0.9.2.apk",
      "homepage": "https://github.com/qmzzn114614/paperink-plugins/tree/main/plugins/com.example.ocr",
      "downloads": 0,
      "minHostVersion": "0.6.0",
      "tags": ["ocr", "text"],
      "capabilities": ["action.provider", "panel.provider"]
    }
  ]
}
```

### 3.1 顶层字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | number | ✅ | 索引格式版本，当前固定为 `1`。App 遇到不认识的版本应忽略或提示升级 |
| `updatedAt` | string | ✅ | 最近更新时间，ISO 8601，建议带时区，如 `2026-11-11T00:00:00+08:00` |
| `marketName` | string | ❌ | 市场显示名称，缺省为「PaperInk 插件市场」 |
| `plugins` | array | ✅ | 插件条目数组，可以为空数组 |

### 3.2 插件条目字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | ✅ | 插件唯一 id，与 APK 内 `paperink.plugin.id` 及 `plugins/<id>/` 目录名一致；建议用 `applicationId` |
| `name` | string | ✅ | 显示名称，建议 ≤ 20 字 |
| `description` | string | ✅ | 一句话简介（建议 ≤ 80 字），列表页展示 |
| `version` | string | ✅ | 插件版本号，与 APK 的 `versionName` 一致，如 `1.0.0` |
| `author` | string | ✅ | 作者名/组织名 |
| `authorUrl` | string | ❌ | 作者主页或 GitHub 地址 |
| `apkUrl` | string | ✅ | APK 的 **https 直链**，推荐指向本仓库的 Release 附件（见第 5.3 节） |
| `homepage` | string | ❌ | 插件详情页（通常是本仓库 `plugins/<id>/` 的 GitHub 页面） |
| `downloads` | number | ❌ | 累计下载次数（手动维护或由 CI 统计），缺省 `0`；仅用于展示，不参与逻辑 |
| `minHostVersion` | string | ✅ | 要求的最低宿主版本，如 `"0.6.0"`；低于该版本的 App 应显示「需要升级 PaperInk」 |
| `tags` | string[] | ❌ | 分类标签，小写英文，如 `["ocr","bookmark","theme"]`，用于筛选 |
| `capabilities` | string[] | ✅ | 插件实现的能力，取值必须来自 `plugin-api` 的 `Capabilities`（见 3.3） |
| `sha256` | string | ❌（强烈建议） | APK 的 SHA-256（64 位小写十六进制），App 下载后可校验完整性 |
| `iconUrl` | string | ❌ | 图标 https 直链；缺省时 App 用默认图标 |
| `sizeBytes` | number | ❌ | APK 字节数，用于列表展示下载体积 |

### 3.3 `capabilities` 合法取值

来自 `plugin-api/src/main/java/com/dsh/paperink/plugin/PluginApi.kt` 中的 `Capabilities`：

| 取值 | 含义 |
| --- | --- |
| `document.format` | 提供新的文档格式解析（如 EPUB、Markdown） |
| `action.provider` | 往选字菜单 / 工具栏 / 长按菜单注入动作 |
| `panel.provider` | 往阅读界面注入侧边栏或悬浮面板 |
| `bookmark.provider` | 自定义书签识别与书签面板 |

> `capabilities` 必须与插件实际实现一致，不能虚报。App 会按能力把插件注册到对应能力表。

---

## 4. 把市场托管到 GitHub Pages

### 4.1 创建仓库

1. GitHub 右上角「+」→「New repository」；
2. 仓库名：`paperink-plugins`；
3. 可见性：必须是 **Public**（私有仓库的 Pages 与 Release 下载链接对普通用户不可用）；
4. 勾选「Add a README file」（这次可以勾，方便起步）；
5. 创建后把仓库克隆到本地：

```powershell
Set-Location D:\
git clone https://github.com/qmzzn114614/paperink-plugins.git
Set-Location .\paperink-plugins
```

### 4.2 放入 index.json 与插件说明

```powershell
# 在仓库根目录创建 .nojekyll（空文件，关闭 Jekyll，避免下划线开头文件被吞）
New-Item -ItemType File -Force .nojekyll | Out-Null

# 创建第一个插件的说明目录
New-Item -ItemType Directory -Force .\plugins\com.example.drawboardbookmark | Out-Null
Set-Content .\plugins\com.example.drawboardbookmark\README.md -Encoding utf8 `
    -Value "# 画板书签`n`n在页面边缘画五角星添加书签。`n"

# 按第 3 节示例写入 index.json（用支持 UTF-8 的编辑器保存，避免 BOM 问题）
notepad .\index.json
```

> 建议用 VS Code / Notepad++ 编辑 JSON，或用 PowerShell 校验（见 4.5 节）。JSON **不带 BOM** 更稳妥。

### 4.3 提交并推送

```powershell
git add .
git commit -m "feat: 初始化插件市场索引 index.json"
git push -u origin main
```

### 4.4 开启 GitHub Pages

1. 打开仓库网页 →「**Settings**」→ 左侧「**Pages**」；
2. 「Build and deployment」→ Source 选「**Deploy from a branch**」；
3. Branch 选 `main`，目录选 `/ (root)`，点「**Save**」；
4. 等 1–2 分钟，页面顶部会出现你的站点地址。

本站索引地址就是：

```text
https://qmzzn114614.github.io/paperink-plugins/index.json
```

在浏览器打开这个地址，能看到 JSON 内容就算成功。

> 如果 404：
> - 确认仓库是 Public、Pages 源分支/目录选对（`main` / root）；
> - 等待 1–2 分钟再刷新（首次部署较慢）；
> - 确认 `.nojekyll` 存在且文件名没有拼错；
> - 确认 `index.json` 在仓库根目录，而不是 `plugins/` 里。

### 4.5 发布前后校验

```powershell
Set-Location D:\paperink-plugins

# 1) JSON 语法校验
Get-Content .\index.json -Raw | ConvertFrom-Json | Select-Object schemaVersion, updatedAt

# 2) 检查重复 id
(Get-Content .\index.json -Raw | ConvertFrom-Json).plugins |
    Group-Object id | Where-Object Count -gt 1 | Select-Object Name, Count

# 3) 检查 sha256 格式（应为 64 位小写十六进制）
(Get-Content .\index.json -Raw | ConvertFrom-Json).plugins |
    Where-Object { $_.sha256 -and $_.sha256 -notmatch '^[0-9a-f]{64}$' } |
    Select-Object id, sha256

# 4) 检查 apkUrl / iconUrl 是否为 https 直链
(Get-Content .\index.json -Raw | ConvertFrom-Json).plugins |
    Where-Object { $_.apkUrl -notlike 'https://*' -or ($_.iconUrl -and $_.iconUrl -notlike 'https://*') } |
    Select-Object id, apkUrl, iconUrl
```

---

## 5. 地址格式（索引、图标、APK）

### 5.1 索引 `index.json` 的三种地址

| 方式 | 地址格式 | 说明 |
| --- | --- | --- |
| **GitHub Pages**（推荐） | `https://qmzzn114614.github.io/paperink-plugins/index.json` | 稳定、可缓存、支持自定义域名；修改后需等 Pages 部署 |
| raw.githubusercontent.com | `https://raw.githubusercontent.com/qmzzn114614/paperink-plugins/main/index.json` | 推送后立即可用；有 CDN 缓存（约 5 分钟），不适合高频更新 |
| jsDelivr CDN（可选） | `https://cdn.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/index.json` | 国内访问有时更稳；同样有 CDN 缓存 |

### 5.2 图标地址

```text
https://raw.githubusercontent.com/qmzzn114614/paperink-plugins/main/plugins/<id>/icon.png
```

### 5.3 插件 APK 地址（推荐 GitHub Release 附件）

**不要把 APK 提交进 Git 仓库历史**（每次发版都会让仓库膨胀）。正确做法是每个插件版本发一个 Release：

```text
https://github.com/qmzzn114614/paperink-plugins/releases/download/<标签>/<文件名>.apk
```

例如：

```text
https://github.com/qmzzn114614/paperink-plugins/releases/download/drawboardbookmark-v1.0.0/drawboardbookmark-1.0.0.apk
```

> 说明：
> - `apkUrl` 必须是**直链**，点击即开始下载，不能是仓库文件页或需要登录的页面；
> - Release 附件支持的最大单文件为 2 GB，插件 APK 一般只有几百 KB 到几 MB；
> - 同一插件的新版本可以复用同一个 Release 标签（如 `drawboardbookmark-latest`）覆盖附件，但**不推荐**：旧版本用户无法回退，且 sha256 会变化。建议每个版本一个标签。

---

## 6. 插件作者发布流程

### 6.1 插件工程要求

插件是独立 APK，必须满足以下条件（协议来源：`plugin-api` 模块）：

1. **独立 applicationId**，例如 `com.example.myplugin`，不能与宿主 `com.dsh.paperink` 相同；
2. 提供一个 **exported 的 Service**，响应宿主查询：

```xml
<service
    android:name=".MyPluginService"
    android:exported="true"
    android:process=":plugin">
    <intent-filter>
        <action android:name="com.dsh.paperink.plugin.BIND" />
    </intent-filter>
    <meta-data
        android:name="paperink.plugin.id"
        android:value="com.example.myplugin" />
</service>
```

3. Service 实现 `PluginEntry`（`manifest()` + `capabilities()`），并实现你要提供的能力接口（`DocumentFormat` / `ActionProvider` / `PanelProvider` / `BookmarkProvider`）；
4. 插件需要声明的权限尽量少；不要请求与功能无关的权限；
5. 插件运行在独立进程 `:plugin`，崩溃不会影响宿主，但仍要自己做好异常处理与超时。

### 6.2 签名与信任

宿主侧 `PluginSignaturePolicy` 的信任规则：

- 插件证书的 SHA-256 与**宿主证书**相同 → 直接信任；
- 或者在 `allowedDigests` 白名单里的证书指纹 → 信任；
- 否则拒绝加载，日志输出 `untrusted:<digest>`。

因此**第三方插件必须把证书指纹提交给宿主维护者加入白名单**（见 `app/src/main/java/com/dsh/paperink/host/PluginSignaturePolicy.kt` 的 `allowedDigests`）。

查看你插件 APK 的签名证书指纹：

```powershell
# 方式 1：apksigner（Android SDK build-tools 目录）
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs .\app-release.apk

# 方式 2：keytool（JDK 自带）
keytool -printcert -jarfile .\app-release.apk
```

把输出的 **SHA-256 指纹**（去掉冒号、转小写）随 PR 一起提交，例如：

```text
签名证书 SHA-256: 3f2a...c9d1
```

### 6.3 构建并发布插件 APK

```powershell
Set-Location D:\my-plugin

# 1) 构建 release APK
.\gradlew.bat assembleRelease

# 2) 计算 SHA-256（填到 index.json 的 sha256 字段）
$apk = Get-ChildItem .\app\build\outputs\apk\release\*.apk | Select-Object -First 1
Get-FileHash $apk.FullName -Algorithm SHA256 | Format-List
$apk | Select-Object Name, @{n='大小MB'; e={[math]::Round($_.Length/1MB,1)}}
```

然后：

1. 在 `paperink-plugins` 仓库的「Releases」→「Draft a new release」；
2. Tag 填 `myplugin-v1.0.0`，Title 填 `画板书签 v1.0.0`；
3. 上传插件 APK 作为附件，Publish；
4. 复制附件下载地址，作为 `index.json` 里的 `apkUrl`。

### 6.4 向市场提交索引（PR）

```powershell
# 1) Fork paperink-plugins 后克隆自己的 fork
git clone https://github.com/qmzzn114614/paperink-plugins.git
Set-Location .\paperink-plugins

# 2) 新建分支
git checkout -b plugin/myplugin-1.0.0

# 3) 创建插件说明目录与 README
New-Item -ItemType Directory -Force .\plugins\com.example.myplugin | Out-Null
notepad .\plugins\com.example.myplugin\README.md

# 4) 编辑 index.json，在 plugins 数组里追加你的条目（复制第 3 节示例改字段）

# 5) 校验后提交
Get-Content .\index.json -Raw | ConvertFrom-Json | Out-Null   # 无报错即合法
git add .
git commit -m "feat(market): add com.example.myplugin 1.0.0"
git push origin plugin/myplugin-1.0.0
```

然后在 GitHub 网页对你的 fork 点「Compare & pull request」，目标仓库选 `qmzzn114614/paperink-plugins` 的 `main` 分支，说明里写清：

- 插件名称、id、版本；
- 实现的能力；
- 签名证书 SHA-256；
- 插件 APK 的 Release 地址；
- 测试过的宿主版本与平板型号。

维护者合并后，GitHub Pages 会自动重新部署，App 刷新即可看到新插件。

### 6.5 更新插件版本

1. 构建新版本 APK，发新的 Release（如 `myplugin-v1.1.0`）；
2. 把 `index.json` 中该插件的 `version`、`apkUrl`、`sha256`、`sizeBytes` 更新为新版；
3. 更新 `plugins/<id>/README.md` 的版本历史；
4. 提 PR 合并。

> 插件 id 一旦发布**不要修改**，否则用户会看到一个「新插件」，旧插件配置与书签数据无法关联。

---

## 7. App 端说明（占位 URL 与当前状态）

### 7.1 当前实现状态

- 市场入口：**书架首页 →「插件」面板**；
- `v0.6.0` 的市场索引地址目前是一个**占位常量**，正式使用前需要替换成你自己的 Pages 地址；
- 占位示例（仅示意，真实常量名以仓库实现为准）：

```kotlin
// 示意代码，不在本文档修改范围内
object PluginMarketConfig {
    // TODO: 发布前替换为真实地址
    const val INDEX_URL = "https://qmzzn114614.github.io/paperink-plugins/index.json"
}
```

- 当前 `app/src/main/AndroidManifest.xml` **未声明 `INTERNET` 权限**，因此在接入「从线上拉取 index.json」之前，需要由开发者在清单里补上：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- 插件安装方式：从市场点条目下载 APK 后，通过系统安装器**手动安装**（Android 不允许 App 静默安装其他 APK），安装完成后回到 App 的「插件」面板点「刷新扫描」即可加载。

### 7.2 用户使用流程（写进插件 README 的部分）

1. 在平板浏览器或 App 内打开市场，下载插件 APK；
2. 允许「安装未知应用」，完成安装；
3. 打开纸墨 → 书架 →「插件」→「刷新扫描」；
4. 看到插件状态为「运行中」即加载成功；面板 / 动作 / 书签能力按插件说明使用。

### 7.3 是否需要私有部署

支持。`paperink-plugins` 可以部署在：

- 你自己的 GitHub 账号（Pages + Release）；
- 公司内网静态服务器（只要 `index.json` 与 APK 可被平板访问）；
- 任意支持 https 的静态托管（Cloudflare Pages、Vercel 等）。

唯一要求：`index.json` 与 APK **都能通过 https 直接访问**，App 只认 URL。

---

## 8. 安全与审核要求

第三方插件能读取当前文档内容、笔迹数据、书签数据，**安装前请自行评估风险**。维护者合并 PR 时至少检查：

- [ ] `id` 唯一，与 APK 内 `paperink.plugin.id` 一致；
- [ ] `apkUrl` 是 https 直链，文件可下载，`sha256` 与实际 APK 一致；
- [ ] `version` 与 APK 的 `versionName` 一致；
- [ ] `capabilities` 与插件实际实现一致，没有虚报；
- [ ] `minHostVersion` 合理，不会让老版本 App 安装后直接崩溃；
- [ ] 插件不请求与功能无关的权限（尤其不要滥用存储、网络、无障碍）；
- [ ] 插件不加载远程可执行代码（市场只接受原生 APK，不接受热更新脚本）；
- [ ] README 写清功能、权限、数据去向；
- [ ] 签名证书 SHA-256 已提供，并已由宿主维护者加入 `allowedDigests` 白名单。

> 宿主对插件的隔离是**进程级**的：插件跑在 `:plugin` 进程，崩溃不会拖垮宿主；但这不等于安全沙盒，插件仍可读取传给它的文档/笔迹数据。请只安装信任作者的插件。

---

## 9. 常见问题

| 问题 | 原因与解决 |
| --- | --- |
| 打开 Pages 地址 404 | 仓库不是 Public；Pages 源分支/目录填错；首次部署未完成（等 1–2 分钟）；`index.json` 不在仓库根目录 |
| `index.json` 能打开但 App 显示「暂无插件」 | `plugins` 为空数组；或 App 里的索引地址还是占位常量，没替换成你的 Pages 地址 |
| App 拉取失败 / 没有网络 | 宿主未声明 `INTERNET` 权限；或平板无法访问 github.io（可换 raw / jsDelivr 地址试） |
| raw 地址改了内容但 App 还是旧的 | raw.githubusercontent.com 有约 5 分钟 CDN 缓存；换 Pages 地址或加 `?t=<时间戳>` 参数 |
| 插件下载 404 | `apkUrl` 写错；Release 附件被删；标签名/文件名大小写不匹配 |
| App 里插件显示「不可用 / untrusted」 | 插件证书指纹不在白名单，或与宿主签名不同；把 SHA-256 提供给宿主维护者加入 `allowedDigests` |
| 插件装上了但刷新不到 | APK 没安装成功；`meta-data paperink.plugin.id` 缺失；Service 未 exported / 未声明 `com.dsh.paperink.plugin.BIND` action；宿主未获得 `QUERY_ALL_PACKAGES` 走 `<queries>` 声明（当前宿主已声明 BIND 查询） |
| 市场列表里出现重复插件 | `index.json` 中 `id` 重复；或旧的 raw 缓存；用第 4.5 节命令检查并去重 |
| 想让市场显示图标但图标裂了 | `iconUrl` 必须是 https 直链且图片可访问；建议 PNG、128×128、小于 200 KB |
| 插件 APK 安装失败 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 平板上已装同 applicationId 不同签名的版本，先卸载旧版再装 |

---

## 10. 维护者发布检查清单

- [ ] `index.json` 通过 JSON 校验，`plugins` 数组无重复 `id`
- [ ] 每个条目的 `apkUrl` / `iconUrl` 都能在无登录浏览器中直接下载/打开
- [ ] 每个 `sha256` 与对应 APK 的 `Get-FileHash` 结果一致
- [ ] 每个 `version` 与 APK 的 `versionName` 一致
- [ ] 每个 `minHostVersion` 不高于当前发布版本
- [ ] 新插件签名指纹已加入宿主 `PluginSignaturePolicy.allowedDigests`
- [ ] `.nojekyll` 存在，Pages 部署成功
- [ ] `README.md` 中的插件列表表格已同步更新
- [ ] 市场地址已填入 App 配置（替换占位常量），并在真机验证能拉到列表

---

## 附录 A：相关文档与源码

| 内容 | 位置 |
| --- | --- |
| 项目说明、构建步骤 | [`../README.md`](../README.md) |
| 把 App 发布到 GitHub 的完整教程 | [`GITHUB_RELEASE_GUIDE.md`](GITHUB_RELEASE_GUIDE.md) |
| 插件协议与能力接口 | `plugin-api/src/main/java/com/dsh/paperink/plugin/PluginApi.kt` |
| 跨进程协议常量 | `plugin-api/src/main/java/com/dsh/paperink/plugin/PluginProtocol.kt` |
| 签名白名单 | `app/src/main/java/com/dsh/paperink/host/PluginSignaturePolicy.kt` |
| 测试插件（参考实现） | `plugin-test/` |

## 附录 B：仓库命名与地址速查

```text
插件市场仓库：  https://github.com/qmzzn114614/paperink-plugins
索引（Pages）： https://qmzzn114614.github.io/paperink-plugins/index.json
索引（raw）：   https://raw.githubusercontent.com/qmzzn114614/paperink-plugins/main/index.json
插件 APK：      https://github.com/qmzzn114614/paperink-plugins/releases/download/<标签>/<文件名>.apk
```

> 本文档中的 GitHub 地址全部是占位示例，请发布前统一替换为你自己的真实地址；不要直接把 `<...>` 占位符留在正式文档或 App 配置里。
