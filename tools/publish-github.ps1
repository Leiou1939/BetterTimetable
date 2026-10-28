#Requires -Version 5.1
<#
  一键把课表 App 发布到 GitHub（建仓库 + 推送 + 触发自动发版）。

  用法：双击同目录的「一键发布到GitHub.bat」，按提示粘贴一次 GitHub 令牌。
  令牌只在本次运行的内存里用，不会写进 .git/config，也不会存到任何文件。

  需要令牌权限：
    - Classic token：勾 repo（私有仓库再加 workflow）
    - Fine-grained token：Contents: Read and write（若还要自动建仓库，另需 Administration）
#>

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BundledGit  = Join-Path (Split-Path -Parent $ProjectRoot) "_build_tools\mingit\cmd\git.exe"

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Ok($text)   { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn2($t)   { Write-Host "    $t" -ForegroundColor Yellow }
function Write-Err2($t)    { Write-Host "    $t" -ForegroundColor Red }

# ---------------------------------------------------------------- git 定位
$Git = $null
if (Test-Path $BundledGit) { $Git = $BundledGit }
else {
    $onPath = Get-Command git -ErrorAction SilentlyContinue
    if ($onPath) { $Git = $onPath.Source }
}
if (-not $Git) {
    Write-Err2 "找不到 git：既没有 $BundledGit，也不在 PATH 里。"
    exit 1
}
Write-Ok "git: $Git"

if (-not (Test-Path (Join-Path $ProjectRoot ".git"))) {
    Write-Err2 "工程目录里没有 .git（$ProjectRoot）。请在完整解压的工程目录里运行。"
    exit 1
}

# ---------------------------------------------------------------- 输入
Write-Step "你的 GitHub 账号"
Write-Host "  令牌在这里输入（不会显示、不会保存）。" -ForegroundColor DarkGray
Write-Host "  没有的话现在去建一个：https://github.com/settings/tokens/new" -ForegroundColor DarkGray
Write-Host "  （Classic token，勾选 repo 即可；有效期建议 7 天，用完删掉）" -ForegroundColor DarkGray

$secure = Read-Host "粘贴令牌（输入时屏幕不显示）" -AsSecureString
$bstr   = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try   { $Token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
$Token = $Token.Trim()
if ([string]::IsNullOrWhiteSpace($Token)) { Write-Err2 "令牌为空。"; exit 1 }

$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "User-Agent"           = "timetable-publisher"
    "X-GitHub-Api-Version" = "2022-11-28"
}

# ---------------------------------------------------------------- 校验令牌
Write-Step "验证令牌"
try {
    $me = Invoke-RestMethod -Uri "https://api.github.com/user" -Headers $headers -TimeoutSec 30
} catch {
    Write-Err2 "令牌无效或无法访问 api.github.com：$($_.Exception.Message)"
    exit 1
}
$Login = $me.login
Write-Ok "已登录：$Login"

$defaultName = "timetable"
$repoName = Read-Host "仓库名（回车用 $defaultName）"
if ([string]::IsNullOrWhiteSpace($repoName)) { $repoName = $defaultName }

$vis = Read-Host "公开仓库？(Y/n，回车=公开)"
$isPrivate = ($vis -match "^[nN]")

# ---------------------------------------------------------------- 建仓库
Write-Step "创建仓库 $Login/$repoName"
$created = $false
try {
    $body = @{
        name        = $repoName
        description = "课程表 App：PDF 导入、周视图、日历提醒联动、应用内更新"
        private     = $isPrivate
        has_issues  = $true
        auto_init   = $false
    } | ConvertTo-Json
    $repo = Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Headers $headers `
        -Method Post -Body $body -ContentType "application/json" -TimeoutSec 40
    Write-Ok "已创建：$($repo.html_url)"
    $created = $true
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -eq 422) {
        Write-Warn2 "仓库已存在，直接复用。"
    } elseif ($status -eq 403 -or $status -eq 404) {
        Write-Warn2 "这个令牌没有「创建仓库」权限（Fine-grained 令牌很常见）。"
        Write-Warn2 "请在浏览器里手动建一个空仓库（不要勾 README），建好后回来继续："
        Write-Host "    https://github.com/new?name=$repoName" -ForegroundColor White
        Read-Host "建好了按回车继续" | Out-Null
    } else {
        Write-Err2 "创建仓库失败：$($_.Exception.Message)"
        exit 1
    }
}

# ---------------------------------------------------------------- 推送
Write-Step "推送代码"
$remoteUrl = "https://github.com/$Login/$repoName.git"
& $Git -C $ProjectRoot remote remove origin 2>$null | Out-Null
& $Git -C $ProjectRoot remote add origin $remoteUrl

# 令牌放在一次性的 http.extraheader 里，推完立刻清掉，不落盘到 .git/config
$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$Token"))
& $Git -C $ProjectRoot config http.extraheader "AUTHORIZATION: basic $b64"
try {
    & $Git -C $ProjectRoot push -u origin main --quiet
    if ($LASTEXITCODE -ne 0) {
        Write-Err2 "推送失败。常见原因：令牌没有 repo/Contents 权限、仓库已被别人占用、网络不通 github.com。"
        exit 1
    }
    Write-Ok "推送成功"
} finally {
    & $Git -C $ProjectRoot config --unset http.extraheader 2>$null | Out-Null
}

if (-not $created) { $created = $true }

Write-Step "完成"
$summary = @"
    仓库：https://github.com/$Login/$repoName
    接下来 GitHub 会自动跑「Build & Release」（约 2-4 分钟），
    在 Releases 里生成 v1.0.10xx 和 APK 附件。

    如果 Actions 没跑起来，去这里点一次 Run workflow：
      https://github.com/$Login/$repoName/actions

    如果运行失败且报 403，去这里把 Workflow permissions 改成 Read and write：
      https://github.com/$Login/$repoName/settings/actions

    发布成功后，手机上：
      设置 → 检查更新 → 仓库填 $Login/$repoName → 检查更新 → 下载并安装
"@
Write-Host $summary -ForegroundColor White

$open = Read-Host "现在打开 Actions 页面？(Y/n，回车=是)"
if ($open -notmatch "^[nN]") {
    Start-Process "https://github.com/$Login/$repoName/actions"
}
