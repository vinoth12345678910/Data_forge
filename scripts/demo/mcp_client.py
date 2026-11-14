#!/usr/bin/env python3
"""A scripted MCP client (not an AI agent): starts `forge mcp`, sends real JSON-RPC requests and prints a compact line
per response. Usage: mcp_client.py <path to forge launcher>"""
import json, subprocess, sys

p = subprocess.Popen([sys.argv[1], "mcp"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
n = 0
def call(method, params=None, label=None):
    global n
    n += 1
    req = {"jsonrpc": "2.0", "id": n, "method": method}
    if params is not None:
        req["params"] = params
    print("->", label or method, flush=True)
    p.stdin.write(json.dumps(req) + "\n"); p.stdin.flush()
    return json.loads(p.stdout.readline())
def tool(name, args, label):
    r = call("tools/call", {"name": name, "arguments": args}, label)
    if "error" in r:
        print("<- error", r["error"]["code"], r["error"]["message"])
    else:
        env = json.loads(r["result"]["content"][0]["text"])
        print("<- isError", str(r["result"]["isError"]).lower() + ",", env["command"], "exit", env["exit"])
r = call("initialize", {"protocolVersion": "2025-06-18"})
print("<- server", r["result"]["serverInfo"]["name"], r["result"]["serverInfo"]["version"])
r = call("tools/list")
print("<-", len(r["result"]["tools"]), "tools:", ", ".join(t["name"].replace("forge_", "") for t in r["result"]["tools"]))
tool("forge_verify", {}, "tools/call forge_verify")
tool("forge_cleanup_dry_run", {}, "tools/call forge_cleanup_dry_run")
tool("forge_cleanup_run", {}, "tools/call forge_cleanup_run")
tool("forge_verify", {"url": "postgres://elsewhere/db"}, "tools/call forge_verify {url: ...}")
p.stdin.close(); p.wait()
