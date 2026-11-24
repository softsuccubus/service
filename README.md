# 在线编程评测与考试平台 — 后端服务

Spring Boot 4 + Java 25 + MySQL 的分层单体后端，实现需求规格说明书中的 UC-01～UC-16：
认证与角色授权、题库与测试点、组卷、考试生命周期、学生作答、代码提交、异步评测、
成绩计算与导出、教师监控、管理员用户管理与审计。

## 1. 快速开始

```bash
# 1) 启动数据库（项目根目录已提供 docker-compose.yml）
docker compose up -d

# 2) 运行服务（默认 http://localhost:8080；评测引擎默认 mock）
./mvnw spring-boot:run

# 可选：构建 Python 评测沙箱镜像，并以真实沙箱引擎运行（详见 §2.6）
docker build -t judge-python-sandbox:latest ./sandbox
JUDGE_ENGINE=sandbox ./mvnw spring-boot:run

# 3) 运行测试（使用 H2 内存库，不依赖 MySQL；
#    含沙箱引擎用例——无 Docker 或未构建镜像时自动跳过）
./mvnw test
```

首次启动会自动创建表结构、内置角色与演示账号（`app.seed.enabled=false` 可关闭）：

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `admin123456` | 管理员 |
| `teacher` | `teacher123456` | 教师 |
| `student` | `student123456` | 学生 |

同时会创建一道演示题（`DEMO-001` A+B）、一份已发布试卷与一场进行中的考试，可直接走通
"登录 → 进入考试 → 编码 → 提交 → 评测 → 查分" 全流程。

## 2. 关键设计

### 2.1 分层结构

```
api            HTTP 接口、DTO、统一响应与异常处理
application    用例编排：认证、题库、组卷、考试、作答、提交、评分、审计
domain         实体与枚举（JPA）
infrastructure 仓储、鉴权、JSON、消息队列、评测引擎、定时任务、数据初始化
presentation   按角色聚合的首页视图
```

### 2.2 认证与授权

- 登录签发 HMAC-SHA256 签名的无状态令牌（`userId.expiry.signature`），密码使用 BCrypt 散列。
- 每次请求由 `AuthInterceptor` 校验签名与有效期，并**从数据库重新加载角色与账户状态**，
  因此禁用/锁定账户立即失效。
- 控制器通过 `@CurrentUser` 注入 `AuthPrincipal`；`AccessGuard` 以"默认拒绝"方式做角色校验，
  各服务再做对象级校验（仅创建者或管理员可修改题目/试卷/考试）。

### 2.3 考试状态机（BR-01）

`DRAFT → SCHEDULED → RUNNING → SUSPENDED / ENDED → ARCHIVED`，非法迁移返回 409。
`ExamLifecycleJob` 每 5 秒按服务端时间自动开始/结束考试，并将超时的进行中记录置为 `EXPIRED`。
个人截止时间 = `min(考试结束时间, 进入时间 + 个人时长)`（BR-02）。

### 2.4 提交与评测可靠性（BR-04/BR-05/BR-08）

- 提交、`judge_task`、`outbox_event` 在**同一事务**内写入；提交成功即评测可靠入队。
- 幂等键唯一：重复提交返回既有结果且 `idempotentReplay=true`。
- `OutboxPublisher` 轮询未投递事件，以至少一次语义投递到应用内队列（可替换为 RabbitMQ）。
- `JudgeWorker` 消费队列并周期扫描数据库兜底；条件更新 + 租约保证并发下同一任务只被领取一次。
- 失败按 5s/10s/20s 退避重试，超过上限转 `MANUAL_REVIEW` 并写审计；引擎调用不占用事务。
- 结果写入前删除旧结果，配合 `(judge_task_id, test_case_version_id)` 唯一约束实现重复消费幂等。

### 2.5 评测引擎

评测引擎实现 `JudgeEngine` 并在 `JudgeEngineAdapter` 注册，由 `app.judge.engine` 选择，
业务层无需改动。内置两种：

**`mock`（默认）** 为**确定性模拟引擎，不执行任何用户代码**，用于没有沙箱环境的验收演示。
源码中出现下列标记即可触发对应判定（大小写不敏感）：
`COMPILE_ERROR` / `WRONG_ANSWER` / `TIME_LIMIT` / `MEMORY_LIMIT` / `RUNTIME_ERROR`。
`WRONG_ANSWER` 只让隐藏测试点失败，便于演示部分得分。

**`sandbox`** 为**真实隔离沙箱**：用 `docker run` 在一次性容器中执行用户代码并逐测试点判定，
当前支持 `PYTHON`。详见 [§2.6 沙箱](#26-代码评测沙箱docker)。

### 2.6 代码评测沙箱（Docker）

`sandbox/` 目录提供 Python 评测沙箱：镜像内是受信任的监管进程 `runner.py`，被测代码在它
`fork` 出的子进程中执行，**隔离由镜像与 `docker run` 参数双层保证**。

| 层 | 手段 | 作用 |
| --- | --- | --- |
| 容器 | `--network none` | 无网络访问 |
| 容器 | `--read-only` + `--tmpfs /tmp` | 根文件系统只读，仅 `/tmp` 可写（`noexec`、64 MB 上限） |
| 容器 | `--cap-drop ALL`、`--security-opt no-new-privileges` | 去掉全部 capability，禁止提权 |
| 容器 | `--user 65534:65534` | 以 nobody 运行 |
| 容器 | `--pids-limit`、`--memory`、`--cpus` | 限制进程数、内存与 CPU |
| 进程 | `RLIMIT_AS` / `RLIMIT_CPU` / `RLIMIT_FSIZE` / `RLIMIT_NOFILE` / `RLIMIT_NPROC` | 内存、CPU、写盘、句柄、进程数上限 |
| 进程 | `setsid` + 墙钟超时后 `killpg(SIGKILL)` | 超时连同子进程整组清理 |
| 进程 | `python3 -I -B` + 精简环境变量 | 忽略环境变量与用户 site，不写 `.pyc` |

**判定**：`AC` 输出比对通过（忽略行尾空白与末尾空行）、`WA` 输出不一致、`CE` 语法错误、
`TLE` 超墙钟限、`MLE` 触发 `RLIMIT_AS`（识别 `MemoryError`）、`RE` 非零退出或信号终止。
内存用量取自 `wait4` 的 `ru_maxrss`。

**隐藏数据最小化（BR-06）**：期望输出**不进入容器**，只在应用侧比对；逐测试点摘要不含隐藏输入与期望输出。

```bash
# 1) 构建镜像（改动 runner.py 后需重新构建）
docker build -t judge-python-sandbox:latest ./sandbox

# 2) 以沙箱引擎启动服务
JUDGE_ENGINE=sandbox ./mvnw spring-boot:run

# 3) 自检：直接调用沙箱协议，验证 AC/CE/TLE/MLE
bash sandbox/smoke-test.sh          # Windows: ./sandbox/smoke-test.ps1

# 4) 端到端：以学生身份提交代码并查看逐测试点判定
bash sandbox/e2e-judge-check.sh /tmp/我的解答.py
```

应用通过 `docker` CLI 启动沙箱容器，因此运行应用的账户需要有 Docker 访问权限；
沙箱相关参数（镜像、CPU、网络、内存余量、额外参数如 `--runtime=runsc`）见 §5 配置项。

> 单次评测的实际耗时上限 ≈ `测试点数 × (时间限制 + 2s) + 15s`（容器启动余量）。
> 若题目多、限时长，请同步调大 `app.judge.worker.lease-seconds`（默认 60），
> 否则 RUNNING 任务可能在执行期间被租约恢复逻辑误判为超时并重新排队。

### 2.7 测试数据保护（BR-06）

测试点输入/期望输出保存在可配置的受控目录（`app.judge.test-data-root`），数据库只存引用键，
读取时做根目录约束防路径穿越；学生端只返回 `PUBLIC` 测试点作为样例，隐藏测试点绝不进入响应。

### 2.8 成绩（BR-07）

每题取所有**成功提交**中的最高测试点得分，按测试点满分比例折算并封顶；多题求和为总分。
人工改分优先级最高，需填写原因，写入 `audit_event`（含前后值），且分数不得超过该题满分。

## 3. 版本快照

- `problem_version`：发布后不可变，发布时冻结测试点版本列表（`test_case_version_ids_json`）。
- 修改已发布题目会生成新草稿版本；组卷只允许引用已发布题目版本。
- `paper_version`：发布时固定题目顺序、分值并要求所有引用题目版本已发布。

题目归档生命周期：`在用 ⇄ 已归档 → 已删除`（`problem.archived`）。归档是软删除，
题目详情仍可通过 `GET /api/problems/{id}` 查看（但不再出现在列表与该题新的组卷选择中）；
已归档题目只读，修改/发布/变更测试点前必须先恢复。
永久删除仅对已归档题目开放，且要求未被任何试卷引用、无提交记录，删除时级联清理
题目版本、测试点版本与对应测试数据文件。

## 4. 接口一览

统一响应体：`{"code":0,"message":"ok","data":...}`，错误时 `code` 为 HTTP 状态码。

### 认证
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，返回令牌与用户信息 |
| GET | `/api/auth/me` | 当前用户 |

### 题库（教师/管理员）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/problems?onlyMine=&archived=` | 题目列表（`archived=true` 查看归档箱） |
| GET | `/api/problems/published` | 已发布题目（学生可见） |
| GET | `/api/problems/{id}` | 题目详情（含测试点原始数据，教师端；归档后仍可查看） |
| GET | `/api/problems/{id}/student-view` | 学生视图（仅公开样例） |
| POST | `/api/problems` | 新建题目 |
| PUT | `/api/problems/{id}` | 修改（已发布则生成新草稿版本；已归档需先恢复） |
| POST | `/api/problems/{id}/publish?versionId=` | 发布版本（冻结测试点） |
| DELETE | `/api/problems/{id}` | 归档题目（软删除，可查看/可恢复） |
| POST | `/api/problems/{id}/restore` | 恢复已归档题目 |
| DELETE | `/api/problems/{id}/permanent` | 永久删除已归档题目（未被试卷引用且无提交记录时） |
| POST/PUT/DELETE | `/api/problems/{id}/test-cases...` | 测试点增改与归档 |

### 组卷
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/papers` | 试卷列表 |
| POST | `/api/papers` | 新建试卷（可带题目项） |
| PUT | `/api/papers/{id}` | 修改（已发布则生成新草稿版本） |
| POST | `/api/papers/{id}/publish?versionId=` | 发布试卷版本 |

### 考试
| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/exams` | 教师 | 我的考试 |
| POST | `/api/exams` | 教师 | 创建考试 |
| PUT | `/api/exams/{id}` | 教师 | 修改（草稿/已排期） |
| POST | `/api/exams/{id}/publish\|start\|suspend\|resume\|end\|archive` | 教师 | 状态迁移 |
| GET | `/api/exams/{id}/monitor` | 教师 | 实时监控 |
| GET | `/api/exams/{id}/participants` | 教师 | 参与学生 |
| GET | `/api/exams/student` | 学生 | 我可参加的考试 |
| POST | `/api/exams/{id}/enter` | 学生 | 进入考试（幂等，不重置计时） |
| GET | `/api/exams/{id}/paper` | 学生 | 作答页数据 |
| PUT | `/api/exams/{id}/paper/{problemVersionId}/draft` | 学生 | 保存草稿（乐观版本校验） |
| POST | `/api/exams/{id}/submissions` | 学生 | 提交代码（幂等键） |
| GET | `/api/exams/{id}/submissions` | 学生 | 我的提交与评测结果 |
| POST | `/api/exams/{id}/submit` | 学生 | 交卷 |
| GET | `/api/exams/{id}/scores` | 教师 | 成绩表 |
| GET | `/api/exams/{id}/scores/export` | 教师 | 导出 CSV |
| PUT | `/api/exams/{id}/records/{recordId}/scores/{problemVersionId}` | 教师 | 人工改分 |

### 学生个人
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/me/scores` | 我的成绩 |
| GET | `/api/submissions/{id}` | 提交详情与逐测试点结果 |
| GET | `/api/exam-records/{recordId}/submissions` | 某次考试的提交历史 |
| GET | `/api/dashboard` | 按角色聚合的首页数据 |

### 管理端
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/admin/users` | 用户列表 / 创建用户 |
| PUT | `/api/admin/users/{id}/status` | 启用/锁定/禁用 |
| PUT | `/api/admin/users/{id}/roles` | 调整角色 |
| GET | `/api/admin/audit-events?targetType=&targetId=` | 审计事件 |
| GET | `/api/admin/judge-tasks?status=` | 评测任务 |
| POST | `/api/admin/judge-tasks/{id}/retry` | 重试评测 |
| POST | `/api/admin/judge-tasks/{id}/manual-review` | 转人工复核 |
| GET | `/api/admin/judge-config` | 评测配置 |

## 5. 配置项

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:mysql://localhost:3306/service` / `service` / `service123456` | 数据源 |
| `APP_AUTH_SECRET` | 开发默认值 | **生产必须覆盖**，至少 16 字符 |
| `app.auth.token-ttl-minutes` | 480 | 令牌有效期 |
| `app.judge.engine` | `mock` | 评测引擎（`mock` / `sandbox`） |
| `app.judge.supported-languages` | `JAVA,CPP,PYTHON` | 允许语言（`sandbox` 引擎当前仅实现 `PYTHON`） |
| `app.judge.test-data-root` | `./data/test-data` | 测试数据根目录 |
| `app.judge.worker.enabled/concurrency/max-attempts/lease-seconds` | `true/2/3/60` | 评测消费者 |
| `app.judge.sandbox.image` | `judge-python-sandbox:latest` | 沙箱镜像 |
| `app.judge.sandbox.docker-command` | `docker` | Docker CLI 命令（可换 `podman` 或绝对路径） |
| `app.judge.sandbox.cpus` / `network` / `pids-limit` | `1` / `none` / `128` | 容器 CPU、网络、进程数上限 |
| `app.judge.sandbox.tmpfs-size-mb` / `memory-headroom-mb` | `64` / `64` | `/tmp` 上限、容器内存余量 |
| `app.judge.sandbox.extra-args` | 空 | 追加到 `docker run` 的参数，如 `--runtime=runsc` |
| `app.exam.lifecycle.enabled/interval-ms` | `true/5000` | 考试生命周期定时任务 |
| `app.seed.enabled` | `true` | 是否初始化演示数据 |

## 6. 数据表

`sys_user`、`sys_role`、`sys_user_role`、`problem`、`problem_version`、`test_case`、
`test_case_version`、`paper`、`paper_version`、`paper_item`、`exam`、`exam_record`、
`answer_draft`、`submission`、`judge_task`、`judge_result`、`score`、`audit_event`、`outbox_event`。

实现扩展字段（不影响规格语义）：
`problem_version.test_case_version_ids_json`（发布冻结测试点）、
`outbox_event.attempt_count/last_error`（投递重试）、
`exam.owner_id`（对象级授权）、
`submission.idempotency_key` / `judge_task.idempotency_key`（幂等）。

## 7. 已知限制

- `sandbox` 引擎当前仅实现 **Python**；`JAVA`/`CPP` 提交会返回错误并触发任务重试，
  接入时在 `sandbox/` 增加对应编译运行步骤即可。
- 沙箱通过挂载的 Docker CLI 启动容器，**宿主机需可访问 Docker**；若把应用本身也容器化，
  需挂载 `/var/run/docker.sock`（等价于交出宿主机 root 权限），此场景建议改用独立评测服务或 rootless 方案。
- 消息通道为进程内队列 + 数据库兜底，单实例可用；多实例部署时请替换为 RabbitMQ/Kafka 实现。
- 表结构由 Hibernate `ddl-auto=update` 生成，生产环境建议改用 Flyway/Liquibase 管理迁移。
