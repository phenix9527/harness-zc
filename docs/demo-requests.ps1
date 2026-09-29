<#
  api-demo —— 五个场景的手工验证脚本（Windows PowerShell 版）

  为什么单独有这么一个脚本？
  ------------------------------------------------------------------
  学习文档里的示例命令是 Linux/Git Bash 的 curl 写法。但在 **Windows PowerShell 里 curl 不是 curl**：
  它是 Invoke-WebRequest 的别名，参数体系完全不同 —— 所以 `curl -H "..." -d '{...}'` 直接报
  「无法绑定参数 Headers，无法将 System.String 转换为 IDictionary」。

  两条出路（本脚本用第 1 条）：
    1. Invoke-WebRequest/RestMethod —— PowerShell 原生，对象进对象出，无需手工转义 JSON。推荐。
    2. curl.exe                     —— 必须显式带 .exe 才是真 curl；而且 PowerShell 5.1 把它交给
                                       原生 exe 时**会吃掉双引号**，JSON 得写成 '{\"userId\":1}'，
                                       否则服务端收到非法 JSON。脚本末尾有对照。

  ★ 三个 Windows PowerShell 5.1 专属的坑（都已在脚本内解决，写出来是让你少踩一次）
  ------------------------------------------------------------------
  1. **执行策略**：直接 `.\doc\demo-requests.ps1` 可能报「因为在此系统上禁止运行脚本」。
     这不是脚本的问题。任选一种方式运行：
        powershell -ExecutionPolicy Bypass -File .\doc\demo-requests.ps1
        # 或当前会话临时放开（关掉窗口即失效，不动机器全局设置）
        Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
  2. **文件编码必须是 UTF-8 with BOM**：PS 5.1 读 .ps1 时默认按 ANSI(GBK) 解码，
     文件里只要有中文（注释也算），无 BOM 就会整片乱码 + 解析报「字符串缺少终止符」。
     （PowerShell 7 默认 UTF-8，没这个问题。）用编辑器另存时记得选「UTF-8 with BOM」。
  3. **响应体中文乱码**：Spring Boot 返回的 Content-Type 是 `application/json`（不带 charset），
     PS 5.1 的 Invoke-RestMethod 会按 ISO-8859-1 解码 → 中文变 `æ´»å¨`。
     服务端没问题（curl/浏览器都正常），是客户端解码问题。本脚本改成取原始字节再按 UTF-8 解码。

  ★ 观察限流必须知道的三件事
  ------------------------------------------------------------------
  A. **限流在最前面**（CouponClaimService 第 0 步，早于幂等判断）。同一 userId 连打两次，
     第二次很可能拿到 `20010` 而不是「幂等返回上次券号」——不是 bug，是限流先生效。
     要观察幂等，两次调用之间必须留够窗口时间（用户维度窗口 1 秒，脚本等 1.5 秒）。
  B. **本机测试时 IP 限流会先生效**：所有请求都来自 127.0.0.1，IP 维度是 10 次/分钟。
     **看 retryAfter 量级就能分辨是谁拦的**：
        ≈1  -> 用户维度（1 次/秒）
        ≈60 -> IP 维度（10 次/分钟）
     重置命令：docker exec apidemo-redis redis-cli del "rate:coupon:ip:127.0.0.1"
  C. **顺序调用撞不上用户限流**：一次请求往返约 900ms，已经超过 1 秒窗口。
     要看到 20010 必须**并发**发（脚本用 HttpClient 同时发 5 个）。

  用法
  ------------------------------------------------------------------
    PS> powershell -ExecutionPolicy Bypass -File .\doc\demo-requests.ps1
    PS> .\doc\demo-requests.ps1 -BaseUrl http://127.0.0.1:18080   # 换端口
    PS> .\doc\demo-requests.ps1 -UserId 8801 -Mobile 13800001111  # 换测试数据

  前提：docker compose up -d 起好 MySQL/Redis/RabbitMQ，应用已启动。
#>
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [long]$UserId = 8801,
    [string]$Mobile = "13800001111"
)

$ErrorActionPreference = "Stop"

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "=== $Title ===" -ForegroundColor Cyan
}

<# 把响应流按 UTF-8 解码成字符串。
   为什么不能直接用 .Content：PS 5.1 会按响应头里的 charset 解码，而我们的接口返回
   `application/json` 不带 charset，于是被当成 ISO-8859-1 → 中文全变乱码。 #>
function ConvertFrom-Utf8Stream {
    param($Stream)
    $ms = New-Object System.IO.MemoryStream
    $Stream.CopyTo($ms)
    return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

<#
  统一请求封装。三个细节都是为了「能看清失败路径」：
    1. Invoke-WebRequest + 手动 UTF-8 解码，避免中文乱码（见文件头第 3 条）。
    2. try/catch 把 **5xx 的 HTTP 状态码 + 响应体**打出来 —— 这正是本项目的观察重点
       （业务失败走 HTTP 200 + code≠0，只有系统异常才走 5xx）。
       不 catch 的话只剩一句英文报错，看不到服务端到底说了什么。
    3. -UseBasicParsing：不依赖 IE 解析引擎，受限环境也能跑。
#>
function Invoke-Demo {
    param(
        [string]$Method,
        [string]$Path,
        $Body,                          # hashtable 或字符串；函数内部负责转 JSON
        [hashtable]$Headers
    )
    $p = @{ Method = $Method; Uri = "$BaseUrl$Path"; UseBasicParsing = $true }
    if ($Body) {
        $p.ContentType = "application/json"
        # 显式按 UTF-8 编码字节：否则含非 ASCII 字符的 body 发出去可能变形
        $p.Body = [System.Text.Encoding]::UTF8.GetBytes(
            $(if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 5 }))
    }
    if ($Headers) { $p.Headers = $Headers }

    try {
        $resp = Invoke-WebRequest @p
        return (ConvertFrom-Utf8Stream $resp.RawContentStream)
    } catch {
        $r = $_.Exception.Response
        if ($r) {
            $text = ConvertFrom-Utf8Stream $r.GetResponseStream()
            return "HTTP $([int]$r.StatusCode) :: $text"
        }
        return "ERR :: $($_.Exception.Message)"
    }
}

<#
  真·并发请求。
  为什么必须并发：见文件头 C 条 —— 顺序调用每次都超过 1 秒窗口，永远撞不上 20010，
  你会误以为「限流没生效」。（PS 5.1 没有 ForEach-Object -Parallel，Start-Job 冷启进程又太慢，
  所以直接用 .NET HttpClient。）
#>
function Invoke-ConcurrentPost {
    param(
        [string]$Path,
        [string]$JsonBody,
        [int]$Count = 5
    )
    # PS 5.1 默认不加载 System.Net.Http，不显式 Add-Type 会报「找不到类型 [HttpClient]」
    Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

    $client = New-Object System.Net.Http.HttpClient
    # 用强类型 Task 列表：WaitAll 要的是 Task[]，普通 @() 数组会被隐式转换坑到
    $tasks = New-Object 'System.Collections.Generic.List[System.Threading.Tasks.Task]'
    for ($i = 0; $i -lt $Count; $i++) {
        $content = New-Object System.Net.Http.StringContent(
            $JsonBody, [System.Text.Encoding]::UTF8, "application/json")
        $tasks.Add($client.PostAsync("$BaseUrl$Path", $content))
    }
    # 等全部完成再逐个取结果，否则拿到的是半成品
    [System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())

    $out = @()
    foreach ($t in $tasks) {
        # 同样按字节取再 UTF-8 解码，别用 ReadAsStringAsync（一样的乱码问题）
        $bytes = $t.Result.Content.ReadAsByteArrayAsync().Result
        $out += [System.Text.Encoding]::UTF8.GetString($bytes)
    }
    $client.Dispose()
    return $out
}

# ---------------------------------------------------------------- 前置检查
Write-Host "target = $BaseUrl   userId = $UserId   mobile = $Mobile" -ForegroundColor DarkGray
Write-Section "前置检查 · actuator health（db/redis/rabbit 应全 UP）"
$health = Invoke-Demo -Method Get -Path "/actuator/health"
Write-Host $health
if ($health -notmatch '"status":"UP"') {
    Write-Host "健康检查未通过，后面的用例没有意义，先解决依赖。" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- 场景1 优惠券领取
Write-Section "场景1 领券 · 首次（期望 code=0, newlyClaimed=true）"
Invoke-Demo -Method Post -Path "/inner/coupon/SPRING_2026/claim" -Body @{ userId = $UserId }

Write-Section "场景1 领券 · 等 1.5 秒后重领（期望 code=0, newlyClaimed=false，券号与首次相同）"
Write-Host "  为什么要等：限流在幂等之前，不等的话第二次会先被限流拦成 20010。" -ForegroundColor DarkGray
Start-Sleep -Milliseconds 1500
Invoke-Demo -Method Post -Path "/inner/coupon/SPRING_2026/claim" -Body @{ userId = $UserId }

Write-Section "场景1 领券 · 5 个并发请求打另一个用户（期望出现 20010 retryAfter=1，其余幂等）"
$concurrentUser = $UserId + 1000
$results = Invoke-ConcurrentPost -Path "/inner/coupon/SPRING_2026/claim" `
    -JsonBody (@{ userId = $concurrentUser } | ConvertTo-Json -Compress) -Count 5
$i = 1
foreach ($r in $results) {
    # 去掉 traceId 便于逐字比对；真实排查时 traceId 恰恰是要留的
    Write-Host ("  resp{0}: {1}" -f $i++, ($r -replace ',"traceId":"[a-f0-9]*"', ''))
}
Write-Host "  并发下最多只有 1 个请求真正发券（其余被限流或被幂等挡回同一张）——这就叫不超发。" -ForegroundColor DarkGray

Write-Section "场景1 领券 · 不存在的活动（期望 20000 活动不存在）"
Start-Sleep -Milliseconds 1500
Invoke-Demo -Method Post -Path "/inner/coupon/NOT_EXIST_2026/claim" -Body @{ userId = $UserId }

Write-Section "场景1 领券 · 缺 userId（期望 10001 参数类，绝不该是 30001「系统繁忙」）"
Start-Sleep -Milliseconds 1500
# 注意传的是字符串 '{}' 而不是 hashtable @{}：空 hashtable 在 PowerShell 里是「假值」，
# 会被 if ($Body) 判掉导致整个 body 不发，那测的就不是「缺字段」而是「没传 body」了。
Invoke-Demo -Method Post -Path "/inner/coupon/SPRING_2026/claim" -Body '{}'

# ---------------------------------------------------------------- 场景3 短信验证码
Write-Section "场景3 短信 · 首次（期望 code=0, expiresIn=300）"
Invoke-Demo -Method Post -Path "/inner/sms/code" -Body @{ mobile = $Mobile; scene = "LOGIN" }

Write-Section "场景3 短信 · 立刻再发（期望 20004 发送过于频繁 + retryAfter≈60）"
Invoke-Demo -Method Post -Path "/inner/sms/code" -Body @{ mobile = $Mobile; scene = "LOGIN" }

Write-Section "场景3 短信 · 非法 scene 枚举（期望 10001 请求体格式不合法，不应落 30001）"
Invoke-Demo -Method Post -Path "/inner/sms/code" -Body @{ mobile = $Mobile; scene = "NOT_EXIST" }

Write-Section "场景3 短信 · 手机号格式错（期望 10001 + 明确的字段名）"
Invoke-Demo -Method Post -Path "/inner/sms/code" -Body @{ mobile = "abc"; scene = "LOGIN" }

# ---------------------------------------------------------------- 场景5 异步导出
Write-Section "场景5 导出 · 提交（期望 code=0, status=PENDING, progress=0）"
$requestId = "ps-demo-" + (Get-Date -Format "HHmmss")
Invoke-Demo -Method Post -Path "/inner/export/tasks" -Headers @{ "X-User-Id" = "$UserId" } `
    -Body @{ requestId = $requestId; bizType = "ORDER"; queryJson = (@{ rows = 200 } | ConvertTo-Json -Compress) }

Write-Section "场景5 导出 · 同 requestId 重提（幂等判据是 taskId 相同；status 可能已变 RUNNING/SUCCESS）"
Invoke-Demo -Method Post -Path "/inner/export/tasks" -Headers @{ "X-User-Id" = "$UserId" } `
    -Body @{ requestId = $requestId; bizType = "ORDER"; queryJson = (@{ rows = 200 } | ConvertTo-Json -Compress) }

Write-Section "场景5 导出 · 少传 X-User-Id（期望 10002 缺少请求头）"
Invoke-Demo -Method Post -Path "/inner/export/tasks" `
    -Body @{ requestId = "$requestId-b"; bizType = "ORDER"; queryJson = (@{ rows = 10 } | ConvertTo-Json -Compress) }

Write-Section "场景5 导出 · 等 3 秒后查状态（期望 SUCCESS + resultUrl + expireAt）"
Start-Sleep -Seconds 3
Write-Host "  把上面返回的 taskId 填进来："
Write-Host "    Invoke-Demo -Method Get -Path '/inner/export/tasks/<taskId>?userId=$UserId'"
Write-Host "  越权对照：把 userId 换成 9999，期望 20008 导出任务不存在" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 结果核对（可选）
Write-Section "核对 · Redis / MySQL 侧的真实变化（需要 docker 在跑）"
# 注意：here-string 的结束符 "@ 必须独占一行的行首。写成 `"@ -ForegroundColor DarkGray`
# 在部分环境下会解析失败 —— 先赋值给变量再交给 Write-Host，别图省事写一行。
$checkHint = @"
  # 库存是否真的被扣（活动 1，初始 100）
  docker exec apidemo-redis redis-cli get "coupon:stock:1"

  # 该用户是否只有 1 张券（并发未超发的直接证据）
  docker exec apidemo-mysql mysql -uroot -p123456 -D api_demo -e "select user_id,count(*) from t_coupon where user_id in ($UserId,$concurrentUser) group by user_id;"

  # 看限流键是谁在拦（用户维度 TTL≈1，IP 维度 TTL≈60）
  docker exec apidemo-redis redis-cli --scan --pattern "rate:coupon:*"

  # 重置 IP 限流（本机连测很容易撞到）
  docker exec apidemo-redis redis-cli del "rate:coupon:ip:127.0.0.1"

  # 实时看 Redis 收到的每条命令（阶段 0 的主力工具，最推荐）
  docker exec apidemo-redis redis-cli monitor
"@
Write-Host $checkHint -ForegroundColor DarkGray

# ---------------------------------------------------------------- curl.exe 的正确姿势
Write-Section "附：curl.exe 的引号陷阱对照"
$curlHint = @"
  ✗ 错：curl.exe ... -d '{"userId":999}'        # PowerShell 5.1 吃掉双引号 -> 服务端收到非法 JSON
  ✓ 对：curl.exe ... -d '{\"userId\":999}'
  ✓ 更省事（本脚本采用的写法）：
        Invoke-RestMethod -Method Post -Uri `$u -ContentType 'application/json' -Body '{"userId":999}'
"@
Write-Host $curlHint -ForegroundColor DarkGray

Write-Host ""
Write-Host "完成。要观察 MQ / 状态机 / 破坏性实验，见 doc\学习路径-从跑通到真懂.md。" -ForegroundColor Green
