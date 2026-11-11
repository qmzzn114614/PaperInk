# 把纸墨 PaperInk 发布到 GitHub 完全教程

> 面向**没接触过 Git / GitHub**的读者。跟着本文从零开始，约 30–60 分钟可以完成：
> 安装 Git → 首次提交 → 创建 GitHub 仓库 → 推送源码 → 打 `v0.6.0` 标签 → 发布 Release（附件 APK）。
>
> 本文所有命令都针对 **Windows + PowerShell**，可直接复制。其他系统请把路径与 `gradlew.bat` 换成对应写法。

---

## 0. 开始之前

### 0.1 本文约定

| 约定 | 说明 |
| --- | --- |
| 工程目录 | `D:\PaperInk`（如果你放在别处，替换所有命令里的路径） |
| GitHub 用户名 | 文中写作 `qmzzn114614`，**复制命令后要整体替换成你的用户名**（连同尖括号一起替换） |
| 仓库名 | `PaperInk`（想用别的名字时，替换所有 URL 中的仓库名） |
| 版本 | 当前 `v0.6.0`（versionCode 27） |
| 安装包 | `D:\PaperInk\dist\paperink-0.6.0-debug.apk`（若 dist 里文件名不同，以实际文件名为准） |
| 命令环境 | Windows PowerShell 7（系统自带的 Windows PowerShell 5.1 也可以） |

### 0.2 需要准备的东西

- 一个 GitHub 账号（免费注册：<https://github.com/signup>）
- 电脑上已装好 JDK 21 和 Android SDK（构建 APK 需要；如果你只发布别人构建好的 APK，可跳过构建）
- 已构建好的 `dist\paperink-0.6.0-debug.apk`
- 稳定的网络（首次要上传约 50 MB 的 APK）

### 0.3 流程总览

```text
安装 Git
  → 检查工程 / 清理仓库体积
  → git init 首次提交
  → GitHub 网页新建空仓库
  → 配置认证（HTTPS + PAT 或 SSH）
  → git remote add origin + git push -u origin main
  → git tag v0.6.0 + git push origin v0.6.0
  → GitHub Releases 上传 APK
  → 以后日常：改文件 → git add → git commit → git push
```

---

## 1. 安装 Git

### 1.1 安装

**方式 A：使用 winget（Windows 10/11 自带，推荐）**

```powershell
winget install --id Git.Git -e --source winget
```

**方式 B：手动下载安装包**

1. 打开 <https://git-scm.com/download/win>，下载 64-bit Git for Windows Setup；
2. 一路「下一步」安装即可，遇到选项保持默认（默认已包含「Git from the command line and also from 3rd-party software」）。

### 1.2 验证安装

**关掉并重新打开 PowerShell**（让 PATH 生效），然后：

```powershell
git --version
```

能看到类似 `git version 2.4x.x.windows.1` 就说明安装成功。

### 1.3 配置身份（每台电脑只需一次）

Git 每次提交都会记录作者信息，先配置好：

```powershell
git config --global user.name "你的名字或昵称"
git config --global user.email "<你的邮箱>"
git config --global init.defaultBranch main
git config --global core.quotepath false
```

- `user.name` / `user.email`：会公开出现在提交记录里，填你愿意公开的信息；
- `core.quotepath false`：让中文文件名正常显示，不出现 `\344\270\255` 之类的转义；
- 查看当前配置：`git config --global --list`。

> 建议同时确认凭据管理器已启用（Git for Windows 默认开启）：
>
> ```powershell
> git config --global credential.helper manager
> ```

---

## 2. 发布前检查工程

```powershell
Set-Location D:\PaperInk

# 1) 确认这些文件/目录都在
Get-ChildItem README.md, LICENSE, .gitignore, dist, docs -ErrorAction Stop |
    Select-Object Name, Length

# 2) 确认还没有初始化过 Git（输出 False 是正常的）
Test-Path .git

# 3) 确认 APK 存在（以 dist 实际文件名为准）
Get-ChildItem .\dist\*.apk | Select-Object Name, @{n='大小MB'; e={[math]::Round($_.Length/1MB,1)}}
```

- 如果 `Test-Path .git` 输出 `True`，说明这个目录已经是 Git 仓库（可能之前初始化过）。本文的 `git init` 命令是幂等的，可以继续；但首次推送前请用 `git status` 看看有没有不该提交的内容。
- 如果 `dist\paperink-0.6.0-debug.apk` 不存在，先按项目 README 的「构建」章节构建，并把 `app\build\outputs\apk\debug\app-debug.apk` 复制到 `dist\paperink-0.6.0-debug.apk`。

---

## 3. 清理仓库体积（强烈建议）

`dist\` 里如果保留了历史上每个版本的 APK，首次推送会非常慢，甚至触发 GitHub 的大小限制。

**GitHub 的限制（重要）**：

| 限制 | 数值 |
| --- | --- |
| 单个文件上限 | **100 MB** |
| 仓库建议大小 | 尽量 **< 1 GB** |
| Release 附件单文件上限 | 2 GB |

当前工程的历史 APK 加起来约 900 MB。建议 **只保留当前版本的 APK**，把旧版本移到不参与提交的 `.work\dist-history\`（`.work/` 已在 `.gitignore` 中）。

```powershell
Set-Location D:\PaperInk
New-Item -ItemType Directory -Force .\.work\dist-history | Out-Null

# 只保留 v0.6.0 的 APK，其余历史 APK 移到 .work\dist-history（不是删除）
$keep = 'paperink-0.6.0-debug.apk'
Get-ChildItem .\dist\*.apk |
    Where-Object { $_.Name -ne $keep } |
    Move-Item -Destination .\.work\dist-history

# 重新生成 SHA256SUMS.txt（只对 dist 里现有的 APK）
$files = Get-ChildItem .\dist\*.apk | Sort-Object Name
$lines = foreach ($f in $files) {
    $hash = (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower()
    "$hash  $($f.Name)"
}
Set-Content -Path .\dist\SHA256SUMS.txt -Value $lines -Encoding ascii

Get-ChildItem .\dist | Select-Object Name, @{n='大小MB'; e={[math]::Round($_.Length/1MB,1)}}
```

> 说明：
> - 移动而不是删除，旧版本仍然留在本机 `.work\dist-history\` 里，需要时可找回；
> - `.work/` 已被 `.gitignore` 忽略，不会进入仓库；
> - 以后每个版本建议**只在 GitHub Release 上传 APK**，仓库里的 `dist/` 只保留当前版本，避免仓库越滚越大。若你确定要保留多个历史 APK，请确认总体积不超过 1 GB。

---

## 4. 初始化本地仓库并首次提交

```powershell
Set-Location D:\PaperInk

# 1) 初始化 Git 仓库（已初始化过也能安全执行）
git init

# 2) 确认当前分支名是 main（Git 2.28+ 用 -M 重命名）
git branch -M main

# 3) 看一眼状态：应只看到未跟踪的源码与文档
git status --short

# 4) 抽查 .gitignore 是否生效（有输出 = 已被忽略，正确）
git check-ignore -v .\.work\shots\h5_hl10_clean.png
git check-ignore -v .\app\build\outputs\apk\debug\app-debug.apk
git check-ignore -v .\local.properties
```

第 4 步三条命令都应该打印出匹配的 `.gitignore` 规则；**如果没有任何输出，说明该路径没有被忽略**，先别急着 `git add`，检查 `.gitignore` 内容。

确认无误后，把所有文件加入暂存区并提交：

```powershell
git add --all

# 提交前再核对一遍：不应出现 .work/、app/build/、.gradle/、local.properties
git status --short

# 首次提交
git commit -m "chore: 初始化纸墨 PaperInk v0.6.0 源码与文档"

# 查看提交结果
git log --oneline -1
```

> 如果 `git status --short` 里出现了 `.work/`、`build/`、`.gradle/`、`local.properties`，说明 `.gitignore` 没有生效或文件已被跟踪，请先执行 `git rm -r --cached <目录>` 再提交（详见第 12 节）。

---

## 5. 在 GitHub 上创建空仓库

1. 登录 <https://github.com>；
2. 点右上角「**+**」→「**New repository**」；
3. 填写：
   - **Repository name**：`PaperInk`
   - **Description**（可选）：`Android 平板 PDF 阅读与手写批注`
   - **Public / Private**：想让别人直接下载 Release 就选 **Public**；Private 也可以，但只有被邀请的人能看；
   - **不要勾选**「Add a README file」「Add .gitignore」「Choose a license」——仓库必须保持**完全空白**，否则首次推送会冲突；
4. 点「**Create repository**」。

创建完成后，记下网页给出的仓库地址（两种任选其一）：

- HTTPS：`https://github.com/qmzzn114614/PaperInk.git`
- SSH：`git@github.com:qmzzn114614/PaperInk.git`

> 提示：本文所有 URL 里的 `qmzzn114614` 都要整体替换，不要留下 `<`、`>` 字符。

---

## 6. 配置认证（二选一）

GitHub 从 2021 年起**不再支持用账号密码推送**。有两种方式：

- **方式 A：HTTPS + PAT（个人访问令牌）**——新手推荐，配置简单；
- **方式 B：SSH 密钥**——一次配置长期免 token，适合长期开发。

选一种即可。

### 6.A HTTPS + PAT（个人访问令牌）

1. GitHub 右上角头像 →「**Settings**」→ 左侧最下方「**Developer settings**」；
2. 选择「**Personal access tokens**」：
   - 推荐「**Fine-grained tokens**」→「**Generate new token**」：
     - Token name：`PaperInk push`
     - Expiration：90 days（按需）
     - Repository access：`Only select repositories` → 选中 `PaperInk`
     - Repository permissions →「**Contents**」设为 **Read and write**（Metadata 会自动只读）
     - 点「Generate token」，**立刻复制**（页面刷新后不再显示）；
   - 或者用「**Tokens (classic)**」→「Generate new token (classic)」→ 勾选 **`repo`** 范围 → 生成并复制。
3. 回到 PowerShell 首次 `git push` 时，会弹出登录窗口：
   - **Username**：你的 GitHub 用户名；
   - **Password**：粘贴刚才的 **token**（不是账号登录密码！）。

验证凭据是否已保存：

```powershell
git config --global credential.helper
```

如果输错了凭据、之后一直认证失败，可以清除 Windows 里保存的旧凭据：

1. 打开「控制面板」→「用户账户」→「凭据管理器」→「Windows 凭据」；
2. 找到 `git:https://github.com`，点「删除」；
3. 重新 `git push`，重新输入用户名 + token。

### 6.B SSH 密钥（可选）

```powershell
# 1) 生成密钥（一路回车即可；想加口令也可以）
ssh-keygen -t ed25519 -C "<你的邮箱>"

# 2) 复制公钥内容
Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub" | Set-Clipboard
```

然后在 GitHub 网页：

1. 右上角头像 →「**Settings**」→「**SSH and GPG keys**」→「**New SSH key**」；
2. Title 随便填（如 `Windows 台式机`），Key 粘贴刚才复制的内容 →「**Add SSH key**」。

测试连接：

```powershell
ssh -T git@github.com
```

第一次会问 `Are you sure you want to continue connecting (yes/no)?`，输入 `yes` 回车。看到下面这句就成功了（GitHub 不提供 shell，这是正常提示）：

```text
Hi qmzzn114614! You've successfully authenticated, but GitHub does not provide shell access.
```

---

## 7. 关联远程仓库并推送

```powershell
Set-Location D:\PaperInk

# 二选一：
# HTTPS（配合第 6.A 节的 PAT）
git remote add origin https://github.com/qmzzn114614/PaperInk.git

# SSH（配合第 6.B 节）
# git remote add origin git@github.com:qmzzn114614/PaperInk.git

git remote -v
git push -u origin main
```

首次推送会把源码与 APK 上传，约 50 MB，视网络可能需要几分钟，**不要中途关窗口**。

成功标志：

```powershell
git status -sb
# 输出应为：## main...origin/main
```

同时刷新 GitHub 网页，应能看到 `README.md`、`docs/`、`dist/` 等文件。

> 如果报 `fatal: remote origin already exists`，说明已添加过远程地址，改用：
> `git remote set-url origin <正确的仓库地址>`
>
> 如果报 `Updates were rejected because the remote contains work...`，说明建仓库时勾选了 README/LICENSE，见第 12.6 节。

---

## 8. 给 v0.6.0 打标签

标签（tag）是 Release 的锚点，先打 tag 再发布 Release。

```powershell
Set-Location D:\PaperInk

# 确认当前提交是你要发布的版本
git log --oneline -1

# 创建附注标签（名称必须是 v0.6.0）
git tag -a v0.6.0 -m "纸墨 PaperInk v0.6.0 (versionCode 27)"

# 单独推送这个标签到 GitHub（普通 git push 不会自动推标签）
git push origin v0.6.0

# 查看本地标签
git tag -l
```

> **重要**：标签指向提交。请先完成所有代码/文档改动并提交，再打 tag。发布后不要再移动或删除 `v0.6.0` 标签（已发布的 Release 会依赖它）。
>
> 打错了怎么办：
>
> ```powershell
> git tag -d v0.6.0                      # 删本地
> git push origin :refs/tags/v0.6.0      # 删远程
> # 然后重新 git tag -a v0.6.0 -m "..." 并推送
> ```
>
> 若一次要推送所有标签：`git push --tags`（多版本时慎用）。

---

## 9. 发布 GitHub Release 并上传 APK

1. 打开仓库网页，点右侧「**Releases**」→「**Draft a new release**」（也可直接访问 `https://github.com/qmzzn114614/PaperInk/releases/new`）；
2. 填写：
   - **Choose a tag**：选择已推送的 `v0.6.0`；
   - **Target**：`main`；
   - **Release title**：`纸墨 PaperInk v0.6.0`；
   - **Describe this release**：粘贴第 10 节的模板（按实际情况修改）；
3. 上传附件：把 `D:\PaperInk\dist\paperink-0.6.0-debug.apk` 直接**拖进**「Attach binaries by dropping them here or selecting them」区域，等待上传完成；
4. 勾选「**Set as the latest release**」，点「**Publish release**」。

发布后，APK 的稳定下载地址为：

```text
https://github.com/qmzzn114614/PaperInk/releases/download/v0.6.0/paperink-0.6.0-debug.apk
```

顺手生成 SHA-256 校验值，贴到 Release 说明里：

```powershell
Get-FileHash .\dist\paperink-0.6.0-debug.apk -Algorithm SHA256 | Format-List
```

> 注意：Release 附件与仓库里的 `dist/` 是两份独立的文件。推荐**每个版本只把 APK 放 Release 附件**，仓库里的 `dist/` 只保留当前版本，避免仓库体积持续膨胀。

---

## 10. Release Notes 模板（可直接复制修改）

```markdown
## 纸墨 PaperInk v0.6.0（versionCode 27）

Android 平板 PDF 阅读与手写批注应用。本版为 **debug 签名**安装包，仅适合个人侧载。

### 新功能

- 右侧悬浮栏新增独立「橡皮」按钮：一键切换橡皮，再点一下切回上一支笔
- 底栏新增「笔迹管理」弹窗：清空本页 / 清空全书 / 勾选页码批量删除（不可撤销）
- 书签面板支持多选删除与「清空本书全部书签」（二次确认）
- 深色空白区也能批注：纸面外笔迹保留显示，色板新增「纸白」#F5F0E1
- 插件市场：书架「插件」面板可从 GitHub `index.json` 拉取插件列表

### 改进

- 荧光笔改为普通半透明叠色（SRC_OVER），预览与成品一致，低透明度不再发黑
- 点工具面板外的空白处会收起面板，不再误触翻页

### 修复

- 按实际情况填写：例如笔迹显示、翻页、插件热插拔等相关修复

### 安装

1. 下载 `paperink-0.6.0-debug.apk`；
2. 传到平板，点击安装，允许「未知来源」；
3. 要求 Android 8.0+（minSdk 28），推荐 arm64 平板 + 手写笔。

### 已知限制

- 手写笔记只存本机，卸载 App 会一起删除，重要笔记请先「导出 PDF」
- debug 包体积约 46–48 MB，不能上架应用商店
- 笔身振动依赖厂商系统白名单，第三方 App 无法逐笔控制笔马达

### 校验

- SHA-256：`<粘贴 Get-FileHash 输出的 Hash>`
```

> 模板中「修复」一节的示例内容请按实际改动填写；所有 `<...>` 占位都要替换掉。

---

## 11. 日常提交流程（以后改文件时用）

```powershell
Set-Location D:\PaperInk

# 1) 看当前状态与改动
git status
git diff

# 2) 只添加你真的要提交的文件（示例）
git add README.md
git add docs/GITHUB_RELEASE_GUIDE.md docs/images/

# 或者添加所有改动（确认没有大文件/临时文件后）
# git add --all

# 3) 提交并写清楚改了什么
git commit -m "docs: 补充构建说明与截图"

# 4) 推送到 GitHub
git push
```

提交信息建议用前缀区分类型，便于以后查历史：

| 前缀 | 用途 |
| --- | --- |
| `feat:` | 新功能 |
| `fix:` | 修复 bug |
| `docs:` | 文档 / 截图 |
| `refactor:` | 重构（不改行为） |
| `build:` | 构建、依赖、版本号 |
| `chore:` | 杂项（初始化仓库、清理文件） |

常用检查命令：

```powershell
git log --oneline --graph --decorate -10   # 最近 10 条历史
git diff --stat                            # 改动统计
git status -sb                             # 分支与远程同步状态
```

### 发布下一个版本的简化流程

1. 修改代码与版本号（`app/build.gradle.kts` 里的 `versionCode` / `versionName`）；
2. 构建并复制 APK 到 `dist/`，重新生成 `dist/SHA256SUMS.txt`；
3. 提交改动：`git add --all && git commit -m "build: 发布 v0.7.0"`；
4. 推送：`git push`；
5. 打标签并推送：`git tag -a v0.7.0 -m "纸墨 PaperInk v0.7.0"` → `git push origin v0.7.0`；
6. 去 GitHub 新建 Release，上传新 APK。

---

## 12. 常见错误排查

### 12.1 `fatal: not a git repository (or any of the parent directories): .git`

- 原因：当前目录不是 Git 仓库，或没在工程目录里。
- 解决：`Set-Location D:\PaperInk` 后重试；若确实没初始化过，执行 `git init`。

### 12.2 认证失败：`Support for password authentication was removed`

- 原因：GitHub 早就不允许用账号密码推送，必须用 **PAT**（第 6.A 节）或 **SSH**（第 6.B 节）。
- 解决：生成 token（勾选 `Contents: Read and write` 或 classic 勾 `repo`），推送时用户名填 GitHub 用户名、密码填 token；清掉 Windows 凭据管理器里旧的 `git:https://github.com` 再重试。

### 12.3 `remote: Permission to qmzzn114614/PaperInk.git denied` / `403`

- 原因：仓库地址里的用户名/仓库名写错；token 没有该仓库权限；token 过期。
- 解决：`git remote -v` 检查地址；重新生成有权限的 token；确认是对**你自己的仓库**推送（别人的仓库需要先 Fork 再提 PR）。

### 12.4 `fatal: remote origin already exists.`

- 原因：已经添加过名为 `origin` 的远程地址。
- 解决：

```powershell
git remote -v
git remote set-url origin https://github.com/qmzzn114614/PaperInk.git
```

### 12.5 `error: src refspec main does not match any`

- 原因：还没有任何提交，或本地分支名是 `master` 而不是 `main`。
- 解决：

```powershell
git branch -M main
git commit -m "chore: 首次提交"   # 若还没有提交
git push -u origin main
```

### 12.6 推送被拒：`Updates were rejected because the remote contains work...`

- 原因：创建 GitHub 仓库时勾选了 README / .gitignore / license，远程已有提交，与本地历史无关。
- 解决（二选一）：
  - 合并远程内容后再推：
    ```powershell
    git pull --rebase origin main
    git push -u origin main
    ```
  - 或者：删掉 GitHub 上的仓库，重新建一个**完全空白**的仓库，再推送。

### 12.7 换行符警告：`warning: LF will be replaced by CRLF`

- 原因：Windows 上 Git 默认会把仓库里的 LF 转成 CRLF，这本身不是错误，只是提示。
- 建议配置（只影响本机，安全）：

```powershell
git config --global core.autocrlf true
```

- 如果已经提交并出现大量「整文件改动」的 diff，可在工程根目录添加 `.gitattributes`（可选，示例如下），然后执行 `git add --renormalize .`：

```gitattributes
* text=auto
*.bat text eol=crlf
*.sh  text eol=lf
gradlew text eol=lf

# 二进制文件不要做换行转换
*.png binary
*.apk binary
*.jar binary
```

> 本仓库当前**没有** `.gitattributes`。如果只是个人使用，维持默认配置即可，不必修改已有历史。

### 12.8 大文件 / push 失败：`GH001: Large files detected`、`RPC failed; HTTP 413`

- 原因：单个文件超过 **100 MB**，或一次上传了过多历史 APK；网络中断也会报 `RPC failed`。
- 解决：
  1. 确认 `dist/` 只保留当前版本 APK（第 3 节），历史 APK 移到 `.work/`；
  2. 检查有没有误提交的大文件：
     ```powershell
     Get-ChildItem . -Recurse -File -Force |
         Where-Object { $_.Length -gt 95MB -and $_.FullName -notmatch '\\\.work\\|\\build\\|\\\.gradle\\' } |
         Select-Object FullName, Length
     ```
  3. **如果大文件还没提交**：从暂存区移除，重新提交：
     ```powershell
     git rm --cached "大文件路径"
     git commit --amend --no-edit
     ```
  4. **如果已经推送到了 GitHub**：历史里已经包含大对象，普通删除无法缩小仓库，需要用 `git filter-repo` 或 BFG 重写历史（属高风险操作，个人仓库可在搜索教程后操作，重写后需要 `git push --force`）。最简单可靠的办法：把大文件移走，**删掉远程仓库重新建一个空仓库，再首次推送**。
  5. 网络问题导致的 `RPC failed`：直接重试 `git push`；仍失败可尝试 `git config --global http.postBuffer 524288000` 或更换网络/VPN。
- 以后发版请把 APK 放 **Release 附件**，不要每个版本都提交进 Git 历史。

### 12.9 误提交 `build/`、`.work/`、`.gradle/`

- 原因：`.gitignore` 写错/未生效，或文件在加 `.gitignore` 之前已被跟踪。
- 解决：

```powershell
# 从版本库中移除（保留本地文件）
git rm -r --cached .work
git rm -r --cached app/build
git rm -r --cached .gradle

git commit -m "chore: 移除误提交的构建产物"
git status --short
```

- 如果这些内容**还没推送**：可以用 `git commit --amend --no-edit` 合并进上一次提交；
- 如果**已经推送**：先按上面提交修正；若还想彻底从历史抹掉，使用 `git filter-repo`/BFG（同 12.8 第 4 条）。
- 检查某路径是否被忽略：`git check-ignore -v <路径>`。

### 12.10 `fatal: unable to access ... SSL certificate problem` / 连接超时

- 原因：公司网络、代理、VPN、系统时间错误等。
- 解决：

```powershell
# 先确认能否访问 GitHub
Invoke-WebRequest https://github.com -UseBasicParsing | Select-Object StatusCode

# 如果公司要求代理（把地址换成实际代理）
git config --global http.proxy http://127.0.0.1:7890

# 取消代理
git config --global --unset http.proxy
```

- 也可尝试关闭/开启 VPN、切换手机热点；系统时间不正确时校时后再试。

### 12.11 中文文件名显示为 `\344\270\255`

```powershell
git config --global core.quotepath false
```

### 12.12 `Filename too long` / `error: invalid path`

```powershell
git config --global core.longpaths true
```

### 12.13 README 里的图片在 GitHub 上不显示

- 原因：截图没有提交/推送，或文件名大小写与引用不一致。
- 检查：

```powershell
git ls-files docs/images
Get-ChildItem docs\images
```

- 解决：`git add docs/images/` → `git commit -m "docs: 补截图"` → `git push`。README 中引用的是相对路径 `docs/images/xxx.png`，不要用本机绝对路径。

### 12.14 标签已存在 / 打错标签

```powershell
git tag -d v0.6.0                    # 删本地标签
git push origin :refs/tags/v0.6.0    # 删远程标签
git tag -a v0.6.0 -m "纸墨 PaperInk v0.6.0 (versionCode 27)"
git push origin v0.6.0
```

### 12.15 平板安装 APK 失败

| 报错 | 原因与解决 |
| --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `签名不一致` | 平板上已装同包名但不同签名的版本。先卸载旧版再安装（会丢本机笔记，请先导出 PDF） |
| `应用未安装` / 解析包错误 | APK 下载不完整（对比 Release 里的 SHA-256），或系统版本低于 Android 8.0 |
| `未知来源` 被拦截 | 在系统设置里允许文件管理器/浏览器「安装未知应用」 |

---

## 13. 发布完成检查清单

- [ ] GitHub 仓库网页能正常打开，README 里的截图全部显示
- [ ] 仓库里**没有** `.work/`、`build/`、`.gradle/`、`local.properties`
- [ ] 仓库里能看到 `README.md`、`LICENSE`、`.gitignore`、`docs/`、`dist/paperink-0.6.0-debug.apk`
- [ ] Tags 页面有 `v0.6.0`
- [ ] Releases 页面有 `纸墨 PaperInk v0.6.0`，且附件 `paperink-0.6.0-debug.apk` 可下载
- [ ] 下载后的 APK 能安装到 Android 8.0+ 平板并正常打开 PDF
- [ ] Release 说明里的 SHA-256 与本地 `dist\paperink-0.6.0-debug.apk` 一致
- [ ] 从 Release 下载链接在无登录浏览器中可访问（Public 仓库）

---

## 附录 A：Git 四个区域（一句话理解）

| 区域 | 含义 | 常用命令 |
| --- | --- | --- |
| 工作区 | 你正在编辑的 `D:\PaperInk` 文件 | `git status` / `git diff` |
| 暂存区 | 准备提交的文件快照 | `git add <文件>` |
| 本地仓库 | 本机的提交历史 | `git commit` / `git log` |
| 远程仓库 | GitHub 上的仓库 | `git push` / `git pull` |

流程就是：**改文件 → `git add`（选中）→ `git commit`（拍快照）→ `git push`（上传）**。

## 附录 B：常用命令速查

```powershell
git status                     # 当前状态
git diff                       # 未暂存的改动
git add --all                  # 全部加入暂存区
git commit -m "说明"           # 提交
git push                       # 推送当前分支
git pull --rebase              # 拉取并变基（多人协作时）
git log --oneline --graph -10  # 查看历史
git remote -v                  # 查看远程地址
git branch -vv                 # 本地分支与跟踪关系
git tag -l                     # 列出标签
git show v0.6.0 --stat         # 查看标签对应的提交
git check-ignore -v <路径>     # 检查是否被忽略
git ls-files                   # 列出仓库跟踪的文件
```

## 附录 C：相关文档

- 项目说明与构建方式：[`../README.md`](../README.md)
- 插件市场托管方案：[`PLUGIN_MARKET.md`](PLUGIN_MARKET.md)
- 开发过程与真机验证记录：[`../PROJECT_STATE.md`](../PROJECT_STATE.md)

---

> 本文以 Git for Windows + PowerShell 7 为准；GitHub 网页界面可能会更新，按钮名称以实际页面为准。命令中的占位符 `<...>` 执行前必须替换成真实值。
