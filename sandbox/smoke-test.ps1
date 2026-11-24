# 沙箱本地自检（Windows PowerShell 版）
# 用法：  ./sandbox/smoke-test.ps1
$ErrorActionPreference = "Stop"

$image = if ($env:JUDGE_SANDBOX_IMAGE) { $env:JUDGE_SANDBOX_IMAGE } else { "judge-python-sandbox:latest" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ">> 构建镜像 $image"
docker build -t $image $here
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

function Invoke-Sandbox([string]$jobJson) {
  $jobJson | docker run --rm -i `
    --network none --read-only `
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777 `
    --cap-drop ALL --security-opt no-new-privileges `
    --pids-limit 128 --memory 512m --memory-swap 512m `
    --user 65534:65534 `
    $image
}

Write-Host "`n>> 用例 1：正确解答（A+B）"
Get-Content (Join-Path $here "sample-job.json") -Raw | Invoke-Sandbox

Write-Host "`n>> 用例 2：语法错误（应 compileError=true）"
Invoke-Sandbox '{"source":"def f(:\n  pass","cases":[{"input":""}],"timeLimitMs":1000,"memoryLimitMb":256}'

Write-Host "`n>> 用例 3：死循环（应 timedOut=true）"
Invoke-Sandbox '{"source":"while True:\n  pass","cases":[{"input":""}],"timeLimitMs":1000,"memoryLimitMb":256}'

Write-Host "`n>> 用例 4：内存超限（应 memoryExceeded=true）"
Invoke-Sandbox '{"source":"x=[0]*(10**9)","cases":[{"input":""}],"timeLimitMs":2000,"memoryLimitMb":64}'

Write-Host "`n>> 自检完成"
