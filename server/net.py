"""Исходящие соединения сервера (скачивание картинок, Telegram-бот) и прокси.

Если на машине включён системный прокси (v2rayN, Clash и т.п. в режиме «системный прокси»),
httpx подхватывал его сам. На Windows Python отдаёт системный SOCKS-прокси как «socks4://…»,
а httpx такой схемы не знает → ValueError: Unknown scheme for proxy URL. Поэтому прокси
выбираем и нормализуем сами и передаём клиенту явно (trust_env=False).

Порядок выбора:
  1. OUTBOUND_PROXY, если задана:  http://127.0.0.1:10809 | socks5://127.0.0.1:10808 | none (без прокси)
  2. иначе системный/переменные окружения (HTTPS_PROXY / HTTP_PROXY / ALL_PROXY / настройки Windows)
  3. иначе без прокси.
socks / socks4 / socks4a приводятся к socks5h (DNS разрешает прокси). Для SOCKS нужен пакет socksio
(pip install "httpx[socks]"); HTTP-прокси работает без него (в v2rayN HTTP-порт обычно 10809).
"""
import os
import urllib.request

import httpx


class ProxyError(Exception):
    pass


def _normalize(url: str) -> str:
    url = url.strip()
    if "://" not in url:
        url = "http://" + url                       # «127.0.0.1:10809» без схемы
    scheme, _, rest = url.partition("://")
    if scheme.lower() in ("socks", "socks4", "socks4a"):
        scheme = "socks5h"
    return f"{scheme.lower()}://{rest}"


def proxy_url() -> str | None:
    env = os.environ.get("OUTBOUND_PROXY")
    if env is not None:
        return None if env.strip().lower() in ("", "none", "off") else _normalize(env)
    found = urllib.request.getproxies()             # переменные окружения + реестр Windows
    p = found.get("https") or found.get("http") or found.get("all")
    return _normalize(p) if p else None


def make_client(timeout: httpx.Timeout | float = 15.0):
    """Возвращает (AsyncClient, proxy_url | None)."""
    proxy = proxy_url()
    try:
        return httpx.AsyncClient(timeout=timeout, proxy=proxy, trust_env=False), proxy
    except ImportError:
        raise ProxyError('Для SOCKS-прокси нужен пакет socksio: pip install "httpx[socks]" '
                         "— или укажите HTTP-прокси, например OUTBOUND_PROXY=http://127.0.0.1:10809")
    except ValueError as e:
        raise ProxyError(f"Не удалось применить прокси {proxy}: {e}. Задайте OUTBOUND_PROXY "
                         "(http://… или socks5://…) либо OUTBOUND_PROXY=none")
