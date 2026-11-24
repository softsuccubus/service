#!/usr/bin/env bash
# 端到端验证：以学生身份进入考试 → 提交 Python 代码 → 轮询评测结果（真实沙箱引擎）。
# 前置：MySQL 与应用已启动（JUDGE_ENGINE=sandbox），沙箱镜像已构建，且存在进行中的考试。
# 用法：  bash sandbox/e2e-judge-check.sh <python源码文件> [examId]
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
STUDENT="${STUDENT:-student}"
PASSWORD="${PASSWORD:-student123456}"
CODE_FILE="${1:?用法: e2e-judge-check.sh <python源码文件> [examId]}"
EXAM_ID="${2:-}"

py() { python -c "$@"; }
api() { curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$STUDENT\",\"password\":\"$PASSWORD\"}" | py 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
[ -n "$TOKEN" ] || { echo "登录失败"; exit 1; }

EXAMS=$(api "$BASE/api/exams/student")
if [ -z "$EXAM_ID" ]; then
  EXAM_ID=$(echo "$EXAMS" | py '
import sys, json
data = json.load(sys.stdin)["data"]
running = [e for e in data if e.get("status") == "RUNNING" and e.get("canEnter")]
print((running or data)[0]["id"])')
fi
echo "考试 ID=$EXAM_ID"

api -X POST "$BASE/api/exams/$EXAM_ID/enter" > /dev/null

PVID=$(api "$BASE/api/exams/$EXAM_ID/paper" | py '
import sys, json
print(json.load(sys.stdin)["data"]["problems"][0]["problemVersionId"])')
echo "题目版本 ID=$PVID"

py '
import json, sys, uuid
code = open(sys.argv[1], encoding="utf-8").read()
json.dump({"problemVersionId": int(sys.argv[2]), "language": "PYTHON", "sourceCode": code,
           "idempotencyKey": uuid.uuid4().hex}, sys.stdout)
' "$CODE_FILE" "$PVID" > /tmp/judge-payload.json

SUB=$(api -X POST "$BASE/api/exams/$EXAM_ID/submissions" -d @/tmp/judge-payload.json)
SUB_ID=$(echo "$SUB" | py 'import sys,json;print(json.load(sys.stdin)["data"]["submissionId"])')
echo "提交 ID=$SUB_ID"

DETAIL=""
for _ in $(seq 1 40); do
  DETAIL=$(api "$BASE/api/submissions/$SUB_ID")
  STATUS=$(echo "$DETAIL" | py 'import sys,json;print(json.load(sys.stdin)["data"]["judgeStatus"])')
  echo "  评测状态: $STATUS"
  [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "MANUAL_REVIEW" ] && break
  sleep 2
done

echo "--- 逐测试点结果 ---"
echo "$DETAIL" | py '
import sys, json
d = json.load(sys.stdin)["data"]
for r in d.get("results") or []:
    print("  pv=%-3s %-4s %5sms %7sKB  %s" % (r.get("testCaseVersionId"), r.get("verdict"),
          r.get("timeUsedMs"), r.get("memoryUsedKb"), r.get("detailSummary") or ""))
print("  总判定:", d.get("overallVerdict"), "| 得分:", d.get("earnedScore"), "/", d.get("totalPossible"),
      "| 任务:", d.get("judgeStatus"))
if d.get("lastError"):
    print("  lastError:", d["lastError"])
'
