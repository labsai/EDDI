"""OpenAI-compatible mock LLM for the cluster demo.

    python mock_llm.py <logfile> <port>

Every chat request is appended to <logfile> as one JSON line:
  {"start": t0, "end": t1, "tag": "<conv-tag>", "messages": <count>,
   "user": "<last user message>", "prev_user": "<the user message before it>",
   "auth": "<sha256 prefix of the Authorization header>"}

The tag is the first token of the last user message when it looks like
"[tag]" — the demo prefixes every turn with its conversation tag, which is how
scenario 1 proves that no two turns of one conversation overlapped (start/end)
and that every turn saw the one committed just before it (prev_user is the
previous call's user message; the message count grows by 2 per committed turn
until the agent's history window caps it).

Behaviours, chosen by the last user message:
  contains "nap"      2 s delay, then a reply
  contains "slow"    70 s delay, then a reply (longer than EDDI's turn timeout)
  contains "linger"  15 s delay, then a reply (a long turn that still finishes)
  contains "error500" HTTP 500
  starts "usetool:<name>:<json>" (after the tag) — call that tool once
  anything else       an immediate echo
"""
import hashlib
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = open(sys.argv[1], "a", encoding="utf-8")
LOCK = threading.Lock()


def log(entry):
    with LOCK:
        LOG.write(json.dumps(entry) + "\n")
        LOG.flush()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith("/models"):
            return self._send(200, {"object": "list", "data": [{"id": "mock-1", "object": "model"}]})
        self._send(404, {"error": "nope"})

    def do_POST(self):
        start = time.time()
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        msgs = body.get("messages", [])
        last = msgs[-1] if msgs else {}
        content = last.get("content") if isinstance(last.get("content"), str) else json.dumps(last.get("content"))
        content = content or ""
        user_msgs = [m for m in msgs if m.get("role") == "user"]
        last_user = user_msgs[-1].get("content") if user_msgs else ""
        last_user = last_user if isinstance(last_user, str) else json.dumps(last_user)
        prev_user = user_msgs[-2].get("content") if len(user_msgs) > 1 else ""
        prev_user = prev_user if isinstance(prev_user, str) else json.dumps(prev_user)
        tag = last_user.split(" ", 1)[0] if last_user.startswith("[") else ""
        auth = self.headers.get("Authorization") or ""
        auth_hash = hashlib.sha256(auth.encode()).hexdigest()[:12] if auth else ""
        text = content[len(tag) + 1:] if tag and content.startswith(tag) else content
        tools = body.get("tools") or []
        code = 200
        if tools and last.get("role") == "user" and text.startswith("usetool:"):
            _, name, args = text.split(":", 2)
            msg = {"role": "assistant", "content": None,
                   "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": name, "arguments": args}}]}
            finish = "tool_calls"
        elif "error500" in content:
            code = 500
        elif "linger" in content and last.get("role") == "user":
            time.sleep(15)
            msg, finish = {"role": "assistant", "content": "lingered " + content[:40]}, "stop"
        elif "slow" in content and last.get("role") == "user":
            time.sleep(70)
            msg, finish = {"role": "assistant", "content": "slow reply"}, "stop"
        elif "nap" in content and last.get("role") == "user":
            time.sleep(2)
            msg, finish = {"role": "assistant", "content": "napped " + content[:40]}, "stop"
        else:
            msg, finish = {"role": "assistant", "content": f"ECHO[{len(msgs)}] {content[:60]}"}, "stop"
        log({"start": start, "end": time.time(), "tag": tag, "messages": len(msgs), "user": last_user[:80], "prev_user": prev_user[:80],
             "role": last.get("role"), "auth": auth_hash, "status": code,
             "tool_result": content[:4000] if last.get("role") == "tool" else None})
        if code != 200:
            return self._send(code, {"error": {"message": "mock upstream failure"}})
        usage = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
        self._send(200, {"id": "c1", "object": "chat.completion", "created": 1, "model": body.get("model"),
                         "choices": [{"index": 0, "message": msg, "finish_reason": finish}], "usage": usage})


class Server(ThreadingHTTPServer):
    # The default listen backlog of 5 drops connects under the demo's bursts,
    # which EDDI then reports as LLM connect timeouts.
    request_queue_size = 256
    daemon_threads = True


Server(("0.0.0.0", int(sys.argv[2])), Handler).serve_forever()
