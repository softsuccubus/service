# Python 评测沙箱镜像
#
# 负责在隔离环境中执行被测 Python 代码；隔离由「镜像 + docker run 参数」共同保证：
#   镜像层：非 root 用户、无多余工具
#   运行层：--network none / --read-only / --cap-drop ALL / --pids-limit / --memory
#           （见 SandboxJudgeEngine，应用按每次评测动态叠加）
#
# 构建：  docker build -t judge-python-sandbox:latest ./sandbox
FROM python:3.12-slim

# 只读根文件系统下的可写目录只有 tmpfs 挂载的 /tmp；受限执行所需的解释器全部就绪。
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    HOME=/tmp \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# 以 nobody(65534) 运行，与 docker run --user 65534:65534 保持一致。
RUN mkdir -p /sandbox && chown 65534:65534 /sandbox

COPY --chown=65534:65534 runner.py /sandbox/runner.py

USER 65534:65534
WORKDIR /sandbox

# 评测任务以 JSON 从 stdin 传入，结果以 JSON 从 stdout 返回。
ENTRYPOINT ["python3", "/sandbox/runner.py"]
