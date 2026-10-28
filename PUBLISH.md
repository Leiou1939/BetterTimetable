# 发布到 GitHub，并让 App 自己检查更新

App 已经把「检查更新」做进去了：它读你仓库的 **Releases**（`/releases/latest`），
有新版本就下载 APK 并调起系统安装器。所以只需要把代码放上 GitHub，**让 Actions 自动发 Release**，
之后每次改代码 push 一次，手机上就能收到更新。

```
你 push 代码
   └─ GitHub Actions 自动构建（版本号自增）
        └─ 自动创建 Release（附 课程表.apk）
             └─ 手机上 设置 → 检查更新 → 下载并安装
```

---

## 一、把代码放上 GitHub（二选一）

### 方式 A：全程网页，不装任何工具（推荐）

1. 打开 <https://github.com/new>
   - **Repository name**：`timetable`
   - 选 **Public**（私有仓库的 Release 也能下载，但 Actions 额度有限，公开更省心）
   - 不要勾 "Add a README file"（否则会和上传的文件冲突）
   - 点 **Create repository**
2. 把 `timetable-app.zip` 解压到本地任意目录。
3. 在新仓库页面点 **uploading an existing file**（或直接访问
   `https://github.com/<你的用户名>/timetable/upload/main`）。
4. 把解压出来的**所有内容**（含隐藏目录 `.github` 和 `keystore`）拖进上传框 → **Commit changes**。

   > ⚠️ 注意两点：
   > - `.github/workflows/release.yml` 必须上传成功，否则不会自动发 Release。
   > - `keystore/debug.keystore` 必须上传成功。它决定了 APK 的签名，
   >   签名不一致的 APK 无法覆盖安装，App 内更新会报"应用未安装"。
   > - Windows 资源管理器默认隐藏以 `.` 开头的文件，如果拖拽后 `.github` 没上去，
   >   请在地址栏手动输入路径确认，或用方式 B。

5. 仓库 → **Settings → Actions → General**：
   - *Actions permissions* 选 **Allow all actions and reusable workflows**
   - *Workflow permissions* 选 **Read and write permissions**（否则发 Release 会 403）
6. 仓库 → **Actions** → 选 `Build & Release` → **Run workflow**（手动跑一次）。
   等 2~4 分钟，**Releases** 里会出现 `v1.0.10xx`，附件是 `课程表-1.xx.apk`。

### 方式 B：命令行（已装 git）

```bash
cd timetable-app
git init -b main
git add -A
git commit -m "课程表 App：课表显示 + 日历联动 + 应用内更新"
git remote add origin https://github.com/<你的用户名>/timetable.git
git push -u origin main
```

（第一次 push 会要求登录，用浏览器弹出的授权即可，或使用 Personal Access Token。）

---

## 二、在手机上让它开始检查更新

1. 安装这次给你的 `课程表.apk`（**必须**和 GitHub 上构建的是同一把签名钥匙，
   即仓库里的 `keystore/debug.keystore`）。
2. 打开 App → 右上角齿轮 **设置** → 滑到「关于与更新」→ **检查更新**。
3. 在「GitHub 仓库（用户名/仓库名）」里填：`你的用户名/timetable` → 点 **检查更新**。
4. 显示新版本后点 **下载并安装**：
   - 第一次会要求「允许从此来源安装应用」，点允许；
   - 下载完成后调起系统安装器，覆盖安装即可（课表数据、设置都保留）。

填过一次仓库地址会被记住；以后 App 每次启动都会静默检查一次，
发现新版本会在底部弹「发现新版本 v1.0.10xx　[更新]」，点一下就进下载。

> 想关掉启动时的自动检查：设置 → 检查更新 → 把仓库地址清空即可（或告诉我，我加个开关）。

---

## 三、以后怎么发新版

改完代码，`git push` 到 `main` 就行 —— Actions 会自动构建并发布下一个 Release。
也可以手动：Actions → Build & Release → Run workflow。

版本号规则（`release.yml` 里）不要改乱，App 靠它判断新旧：

| 项 | 值 | 说明 |
| --- | --- | --- |
| `BUILD_NO` | Actions 运行序号 | 自动递增 |
| `versionCode` | `1000 + BUILD_NO` | 写进 APK，App 拿它比较 |
| `versionName` | `1.BUILD_NO` | 界面上显示 |
| Release tag | `v1.0.(1000+BUILD_NO)` | App 取最后一段数字，正好等于 versionCode |

本地自己构建时（Android Studio / `gradlew assembleRelease`）默认 `versionCode = 4`，
所以你手机上装的本地包永远不会比 GitHub 上的新版本"更新"，会正常收到更新提示。

---

## 四、常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 检查更新提示 **404** | 仓库地址写错，或该仓库还没发过 Release（先跑一次 Actions） |
| 提示 **API rate limit** | 未登录的 GitHub API 每小时 60 次，等一小时即可；App 只在启动时查一次，正常不会触发 |
| 下载完安装时提示 **「应用未安装」/ 签名冲突** | 手机上装的是别的签名版本（例如旧的 debug 包）。先卸载旧版再装这次这个包，之后就能覆盖升级 |
| 安装按钮点了没反应 | 系统设置里给「课程表」开启"安装未知应用"权限：设置 → 应用 → 课程表 → 安装未知应用 → 允许 |
| Actions 跑失败 | 看日志：多数是 *Workflow permissions* 没给 Read and write，或 `keystore/debug.keystore` 没上传 |
| App 内一直提示"已是最新" | 确认 tag 是 `v1.0.10xx` 这种格式（最后一段是 versionCode），且 APK 附件名以 `.apk` 结尾 |

---

## 五、如果你想让 AI 直接帮你推

把代码推上去需要 GitHub 凭据，而这台机器上既没有 `git` 也没有登录信息，所以我没法代推。
你可以在 GitHub 上建一个 **Fine-grained token**（仅 `timetable` 仓库、`Contents: Read and write` 权限），
把它交给我，我就能直接建仓库、推送并发 Release —— 但**令牌会出现在这段对话里**，
用完请立刻在 GitHub 上撤销。更稳妥的是按方式 A 自己上传，五分钟左右。
