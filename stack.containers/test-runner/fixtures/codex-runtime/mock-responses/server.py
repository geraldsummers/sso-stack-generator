#!/usr/bin/env python3
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ASSISTANT_TEXT = os.environ.get("CODEX_MOCK_TEXT", "AGENT_RUNTIME_OK")
URL_FILE = os.environ.get("CODEX_MOCK_URL_FILE", "server.url")
REQUEST_LOG = os.environ.get("CODEX_MOCK_REQUEST_LOG", "requests.log")


def sse_event(payload):
    return f"event: {payload['type']}\ndata: {json.dumps(payload)}\n\n".encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "CodexMock/0.1"

    def do_POST(self):
        if self.path not in {"/responses", "/v1/responses"}:
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        with open(REQUEST_LOG, "a", encoding="utf-8") as handle:
            handle.write(
                json.dumps(
                    {
                        "path": self.path,
                        "body": body.decode("utf-8"),
                        "headers": {
                            "x-client-request-id": self.headers.get("X-Client-Request-Id"),
                            "x-request-id": self.headers.get("X-Request-Id"),
                        },
                    }
                ) + "\n"
            )
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("X-Request-Id", self.headers.get("X-Request-Id", "mock-upstream-request-id"))
        self.end_headers()
        events = [
            {
                "type": "response.created",
                "response": {
                    "id": "resp_mock",
                    "object": "response",
                    "created_at": 1234,
                    "model": "mock-model",
                    "status": "in_progress",
                    "output": [
                        {
                            "type": "message",
                            "role": "assistant",
                            "id": "msg_mock",
                            "status": "completed",
                            "content": [{"type": "output_text", "text": ASSISTANT_TEXT}],
                        }
                    ],
                    "usage": None,
                },
            },
            {
                "type": "response.output_item.done",
                "output_index": 0,
                "item": {
                    "type": "message",
                    "role": "assistant",
                    "id": "msg_mock",
                    "content": [{"type": "output_text", "text": ASSISTANT_TEXT}],
                },
            },
            {
                "type": "response.completed",
                "response": {
                    "id": "resp_mock",
                    "object": "response",
                    "created_at": 1234,
                    "model": "mock-model",
                    "status": "completed",
                    "output": [
                        {
                            "type": "message",
                            "role": "assistant",
                            "id": "msg_mock",
                            "status": "completed",
                            "content": [{"type": "output_text", "text": ASSISTANT_TEXT}],
                        }
                    ],
                    "usage": {
                        "input_tokens": 1,
                        "input_tokens_details": None,
                        "output_tokens": 1,
                        "output_tokens_details": None,
                        "total_tokens": 2,
                    },
                },
            },
        ]
        for event in events:
            self.wfile.write(sse_event(event))
        self.wfile.flush()

    def log_message(self, format, *args):
        return


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    with open(URL_FILE, "w", encoding="utf-8") as handle:
        handle.write(f"http://127.0.0.1:{server.server_address[1]}")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        thread.join()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
