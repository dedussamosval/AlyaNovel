"""Защита API и сайта: ограничение частоты запросов, проверка Origin и размера запроса, заголовки безопасности.

Подключается в main.py одной строкой: app.middleware("http")(security.guard).
Лимиты считаются по IP клиента. За reverse proxy (Caddy/nginx) запускайте uvicorn с
    --proxy-headers --forwarded-allow-ips 127.0.0.1
иначе все клиенты будут выглядеть как 127.0.0.1 и делить один лимит на всех.
Счётчики хранятся в памяти процесса: для одного процесса uvicorn этого достаточно; при перезапуске сбрасываются.
"""
import os
import time
from collections import defaultdict, deque
from urllib.parse import urlparse

from starlette.requests import Request
from starlette.responses import JSONResponse, PlainTextResponse

ENABLED = os.environ.get("RATE_LIMIT", "1") != "0"          # RATE_LIMIT=0 — выключить (локальная отладка, тесты)
HTTPS_ONLY = os.environ.get("HTTPS_ONLY", "0") == "1"
MAX_UPLOAD_BYTES = int(float(os.environ.get("MAX_UPLOAD_MB", "300")) * 1024 * 1024)

# (метод, префикс пути, запросов, окно в секундах); первое совпадение; limit=None — не ограничивать
RULES = [
    ("POST", "/admin/login", 20, 300),
    ("POST", "/api/auth/start", 20, 600),
    ("GET", "/api/auth/poll/", 150, 60),
    ("GET", "/api/chapters/", 60, 60),
    ("*", "/api/", 300, 60),
    ("*", "/static/", None, 0),
    ("*", "/", 300, 60),
]

SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "same-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    # в админке есть inline-обработчики (onchange/onsubmit), поэтому script-src 'unsafe-inline'; всё остальное — только со своего домена
    "Content-Security-Policy": ("default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                "script-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"),
}
if HTTPS_ONLY:
    SECURITY_HEADERS["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"

NO_STORE_PREFIXES = ("/admin", "/api/auth/", "/api/chat")  # ответы с личными данными не кэшируем


class RateLimiter:
    """Скользящее окно: не больше `limit` событий за `window` секунд на ключ."""

    def __init__(self):
        self._hits: dict[str, deque] = defaultdict(deque)
        self._last_sweep = time.monotonic()

    def _prune(self, key: str, window: int, now: float) -> deque:
        q = self._hits[key]
        while q and q[0] <= now - window:
            q.popleft()
        return q

    def check(self, key: str, limit: int, window: int) -> int:
        """0 — можно (событие засчитано); иначе — через сколько секунд повторить."""
        now = time.monotonic()
        q = self._prune(key, window, now)
        if len(q) >= limit:
            return max(1, int(q[0] + window - now) + 1)
        q.append(now)
        self._sweep(now)
        return 0

    # учёт неудач (вход в админку): считаем отдельно от обычных лимитов
    def failures(self, key: str, window: int) -> int:
        return len(self._prune(key, window, time.monotonic()))

    def fail(self, key: str) -> None:
        self._hits[key].append(time.monotonic())

    def reset(self, key: str) -> None:
        self._hits.pop(key, None)

    def _sweep(self, now: float) -> None:
        """Раз в минуту выбрасываем давно молчащие ключи, чтобы память не росла."""
        if now - self._last_sweep < 60:
            return
        self._last_sweep = now
        for k in [k for k, q in self._hits.items() if not q or q[-1] < now - 3600]:
            del self._hits[k]


limiter = RateLimiter()


def client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def origin_ok(request: Request) -> bool:
    """POST-запросы админки должны приходить с нашего же сайта (защита от CSRF поверх SameSite=Strict)."""
    host = request.headers.get("host", "")
    for header in ("origin", "referer"):
        value = request.headers.get(header)
        if value:
            return urlparse(value).netloc == host
    return True  # ни Origin, ни Referer: SameSite=Strict у cookie всё равно защищает


async def guard(request: Request, call_next):
    method, path, ip = request.method, request.url.path, client_ip(request)

    if method in ("POST", "PUT", "PATCH"):
        length = request.headers.get("content-length", "")
        if length.isdigit() and int(length) > MAX_UPLOAD_BYTES:
            return JSONResponse({"detail": "Слишком большой запрос"}, status_code=413)
        if path.startswith("/admin") and not origin_ok(request):
            return PlainTextResponse("Запрос с чужого сайта отклонён", status_code=403)

    if ENABLED:
        for rule_method, prefix, limit, window in RULES:
            if (rule_method == "*" or rule_method == method) and path.startswith(prefix):
                if limit is not None:
                    wait = limiter.check(f"{prefix}|{ip}", limit, window)
                    if wait:
                        return JSONResponse({"detail": "Слишком много запросов"}, status_code=429,
                                            headers={"Retry-After": str(wait)})
                break

    response = await call_next(request)
    for name, value in SECURITY_HEADERS.items():
        response.headers.setdefault(name, value)
    if path.startswith(NO_STORE_PREFIXES):
        response.headers["Cache-Control"] = "no-store"
    return response