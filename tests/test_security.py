"""server/security.py — лимит запросов, Origin/CSRF, размер запроса, заголовки безопасности."""
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.requests import Request

from server import security


# ───────────── RateLimiter (с управляемым временем) ─────────────
class Clock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t


@pytest.fixture
def clock(monkeypatch):
    c = Clock()
    monkeypatch.setattr(security, "time", SimpleNamespace(monotonic=c))
    return c


def test_limiter_allows_up_to_limit_then_blocks(clock):
    rl = security.RateLimiter()
    assert [rl.check("k", 3, 10) for _ in range(3)] == [0, 0, 0]
    clock.t = 1003
    assert rl.check("k", 3, 10) == 8              # первое событие было в 1000 → освободится в 1010


def test_limiter_rejected_attempts_do_not_extend_the_block(clock):
    rl = security.RateLimiter()
    for _ in range(3):
        rl.check("k", 3, 10)
    for t in (1001, 1005, 1009.9):
        clock.t = t
        assert rl.check("k", 3, 10) > 0
    clock.t = 1010
    assert rl.check("k", 3, 10) == 0


def test_limiter_sliding_window(clock):
    rl = security.RateLimiter()
    rl.check("k", 2, 10)                          # t=1000
    clock.t = 1006
    rl.check("k", 2, 10)                          # t=1006
    clock.t = 1008
    assert rl.check("k", 2, 10) > 0
    clock.t = 1010.5                              # первое событие выпало из окна, второе — нет
    assert rl.check("k", 2, 10) == 0
    assert rl.check("k", 2, 10) > 0


def test_limiter_retry_after_is_at_least_one(clock):
    rl = security.RateLimiter()
    rl.check("k", 1, 10)
    clock.t = 1009.99
    assert rl.check("k", 1, 10) >= 1


def test_limiter_keys_are_independent(clock):
    rl = security.RateLimiter()
    assert rl.check("a", 1, 10) == 0
    assert rl.check("a", 1, 10) > 0
    assert rl.check("b", 1, 10) == 0


def test_limiter_failure_counter(clock):
    rl = security.RateLimiter()
    for _ in range(3):
        rl.fail("login|1.2.3.4")
    assert rl.failures("login|1.2.3.4", 300) == 3
    assert rl.failures("login|9.9.9.9", 300) == 0
    clock.t += 301
    assert rl.failures("login|1.2.3.4", 300) == 0


def test_limiter_reset_clears_key(clock):
    rl = security.RateLimiter()
    rl.fail("k")
    rl.reset("k")
    assert rl.failures("k", 300) == 0
    rl.reset("never-seen")                        # не падает на неизвестном ключе


def test_limiter_sweeps_stale_keys(clock):
    rl = security.RateLimiter()
    rl.check("old", 5, 10)
    clock.t = 5000                                # прошло больше часа
    rl.check("fresh", 5, 10)
    assert "old" not in rl._hits and "fresh" in rl._hits


def test_limiter_does_not_sweep_more_than_once_a_minute(clock):
    rl = security.RateLimiter()
    rl.check("old", 5, 10)
    clock.t = 1030                                # меньше минуты с создания
    rl.check("fresh", 5, 10)
    assert "old" in rl._hits


# ───────────── client_ip / origin_ok ─────────────
def make_request(headers=None, client=("1.2.3.4", 5000)):
    scope = {"type": "http", "headers": [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()]}
    if client is not None:
        scope["client"] = client
    return Request(scope)


def test_client_ip():
    assert security.client_ip(make_request()) == "1.2.3.4"
    assert security.client_ip(make_request(client=None)) == "unknown"


def test_client_ip_ignores_forwarded_header():
    # X-Forwarded-For подставляет сам клиент; доверять ему можно только через uvicorn --proxy-headers
    assert security.client_ip(make_request({"X-Forwarded-For": "6.6.6.6"})) == "1.2.3.4"


@pytest.mark.parametrize("headers, expected", [
    ({"host": "site.test"}, True),                                              # ни Origin, ни Referer
    ({"host": "site.test", "origin": "https://site.test"}, True),
    ({"host": "site.test:8000", "origin": "http://site.test:8000"}, True),
    ({"host": "site.test", "origin": "https://evil.example"}, False),
    ({"host": "site.test", "origin": "https://site.test.evil.example"}, False),
    ({"host": "site.test", "origin": "null"}, False),
    ({"host": "site.test", "referer": "https://site.test/admin/page"}, True),
    ({"host": "site.test", "referer": "https://evil.example/x"}, False),
    ({"host": "site.test", "origin": "https://site.test", "referer": "https://evil.example/"}, True),   # Origin важнее
    ({"host": "site.test", "origin": "https://evil.example", "referer": "https://site.test/"}, False),
])
def test_origin_ok(headers, expected):
    assert security.origin_ok(make_request(headers)) is expected


# ───────────── middleware на настоящем приложении ─────────────
@pytest.fixture
def app(monkeypatch):
    monkeypatch.setattr(security, "ENABLED", True)
    monkeypatch.setattr(security, "limiter", security.RateLimiter())
    monkeypatch.setattr(security, "MAX_UPLOAD_BYTES", 1000)
    app = FastAPI()
    app.middleware("http")(security.guard)

    ok = lambda: {"ok": True}
    for path in ("/", "/api/ping", "/api/chapters/1", "/api/auth/poll/abc", "/api/chat/x", "/admin/page", "/static/x.css"):
        app.get(path)(ok)
    for path in ("/admin/login", "/admin/settings", "/admin/upload", "/api/upload", "/api/auth/start"):
        app.post(path)(ok)

    from fastapi.responses import JSONResponse

    @app.get("/custom")
    def custom():
        return JSONResponse({"ok": True}, headers={"X-Frame-Options": "SAMEORIGIN"})
    return app


@pytest.fixture
def client(app):
    return TestClient(app)


def test_security_headers_added(client):
    r = client.get("/api/ping")
    assert r.status_code == 200
    for name, value in security.SECURITY_HEADERS.items():
        assert r.headers[name] == value
    assert r.headers["x-frame-options"] == "DENY"
    assert "frame-ancestors 'none'" in r.headers["content-security-policy"]


def test_security_headers_do_not_override_app_headers(client):
    assert client.get("/custom").headers["x-frame-options"] == "SAMEORIGIN"


@pytest.mark.parametrize("path", ["/admin/page", "/api/auth/poll/abc", "/api/chat/x"])
def test_no_store_for_private_paths(client, path):
    assert client.get(path).headers["cache-control"] == "no-store"


@pytest.mark.parametrize("path", ["/api/ping", "/", "/static/x.css"])
def test_cache_control_untouched_for_public_paths(client, path):
    assert "cache-control" not in client.get(path).headers


# ── лимиты ──
def test_admin_settings_limit(client):
    for _ in range(10):
        assert client.post("/admin/settings").status_code == 200
    r = client.post("/admin/settings")
    assert r.status_code == 429
    assert r.json() == {"detail": "Слишком много запросов"}
    assert 1 <= int(r.headers["retry-after"]) <= 31


def test_limits_are_per_rule(client):
    for _ in range(11):
        client.post("/admin/settings")
    assert client.post("/admin/settings").status_code == 429
    assert client.post("/admin/login").status_code == 200       # у логина свой счётчик
    assert client.get("/api/ping").status_code == 200


def test_limits_are_per_ip(app):
    a = TestClient(app, client=("10.0.0.1", 1111))
    b = TestClient(app, client=("10.0.0.2", 2222))
    for _ in range(10):
        a.post("/admin/settings")
    assert a.post("/admin/settings").status_code == 429
    assert b.post("/admin/settings").status_code == 200


def test_login_limit(client):
    assert all(client.post("/admin/login").status_code == 200 for _ in range(20))
    assert client.post("/admin/login").status_code == 429


def test_chapters_limit_is_separate_from_generic_api(client):
    assert all(client.get("/api/chapters/1").status_code == 200 for _ in range(60))
    assert client.get("/api/chapters/1").status_code == 429
    assert client.get("/api/ping").status_code == 200


def test_static_is_not_limited(client):
    assert all(client.get("/static/x.css").status_code == 200 for _ in range(400))


def test_limit_applies_to_matching_method_only(client):
    # правило для POST /admin/settings не касается GET
    for _ in range(11):
        client.post("/admin/settings")
    assert client.get("/admin/page").status_code == 200


def test_rate_limit_can_be_disabled(client, monkeypatch):
    monkeypatch.setattr(security, "ENABLED", False)
    assert all(client.post("/admin/settings").status_code == 200 for _ in range(30))


# ── Origin / Referer ──
def test_admin_post_from_foreign_origin_is_rejected(client):
    r = client.post("/admin/login", headers={"Origin": "https://evil.example"})
    assert r.status_code == 403


def test_admin_post_from_own_origin_passes(client):
    assert client.post("/admin/login", headers={"Origin": "http://testserver"}).status_code == 200


def test_admin_post_foreign_referer_rejected_own_referer_passes(client):
    assert client.post("/admin/login", headers={"Referer": "https://evil.example/x"}).status_code == 403
    assert client.post("/admin/login", headers={"Referer": "http://testserver/admin/"}).status_code == 200


def test_origin_is_checked_only_for_admin_paths(client):
    assert client.post("/api/upload", headers={"Origin": "https://evil.example"}).status_code == 200


def test_rejected_cross_site_posts_do_not_use_up_the_limit(client):
    for _ in range(30):
        assert client.post("/admin/login", headers={"Origin": "https://evil.example"}).status_code == 403
    assert client.post("/admin/login").status_code == 200


# ── размер запроса ──
def test_oversized_post_rejected(client):
    r = client.post("/api/upload", content=b"x" * 1001)
    assert r.status_code == 413
    assert r.json() == {"detail": "Слишком большой запрос"}


def test_post_at_limit_passes(client):
    assert client.post("/api/upload", content=b"x" * 1000).status_code == 200


def test_oversized_admin_post_rejected(client):
    assert client.post("/admin/upload", content=b"x" * 5000).status_code == 413


def test_size_limit_is_not_applied_to_get(client):
    assert client.get("/api/ping", headers={"Content-Length": "999999"}).status_code == 200


def test_garbage_content_length_does_not_crash(client):
    r = client.post("/api/upload", content=b"x", headers={"Content-Length": "abc"})
    assert r.status_code in (200, 400)
