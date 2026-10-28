# 已经发布好了 —— 仓库、发版、更新

**仓库**：<https://github.com/Leiou1939/BetterTimetable>
**最新版本**：<https://github.com/Leiou1939/BetterTimetable/releases/latest>

```
你 push 到 main
   └─ GitHub Actions 自动构建（版本号自增）
        └─ 自动创建 Release（附 timetable-x.y.apk）
             └─ 手机上 设置 → 检查更新 → 下载并安装
```

---

## 一、装哪个包

装根目录的 **`课程表.apk`**（`versionCode 1003 / versionName 1.3`），
它和 GitHub 上 `v1.0.1003` 是同一份代码、同一把签名钥匙（`keystore/debug.keystore`）。

所以：

- 装了这个包之后，GitHub 再发新版本，**可以直接覆盖安装**，不会报"应用未安装"；
- 如果你手机上装过更早的、用别的钥匙签的包（比如 9 月那版自己 build 的），
  **先卸载旧的**再装这个，否则签名冲突。

更新地址已经**预先写进 APK**（`Leiou1939/BetterTimetable`），装完不用再填。

---

## 二、在手机上检查更新

1. 打开 App → 右上角齿轮 **设置** → 滑到「关于与更新」；
2. 点 **检查更新**（仓库地址已自动填好，不用改）；
3. 有新版本就点 **下载并安装**：
   - 第一次会提示「允许从此来源安装应用」，允许即可；
   - 下载完成调起系统安装器，覆盖安装，课表与设置都保留。

App 每次启动也会静默查一次，有新版本底部会弹「发现新版本 v1.0.10xx　[更新]」。

> **国内网络注意**：`github.com` 网页在国内经常连不上，但 `api.github.com` 通常可用。
> App 的下载逻辑因此**优先走 API 附件接口**（`api.github.com/.../releases/assets/<id>`），
> 失败才回退到网页下载地址；两者都失败时，对话框里会给一个「在浏览器中打开发布页」的按钮，
> 可以手动下载 APK 安装。

---

## 三、以后怎么发新版

改完代码，推到 `main` 就行：

```bash
git add -A
git commit -m "改了什么"
git push origin main
```

GitHub Actions 会自动构建并发布下一个 Release（约 2-4 分钟）。
也可以在网页上手动触发：仓库 → Actions → `Build & Release` → Run workflow。

版本号规则（不要改乱，App 靠它判断新旧）：

| 项 | 值 | 例 |
| --- | --- | --- |
| `versionCode` | `1000 + 运行序号` | 1003 |
| `versionName` | `1.运行序号` | 1.3 |
| Release tag | `v1.0.(1000+运行序号)` | v1.0.1003 |

App 取 tag 最后一段数字与自己的 `versionCode` 比较 —— **两处必须一致**。

---

## 四、第一次发布时踩到的坑（已修好，供以后排查）

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `git push` 403 `Permission denied` | Fine-grained 令牌只有 `metadata: read` | 令牌需要 **Contents: Read and write**，以及 **Workflows: Read and write**（推 `.github/workflows` 必须） |
| 用 Fine-grained 令牌建仓库 403 | GitHub 不支持 fined-grained 令牌创建个人仓库 | 网页手动建空仓库，或用 classic 令牌（勾 `repo`） |
| Release 附件名变成 `-1.1.apk` | `gh` 上传时中文名被吃掉 | 附件名改成纯 ASCII `timetable-x.y.apk` |
| Actions 报 403 无法发 Release | 仓库 *Workflow permissions* 是只读 | Settings → Actions → General → **Read and write** |
| 手机上更新提示"应用未安装" | APK 签名与已装的不一致 | 卸载旧包；并确保 `keystore/debug.keystore` 在仓库里（`.gitignore` 已加白名单） |

---

## 五、安全事项

发布时用到的 GitHub 令牌**已经出现在对话记录里**，请现在就去撤销：

<https://github.com/settings/tokens?type=beta> → 找到那个令牌 → **Delete**

仓库本身不需要任何令牌；以后你在本机用 git 推送时，用新令牌或浏览器授权即可。
