"""server/net.py — выбор и нормализация исходящего прокси."""
import asyncio

import httpx
import pytest

from server import net


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    monkeypatch.delenv("OUTBOUND_PROXY", raising=False)


@pytest.mark.parametrize("raw, expected", [
    ("http://127.0.0.1:10809", "http://127.0.0.1:10809"),
    ("127.0.0.1:10809", "http://127.0.0.1:10809"),              # без схемы
    ("  http://host:1  ", "http://host:1"),
    ("socks://127.0.0.1:10808", "socks5h://127.0.0.1:10808"),   # то, что отдаёт Windows
    ("socks4://127.0.0.1:10808", "socks5h://127.0.0.1:10808"),
    ("SOCKS4A://h:1", "socks5h://h:1"),
    ("socks5://h:1", "socks5://h:1"),
    ("socks5h://h:1", "socks5h://h:1"),
    ("HTTP://Host:8080", "http://Host:8080"),                   # схема в нижний регистр, хост как есть
    ("http://user:pass@h:1", "http://user:pass@h:1"),
])
def test_normalize(raw, expected):
    assert net._normalize(raw) == expected


@pytest.mark.parametrize("value", ["", "none", "NONE", " off ", "Off"])
def test_env_can_disable_proxy(monkeypatch, value):
    monkeypatch.setenv("OUTBOUND_PROXY", value)
    monkeypatch.setattr(net.urllib.request, "getproxies", lambda: {"https": "http://sys:1"})
    assert net.proxy_url() is None


def test_env_proxy_is_normalized_and_beats_system(monkeypatch):
    monkeypatch.setenv("OUTBOUND_PROXY", "socks://127.0.0.1:10808")
    monkeypatch.setattr(net.urllib.request, "getproxies", lambda: {"https": "http://sys:1"})
    assert net.proxy_url() == "socks5h://127.0.0.1:10808"


@pytest.mark.parametrize("found, expected", [
    ({"https": "http://a:1", "http": "http://b:2", "all": "http://c:3"}, "http://a:1"),
    ({"http": "http://b:2", "all": "http://c:3"}, "http://b:2"),
    ({"all": "socks://c:3"}, "socks5h://c:3"),
    ({"https": "127.0.0.1:10809"}, "http://127.0.0.1:10809"),
    ({}, None),
])
def test_system_proxy_fallback(monkeypatch, found, expected):
    monkeypatch.setattr(net.urllib.request, "getproxies", lambda: found)
    assert net.proxy_url() == expected


def test_make_client_without_proxy(monkeypatch):
    monkeypatch.setenv("OUTBOUND_PROXY", "none")
    client, proxy = net.make_client()
    assert proxy is None and isinstance(client, httpx.AsyncClient)
    asyncio.run(client.aclose())


def test_make_client_with_http_proxy(monkeypatch):
    monkeypatch.setenv("OUTBOUND_PROXY", "127.0.0.1:10809")
    client, proxy = net.make_client(5.0)
    assert proxy == "http://127.0.0.1:10809"
    asyncio.run(client.aclose())


def test_make_client_passes_proxy_and_ignores_environment(monkeypatch):
    seen = {}

    class Fake:
        def __init__(self, **kw):
            seen.update(kw)
    monkeypatch.setattr(net.httpx, "AsyncClient", Fake)
    monkeypatch.setenv("OUTBOUND_PROXY", "socks://127.0.0.1:10808")
    _, proxy = net.make_client(5.0)
    assert proxy == "socks5h://127.0.0.1:10808"
    assert seen == {"timeout": 5.0, "proxy": proxy, "trust_env": False}


def test_make_client_missing_socksio(monkeypatch):
    def fake(**kw):
        raise ImportError("socksio")
    monkeypatch.setattr(net.httpx, "AsyncClient", fake)
    monkeypatch.setenv("OUTBOUND_PROXY", "socks5://h:1")
    with pytest.raises(net.ProxyError, match="socksio"):
        net.make_client()


def test_make_client_bad_proxy(monkeypatch):
    def fake(**kw):
        raise ValueError("Unknown scheme")
    monkeypatch.setattr(net.httpx, "AsyncClient", fake)
    monkeypatch.setenv("OUTBOUND_PROXY", "weird://h:1")
    with pytest.raises(net.ProxyError, match="weird://h:1") as e:
        net.make_client()
    assert "OUTBOUND_PROXY=none" in str(e.value)
