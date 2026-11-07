"""server/push.py — FCM push: JWT, кэш токена, отправка в тему, фоновые задачи.

Сеть не используется (httpx.MockTransport). Тесты JWT требуют пакет cryptography.
"""
import asyncio
import base64
import json
import time
from urllib.parse import parse_qs

import httpx
import pytest

from server import push

TOKEN_URI = "https://oauth.test/token"


def run(coro):
    return asyncio.run(coro)


@pytest.fixture(autouse=True)
def clean_token_cache():
    push._token_cache.update(value=None, exp=0.0)
    yield
    push._token_cache.update(value=None, exp=0.0)


@pytest.fixture(scope="module")
def rsa_key():
    pytest.importorskip("cryptography")
    from cryptography.hazmat.primitives.asymmetric import rsa
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def account(rsa_key):
    from cryptography.hazmat.primitives import serialization
    pem = rsa_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption()).decode()
    return {"project_id": "proj-1", "client_email": "bot@proj-1.iam.gserviceaccount.com",
            "private_key": pem, "token_uri": TOKEN_URI}


@pytest.fixture
def account_file(tmp_path, monkeypatch, account):
    path = tmp_path / "sa.json"
    path.write_text(json.dumps(account), encoding="utf-8")
    monkeypatch.setattr(push, "FCM_SERVICE_ACCOUNT", str(path))
    return path


def make_client(log, *, token_status=200, fcm_status=200):
    def handler(request: httpx.Request):
        log.append(request)
        if str(request.url) == TOKEN_URI:
            if token_status >= 400:
                return httpx.Response(token_status, json={"error": "invalid_grant"})
            return httpx.Response(200, json={"access_token": "tok-1", "expires_in": 3600})
        if request.url.host == "fcm.googleapis.com":
            return httpx.Response(fcm_status, json={"name": "ok"} if fcm_status < 400 else {"error": "bad"})
        return httpx.Response(404)
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


# ───────────── enabled / _b64 ─────────────
def test_enabled_requires_existing_file(monkeypatch, tmp_path):
    monkeypatch.setattr(push, "FCM_SERVICE_ACCOUNT", "")
    assert push.enabled() is False
    monkeypatch.setattr(push, "FCM_SERVICE_ACCOUNT", str(tmp_path / "missing.json"))
    assert push.enabled() is False
    f = tmp_path / "ok.json"
    f.write_text("{}")
    monkeypatch.setattr(push, "FCM_SERVICE_ACCOUNT", str(f))
    assert push.enabled() is True


def test_b64_is_urlsafe_without_padding():
    assert push._b64(b"\xff\xfe\xfd\xfc") == "__79_A"
    assert "=" not in push._b64(b"a")
    assert b64d(push._b64(b"hello world!")) == b"hello world!"


# ───────────── JWT ─────────────
def test_signed_jwt_structure_and_signature(account, rsa_key):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    token = push._signed_jwt(account, 1_700_000_000)
    head, claims, sig = token.split(".")
    assert json.loads(b64d(head)) == {"alg": "RS256", "typ": "JWT"}
    c = json.loads(b64d(claims))
    assert c == {"iss": account["client_email"], "scope": push.SCOPE, "aud": TOKEN_URI,
                 "iat": 1_700_000_000, "exp": 1_700_000_000 + 3600}
    # подпись проверяется открытым ключом — бросит исключение, если не совпадёт
    rsa_key.public_key().verify(b64d(sig), f"{head}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256())


def test_signed_jwt_default_audience(account):
    del account["token_uri"]
    claims = json.loads(b64d(push._signed_jwt(account, 1).split(".")[1]))
    assert claims["aud"] == push.DEFAULT_TOKEN_URI


# ───────────── access token ─────────────
def test_access_token_requests_and_caches(account):
    log = []
    client = make_client(log)
    assert run(push._access_token(client, account)) == "tok-1"
    form = parse_qs(log[0].content.decode())
    assert form["grant_type"] == ["urn:ietf:params:oauth:grant-type:jwt-bearer"]
    assert form["assertion"][0].count(".") == 2
    assert run(push._access_token(client, account)) == "tok-1"
    assert len(log) == 1                                   # второй раз — из кэша


def test_access_token_cache_used_while_valid(account):
    log = []
    push._token_cache.update(value="cached", exp=time.time() + 3600)
    assert run(push._access_token(make_client(log), account)) == "cached"
    assert log == []


def test_access_token_refreshed_shortly_before_expiry(account):
    log = []
    push._token_cache.update(value="old", exp=time.time() + 30)     # меньше минуты запаса
    assert run(push._access_token(make_client(log), account)) == "tok-1"
    assert len(log) == 1


def test_access_token_http_error(account):
    with pytest.raises(httpx.HTTPStatusError):
        run(push._access_token(make_client([], token_status=400), account))
    assert push._token_cache["value"] is None


# ───────────── send_to_topic ─────────────
def test_send_disabled_makes_no_requests(monkeypatch):
    monkeypatch.setattr(push, "FCM_SERVICE_ACCOUNT", "")
    log = []
    assert run(push.send_to_topic({"a": 1}, client=make_client(log))) is False
    assert log == []


def test_send_success_request_shape(account_file):
    log = []
    ok = run(push.send_to_topic({"chapter_id": 7, "flag": True, "title": "Гл"}, client=make_client(log)))
    assert ok is True
    fcm = log[-1]
    assert str(fcm.url) == "https://fcm.googleapis.com/v1/projects/proj-1/messages:send"
    assert fcm.headers["authorization"] == "Bearer tok-1"
    msg = json.loads(fcm.content)["message"]
    assert msg["topic"] == push.TOPIC
    assert msg["data"] == {"chapter_id": "7", "flag": "True", "title": "Гл"}     # FCM принимает только строки
    assert msg["android"] == {"priority": "HIGH"}


def test_send_reuses_cached_token(account_file):
    log = []
    client = make_client(log)
    run(push.send_to_topic({"a": 1}, client=client))
    run(push.send_to_topic({"a": 2}, client=client))
    assert sum(str(r.url) == TOKEN_URI for r in log) == 1


def test_send_fcm_error_returns_false_and_drops_token(account_file, capsys):
    ok = run(push.send_to_topic({"a": 1}, client=make_client([], fcm_status=500)))
    assert ok is False
    assert push._token_cache["value"] is None
    assert "FCM" in capsys.readouterr().out


def test_send_token_error_does_not_raise(account_file, capsys):
    assert run(push.send_to_topic({"a": 1}, client=make_client([], token_status=401))) is False
    assert "не удалось отправить" in capsys.readouterr().out


def test_send_broken_key_does_not_raise(account_file, account):
    account["private_key"] = "not a key"
    account_file.write_text(json.dumps(account))
    assert run(push.send_to_topic({"a": 1}, client=make_client([]))) is False


def test_send_broken_account_file_does_not_raise(account_file):
    account_file.write_text("{ это не json")
    assert run(push.send_to_topic({"a": 1}, client=make_client([]))) is False


def test_send_network_error_does_not_raise(account_file):
    def handler(request):
        raise httpx.ConnectError("down")
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    assert run(push.send_to_topic({"a": 1}, client=client)) is False


def test_send_closes_own_client_but_not_passed_one(account_file, monkeypatch):
    own = make_client([])
    monkeypatch.setattr(push.net, "make_client", lambda timeout: (own, None))
    assert run(push.send_to_topic({"a": 1})) is True
    assert own.is_closed
    passed = make_client([])
    assert run(push.send_to_topic({"a": 1}, client=passed)) is True
    assert not passed.is_closed


def test_send_closes_own_client_on_failure(account_file, monkeypatch):
    own = make_client([], fcm_status=500)
    monkeypatch.setattr(push.net, "make_client", lambda timeout: (own, None))
    assert run(push.send_to_topic({"a": 1})) is False
    assert own.is_closed


# ───────────── notify_new_chapter / fire_and_forget ─────────────
def test_notify_new_chapter_payload(monkeypatch):
    sent = {}

    async def fake_send(data, *, client=None):
        sent.update(data)
        return True
    monkeypatch.setattr(push, "send_to_topic", fake_send)
    ok = run(push.notify_new_chapter(chapter_id=5, kind="ranobe", volume=1, number=0, title="Пролог",
                                     created=123, chapter_type="prologue"))
    assert ok is True
    assert sent == {"type": "chapter", "chapter_id": 5, "kind": "ranobe", "volume": 1, "number": 0,
                    "title": "Пролог", "created": 123, "chapter_type": "prologue"}


def test_notify_new_chapter_default_type(monkeypatch):
    sent = {}

    async def fake_send(data, *, client=None):
        sent.update(data)
        return True
    monkeypatch.setattr(push, "send_to_topic", fake_send)
    run(push.notify_new_chapter(chapter_id=1, kind="manga", volume=1, number=1, title="t", created=1))
    assert sent["chapter_type"] == "chapter" and sent["type"] == "chapter"


def test_fire_and_forget_runs_task_and_releases_reference():
    async def main():
        done = []

        async def job():
            done.append(1)
        push.fire_and_forget(job())
        assert len(push._background) == 1                  # пока идёт — ссылка хранится
        for _ in range(5):
            await asyncio.sleep(0)
        assert done == [1]
        assert not push._background                        # завершилась — ссылка убрана
    run(main())
