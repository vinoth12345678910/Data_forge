#!/usr/bin/env python3
"""Drives an interactive program through a pseudo-terminal, like a person typing. Usage:
   pty_session.py '<json list of [expect, send]>' program [args...]
Prints the transcript (what a terminal would show, with ANSI colours removed)."""
import json, os, pty, re, select, subprocess, sys, time

script = json.loads(sys.argv[1])
master, slave = pty.openpty()
p = subprocess.Popen(sys.argv[2:], stdin=slave, stdout=slave, stderr=slave, close_fds=True)
os.close(slave)
buf = ""
def pump(timeout):
    global buf
    end = time.time() + timeout
    while time.time() < end:
        r, _, _ = select.select([master], [], [], 0.2)
        if r:
            try:
                d = os.read(master, 4096)
            except OSError:
                return False
            if not d:
                return False
            buf += d.decode("utf-8", "replace")
    return True
def wait_for(s, timeout=60):
    end = time.time() + timeout
    start = len(buf)
    while time.time() < end:
        if s in buf[start - len(s):] or s in buf[start:]:
            return True
        if not pump(0.3):
            break
        if s in buf[start:]:
            return True
    return False
pos = 0
for expect, send in script:
    seen = buf.find(expect, pos)
    deadline = time.time() + 90
    while seen < 0 and time.time() < deadline:
        pump(0.3)
        seen = buf.find(expect, pos)
    if seen < 0:
        break
    pos = seen + len(expect)
    time.sleep(0.15)
    os.write(master, (send + "\n").encode())
pump(2.0)
try:
    p.wait(timeout=20)
except Exception:
    p.kill()
text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", buf).replace("\r\n", "\n").replace("\r", "")
sys.stdout.write(text)
