#!/usr/bin/env bash
# 沙箱本地自检：构建镜像并对若干样例代码执行评测协议。
# 用法：  ./sandbox/smoke-test.sh          （需已安装 Docker）
set -euo pipefail

IMAGE="${JUDGE_SANDBOX_IMAGE:-judge-python-sandbox:latest}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo ">> 构建镜像 $IMAGE"
docker build -t "$IMAGE" "$HERE"

run() {
  docker run --rm -i \
    --network none --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777 \
    --cap-drop ALL --security-opt no-new-privileges \
    --pids-limit 128 --memory 512m --memory-swap 512m \
    --user 65534:65534 \
    "$IMAGE"
}

echo
echo ">> 用例 1：正确解答（A+B）"
run < "$HERE/sample-job.json"
echo

echo ">> 用例 2：语法错误（应 compileError=true）"
printf '%s' '{"source":"def f(:\n  pass","cases":[{"input":""}],"timeLimitMs":1000,"memoryLimitMb":256}' | run
echo

echo ">> 用例 3：死循环（应 timedOut=true）"
printf '%s' '{"source":"while True:\n  pass","cases":[{"input":""}],"timeLimitMs":1000,"memoryLimitMb":256}' | run
echo

echo ">> 用例 4：内存超限（应 memoryExceeded=true）"
printf '%s' '{"source":"x=[0]*(10**9)","cases":[{"input":""}],"timeLimitMs":2000,"memoryLimitMb":64}' | run
echo
echo ">> 自检完成"
