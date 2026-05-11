#!/usr/bin/env python3
import http.client
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


UPSTREAM_HOST = os.environ.get("JELLYFIN_UPSTREAM_HOST", "jellyfin")
UPSTREAM_PORT = int(os.environ.get("JELLYFIN_UPSTREAM_PORT", "8096"))
LISTEN_PORT = int(os.environ.get("JELLYFIN_PROFILE_PROXY_PORT", "8080"))

HOP_BY_HOP_HEADERS = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
}

VIDEO_CODEC_KEYS = {"VideoCodec", "videoCodec"}
CODEC_KEYS = {"Codec", "codec"}


def split_codecs(value):
    if not isinstance(value, str):
        return value
    return [part.strip() for part in value.split(",") if part.strip()]


def without_hevc(value):
    codecs = split_codecs(value)
    if not isinstance(codecs, list):
        return value, False
    filtered = [
        codec for codec in codecs
        if codec.lower() not in {"hevc", "h265", "h.265"}
    ]
    if filtered == codecs:
        return value, False
    return ",".join(filtered), True


def normalize_profile(value):
    changed = False

    if isinstance(value, list):
        normalized = []
        for item in value:
            normalized_item, item_changed, drop = normalize_profile_item(item)
            changed = changed or item_changed or drop
            if not drop:
                normalized.append(normalized_item)
        return normalized, changed

    if isinstance(value, dict):
        normalized = {}
        for key, item in value.items():
            if key in VIDEO_CODEC_KEYS:
                updated, item_changed = without_hevc(item)
                if updated == "":
                    changed = True
                    continue
                normalized[key] = updated
                changed = changed or item_changed
                continue

            updated, item_changed = normalize_profile(item)
            normalized[key] = updated
            changed = changed or item_changed
        return normalized, changed

    return value, False


def normalize_profile_item(item):
    if not isinstance(item, dict):
        normalized, changed = normalize_profile(item)
        return normalized, changed, False

    codec_value = next((item[key] for key in CODEC_KEYS if key in item), None)
    video_codec_value = next((item[key] for key in VIDEO_CODEC_KEYS if key in item), None)
    type_value = str(item.get("Type", item.get("type", ""))).lower()

    codec_only_hevc = isinstance(codec_value, str) and codec_value.lower() in {"hevc", "h265", "h.265"}
    video_codecs = split_codecs(video_codec_value)
    video_only_hevc = isinstance(video_codecs, list) and video_codecs and all(
        codec.lower() in {"hevc", "h265", "h.265"} for codec in video_codecs
    )
    is_video_profile = "video" in type_value or video_codec_value is not None

    if is_video_profile and (codec_only_hevc or video_only_hevc):
        return item, True, True

    normalized, changed = normalize_profile(item)
    return normalized, changed, False


def maybe_rewrite_playback_info(path, headers, body):
    if "/PlaybackInfo" not in path:
        return body, False
    content_type = headers.get("content-type", "")
    if "json" not in content_type.lower() and body.strip()[:1] not in {b"{", b"["}:
        return body, False
    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:
        return body, False

    normalized, changed = normalize_profile(payload)
    if not changed:
        return body, False

    return json.dumps(normalized, separators=(",", ":")).encode("utf-8"), True


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.proxy()

    def do_POST(self):
        self.proxy()

    def do_PUT(self):
        self.proxy()

    def do_DELETE(self):
        self.proxy()

    def do_PATCH(self):
        self.proxy()

    def do_OPTIONS(self):
        self.proxy()

    def proxy(self):
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"ok")
            return

        length = int(self.headers.get("content-length", "0") or "0")
        body = self.rfile.read(length) if length else b""
        original_host = self.headers.get("Host")
        headers = {
            key: value for key, value in self.headers.items()
            if key.lower() not in HOP_BY_HOP_HEADERS and key.lower() != "host"
        }
        headers["Host"] = original_host or f"{UPSTREAM_HOST}:{UPSTREAM_PORT}"
        headers["Accept-Encoding"] = "identity"

        rewritten_body, changed = maybe_rewrite_playback_info(
            urlsplit(self.path).path,
            {key.lower(): value for key, value in headers.items()},
            body,
        )
        if changed:
            body = rewritten_body
            headers["Content-Length"] = str(len(body))
            print(f"[jellyfin-profile-proxy] normalized HEVC from PlaybackInfo request: {self.path}", flush=True)
        elif body:
            headers["Content-Length"] = str(len(body))

        conn = http.client.HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT, timeout=120)
        try:
            conn.request(self.command, self.path, body=body if body else None, headers=headers)
            response = conn.getresponse()
            response_body = response.read()
        except Exception as exc:
            message = f"upstream proxy error: {exc}".encode("utf-8")
            self.send_response(502)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(message)))
            self.end_headers()
            self.wfile.write(message)
            return
        finally:
            conn.close()

        self.send_response(response.status, response.reason)
        for key, value in response.getheaders():
            lowered = key.lower()
            if lowered in HOP_BY_HOP_HEADERS or lowered in {"content-length", "content-encoding"}:
                continue
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(response_body)))
        self.end_headers()
        self.wfile.write(response_body)

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("", LISTEN_PORT), ProxyHandler)
    print(
        f"[jellyfin-profile-proxy] listening on :{LISTEN_PORT}, upstream={UPSTREAM_HOST}:{UPSTREAM_PORT}",
        flush=True,
    )
    server.serve_forever()
