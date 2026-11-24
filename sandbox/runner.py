#!/usr/bin/env python3
"""Python 代码评测沙箱执行器（在容器内运行）。

本进程是**受信任代码**；被测代码在独立的子进程中执行，受到多重限制：

* ``RLIMIT_AS``  限制地址空间（内存），超限触发 ``MemoryError``；
* ``RLIMIT_CPU`` 限制 CPU 秒数（墙钟超时由父进程兜底）；
* ``RLIMIT_FSIZE`` / ``RLIMIT_NOFILE`` / ``RLIMIT_NPROC`` 限制写文件、句柄与进程数；
* 子进程 ``setsid`` 独立进程组，超时时整组 ``SIGKILL``；
* 容器层再叠加 ``--network none`` / ``--read-only`` / ``--cap-drop ALL`` / 非 root。

协议（JSON over stdin/stdout）：

  输入::

      {"source": "...", "cases": [{"input": "..."}],
       "timeLimitMs": 1000, "memoryLimitMb": 256}

  输出::

      {"compileError": false, "compileMessage": "",
       "cases": [{"output": "...", "stderr": "...", "timeMs": 12, "memoryKb": 3400,
                  "exitCode": 0, "signal": null,
                  "timedOut": false, "memoryExceeded": false}]}

期望输出不进入沙箱：本进程只回传被测程序的真实输出，是否通过由调用方比对。
"""

import json
import math
import os
import resource
import signal
import sys
import threading
import time

WORK_DIR = "/tmp/job"
SOURCE_NAME = "main.py"

MAX_CAPTURE = 4 * 1024 * 1024      # 单用例最多捕获的输出字节，超出即截断并断开管道
STDERR_CAPTURE = 8 * 1024          # 回传的 stderr 上限
FSIZE_LIMIT = 16 * 1024 * 1024     # 子进程可写文件上限
NOFILE_LIMIT = 64
NPROC_LIMIT = 64
MIN_MEM_MB = 32                    # 低于此值解释器无法启动，按此下限执行

DEFAULT_TIME_MS = 1000
DEFAULT_MEM_MB = 256
KILL_GRACE_SEC = 0.15              # 超时后允许子进程收尾的宽限

PYTHON = sys.executable
CHILD_ENV = {
    "PATH": "/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin",
    "HOME": WORK_DIR,
    "TMPDIR": WORK_DIR,
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "PYTHONIOENCODING": "utf-8",
    "PYTHONDONTWRITEBYTECODE": "1",
    "PYTHONHASHSEED": "0",
}


def _kill_group(pid):
    """整组终止子进程；进程组 ID 等于 setsid 后的子进程 pid。"""
    try:
        os.killpg(pid, signal.SIGKILL)
    except OSError:
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass


def _drain(fd, buf, limit, state):
    """读空一个管道并追加到 buf，达到上限即截断并断开（子进程随后收到 EPIPE）。"""
    try:
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            remaining = limit - len(buf)
            if remaining <= 0:
                state["truncated"] = True
                break
            buf.extend(chunk[:remaining])
            if len(chunk) > remaining:
                state["truncated"] = True
                break
    except OSError:
        pass
    finally:
        try:
            os.close(fd)
        except OSError:
            pass


def _feed(fd, data):
    """把测试输入写入子进程 stdin，写完关闭；子进程提前退出时忽略 EPIPE。"""
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            view = view[written:]
    except OSError:
        pass
    finally:
        try:
            os.close(fd)
        except OSError:
            pass


def _signal_name(number):
    try:
        return signal.Signals(number).name
    except ValueError:
        return "SIG%d" % number


def run_case(source_path, input_bytes, time_limit_ms, mem_limit_mb):
    """在独立子进程中执行一次被测代码，返回单测试点结果字典。"""
    mem_mb = max(MIN_MEM_MB, int(mem_limit_mb))
    mem_bytes = mem_mb * 1024 * 1024
    # CPU 硬限比墙钟限多留 1 秒，保证 TLE 由本进程按墙钟判定（测量一致、可控），
    # RLIMIT_CPU 仅作为"墙钟失效时"的兜底。
    cpu_sec = max(1, int(math.ceil(time_limit_ms / 1000.0)) + 1)

    out_r, out_w = os.pipe()
    err_r, err_w = os.pipe()
    in_r, in_w = os.pipe()

    start = time.monotonic()
    pid = os.fork()
    if pid == 0:  # -------- 子进程（执行被测代码） --------
        try:
            os.setsid()
            os.dup2(in_r, 0)
            os.dup2(out_w, 1)
            os.dup2(err_w, 2)
            for fd in (out_r, out_w, err_r, err_w, in_r, in_w):
                if fd > 2:
                    try:
                        os.close(fd)
                    except OSError:
                        pass
            resource.setrlimit(resource.RLIMIT_AS, (mem_bytes, mem_bytes))
            resource.setrlimit(resource.RLIMIT_CPU, (cpu_sec, cpu_sec))
            resource.setrlimit(resource.RLIMIT_FSIZE, (FSIZE_LIMIT, FSIZE_LIMIT))
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
            for res, value in ((resource.RLIMIT_NOFILE, NOFILE_LIMIT),
                               (resource.RLIMIT_NPROC, NPROC_LIMIT)):
                try:
                    resource.setrlimit(res, (value, value))
                except (ValueError, OSError):
                    pass
            os.chdir(WORK_DIR)
            os.execve(PYTHON, [PYTHON, "-I", "-B", source_path], CHILD_ENV)
        except BaseException:
            os._exit(127)

    # -------- 父进程（受信任的监管者） --------
    os.close(in_r)
    os.close(out_w)
    os.close(err_w)

    out_buf = bytearray()
    err_buf = bytearray()
    state = {"truncated": False}
    t_out = threading.Thread(target=_drain, args=(out_r, out_buf, MAX_CAPTURE, state), daemon=True)
    t_err = threading.Thread(target=_drain, args=(err_r, err_buf, STDERR_CAPTURE, state), daemon=True)
    t_in = threading.Thread(target=_feed, args=(in_w, input_bytes), daemon=True)
    t_out.start()
    t_err.start()
    t_in.start()

    # 墙钟超时：等待 stdout 关闭（正常结束的信号）。超时则整组终止。
    limit_sec = time_limit_ms / 1000.0
    t_out.join(limit_sec + KILL_GRACE_SEC)
    timed_out = t_out.is_alive()
    elapsed_ms = int((time.monotonic() - start) * 1000)
    if timed_out:
        _kill_group(pid)

    t_out.join(2.0)
    t_err.join(2.0)
    t_in.join(2.0)

    # 等待回收子进程：先带截止时间轮询，避免"关闭 stdout 后继续空转"的代码把执行器挂死。
    wait_status = 0
    rusage = None
    deadline = time.monotonic() + (0.0 if timed_out else limit_sec) + 2.0
    while True:
        try:
            _, wait_status, rusage = os.wait4(pid, os.WNOHANG)
        except ChildProcessError:
            rusage = None
            break
        if rusage is not None:
            break
        if time.monotonic() >= deadline:
            timed_out = True
            _kill_group(pid)
        time.sleep(0.005)

    if rusage is None:  # 上面的循环未取到 rusage（进程已被回收）：退化为阻塞回收
        try:
            _, wait_status, rusage = os.wait4(pid, 0)
        except ChildProcessError:
            pass

    # ru_maxrss 为峰值常驻内存（KB）。
    memory_kb = int(rusage.ru_maxrss) if rusage is not None else 0
    if timed_out:
        elapsed_ms = min(max(elapsed_ms, time_limit_ms), time_limit_ms)

    exit_code = os.waitstatus_to_exitcode(wait_status)
    signal_name = None
    if exit_code < 0:
        signal_name = _signal_name(-exit_code)

    memory_exceeded = False
    if not timed_out and exit_code != 0:
        if b"MemoryError" in err_buf:
            memory_exceeded = True
        elif signal_name == "SIGSEGV" and memory_kb >= mem_mb * 1024 * 0.8:
            memory_exceeded = True

    return {
        "output": bytes(out_buf).decode("utf-8", "replace"),
        "truncated": state["truncated"],
        "stderr": bytes(err_buf).decode("utf-8", "replace"),
        "timeMs": time_limit_ms if timed_out else elapsed_ms,
        "memoryKb": memory_kb,
        "exitCode": exit_code,
        "signal": signal_name,
        "timedOut": timed_out,
        "memoryExceeded": memory_exceeded,
    }


def _blank_case(reason):
    return {
        "output": "",
        "truncated": False,
        "stderr": reason,
        "timeMs": 0,
        "memoryKb": 0,
        "exitCode": 1,
        "signal": None,
        "timedOut": False,
        "memoryExceeded": False,
    }


def main():
    raw = sys.stdin.buffer.read()
    job = json.loads(raw.decode("utf-8"))

    source = job.get("source") or ""
    cases = job.get("cases") or []
    time_limit_ms = int(job.get("timeLimitMs") or DEFAULT_TIME_MS)
    mem_limit_mb = int(job.get("memoryLimitMb") or DEFAULT_MEM_MB)

    os.makedirs(WORK_DIR, exist_ok=True)
    source_path = os.path.join(WORK_DIR, SOURCE_NAME)
    with open(source_path, "w", encoding="utf-8") as handle:
        handle.write(source)

    result = {"compileError": False, "compileMessage": "", "cases": []}

    # 语法检查：compile() 只做解析编译，不执行任何用户逻辑。
    try:
        compile(source, SOURCE_NAME, "exec")
    except SyntaxError as exc:
        line = exc.lineno if exc.lineno is not None else 0
        result["compileError"] = True
        result["compileMessage"] = "第 %d 行语法错误: %s" % (line, exc.msg)
    except (ValueError, OverflowError) as exc:
        result["compileError"] = True
        result["compileMessage"] = "代码无法编译: %s" % exc

    if result["compileError"]:
        message = result["compileMessage"]
        result["cases"] = [_blank_case(message) for _ in cases]
    else:
        for case in cases:
            data = (case.get("input") or "").encode("utf-8")
            result["cases"].append(run_case(source_path, data, time_limit_ms, mem_limit_mb))

    sys.stdout.write(json.dumps(result, ensure_ascii=False))
    sys.stdout.flush()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # 执行器自身故障：报错并以非零码退出，交由调用方重试/转人工
        sys.stderr.write("sandbox runner failure: %r\n" % (exc,))
        sys.exit(2)
