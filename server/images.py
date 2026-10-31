"""Иллюстрации в ранобэ по ссылкам (postimg.cc и т.п.).

Админ вставляет в текст главы:
    ![подпись](https://i.postimg.cc/xxxx/pic.jpg)      — картинка с подписью
    https://postimg.cc/AbCdEfGh                         — просто ссылка отдельной строкой
    https://i.imgur.com/AbCdEfG.png  или  https://imgur.com/AbCdEfG
Принимаются и прямые ссылки на файл, и страницы одной картинки (адрес файла берётся из og:image /
twitter:image). Хосты — из белого списка IMAGE_HOSTS (по умолчанию postimg.cc и imgur.com).
Альбомы и галереи imgur (imgur.com/a/…, /gallery/…) не поддерживаются: вставьте прямые ссылки на картинки.

При сохранении главы сервер САМ скачивает картинки и кладёт их внутрь .mtx, а в тексте оставляет
строки вида  ![подпись](img:N). Поэтому у читателей картинки работают офлайн, не зависят от живости
хостинга и не раскрывают их IP стороннему сайту.

Безопасность (сервер ходит по ссылкам, которые вводит человек, — это классический SSRF-риск):
  • только https и только хосты из белого списка IMAGE_HOSTS (домен и его поддомены);
  • каждый редирект проверяется заново (не больше 3);
  • адрес должен резолвиться в публичный IP (не localhost / локальная сеть / link-local);
  • лимиты: размер файла, число картинок, суммарный объём, таймаут;
  • содержимое проверяется по сигнатуре: JPEG, PNG или WEBP (расширению и Content-Type не верим).
"""
import asyncio
import html
import ipaddress
import os
import re
from urllib.parse import urljoin, urlparse

import httpx

from common import formats
from server import net

ALLOWED_HOSTS = [h.strip().lower() for h in os.environ.get("IMAGE_HOSTS", "postimg.cc,imgur.com").split(",") if h.strip()]
MAX_IMAGE_BYTES = int(float(os.environ.get("IMAGE_MAX_MB", "8")) * 1024 * 1024)
MAX_TOTAL_BYTES = int(float(os.environ.get("IMAGE_TOTAL_MAX_MB", "60")) * 1024 * 1024)
MAX_HTML_BYTES = 512 * 1024
MAX_REDIRECTS = 3
TIMEOUT = httpx.Timeout(15.0)
HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; AlyaNovelBot/1.0)", "Accept": "image/*,text/html;q=0.8"}

MD_IMG = re.compile(r"!\[([^\]\n]*)\]\(\s*(https?://[^\s)]+)\s*\)")
BARE_URL = re.compile(r"^\s*(https?://\S+)\s*$")
# адрес картинки на странице-обёртке: og:image, а если его нет — twitter:image (в любом порядке атрибутов)
_META_KEYS = ("og:image", "twitter:image")
OG_IMAGE = [
    re.compile(r'<meta[^>]+(?:property|name)=["\']' + key + r'["\'][^>]*content=["\']([^"\']+)["\']', re.I)
    for key in _META_KEYS
] + [
    re.compile(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]*(?:property|name)=["\']' + key + r'["\']', re.I)
    for key in _META_KEYS
]


class ImageError(formats.FormatError):
    pass


def host_allowed(host: str) -> bool:
    host = (host or "").lower().rstrip(".")
    return any(host == d or host.endswith("." + d) for d in ALLOWED_HOSTS)


def _validate_url(url: str) -> str:
    p = urlparse(url)
    if p.scheme != "https":
        raise ImageError(f"Допустимы только https-ссылки: {url}")
    if not host_allowed(p.hostname):
        raise ImageError(f"Хост {p.hostname} не разрешён (разрешены: {', '.join(ALLOWED_HOSTS)})")
    host = (p.hostname or "").lower()
    if (host == "imgur.com" or host.endswith(".imgur.com")) and p.path.startswith(("/a/", "/gallery/")):
        raise ImageError(f"Альбомы и галереи imgur не поддерживаются — вставьте прямые ссылки на картинки "
                         f"(i.imgur.com/…): {url}")
    return url


async def ensure_public_host(host: str):
    """Хост должен резолвиться только в публичные адреса."""
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(host, 443, type=0)
    except OSError:
        raise ImageError(f"Не удалось найти адрес {host}")
    for info in infos:
        if not ipaddress.ip_address(info[4][0]).is_global:
            raise ImageError(f"Хост {host} указывает на непубличный адрес")


async def _fetch(client, url: str, check, *, limit_img: int):
    """Скачивает url (с ручной обработкой редиректов). Возвращает (content_type, bytes, final_url)."""
    for _ in range(MAX_REDIRECTS + 1):
        _validate_url(url)
        await check(urlparse(url).hostname)
        try:
            async with client.stream("GET", url, headers=HEADERS, follow_redirects=False) as r:
                if r.status_code in (301, 302, 303, 307, 308):
                    loc = r.headers.get("location")
                    if not loc:
                        raise ImageError(f"Пустой редирект: {url}")
                    url = urljoin(url, loc)
                    continue
                if r.status_code >= 400:
                    raise ImageError(f"Сервер ответил {r.status_code}: {url}")
                ctype = r.headers.get("content-type", "").split(";")[0].strip().lower()
                limit = MAX_HTML_BYTES if ctype == "text/html" else limit_img
                data = bytearray()
                async for chunk in r.aiter_bytes():
                    data += chunk
                    if len(data) > limit:
                        mb = limit / 1024 / 1024
                        raise ImageError(f"Файл слишком большой (лимит {mb:.0f} МБ): {url}")
                return ctype, bytes(data), url
        except httpx.HTTPError as e:  # нет сети, прокси не отвечает, таймаут и т.п. — понятная ошибка, а не 500
            hint = " (проверьте прокси: переменная OUTBOUND_PROXY)" if isinstance(e, (httpx.ProxyError, httpx.ConnectError, httpx.ConnectTimeout)) else ""
            raise ImageError(f"Не удалось скачать ({type(e).__name__}){hint}: {url}")
    raise ImageError(f"Слишком много редиректов: {url}")


async def download_image(client, url: str, check=ensure_public_host) -> bytes:
    ctype, data, final = await _fetch(client, url, check, limit_img=MAX_IMAGE_BYTES)
    if ctype == "text/html":  # страница-обёртка (postimg.cc/xxxx) → берём og:image
        page = data.decode("utf-8", "ignore")
        direct = next((m.group(1) for rx in OG_IMAGE if (m := rx.search(page))), None)
        if not direct:
            raise ImageError(f"На странице нет картинки: {url}")
        ctype, data, final = await _fetch(client, urljoin(final, html.unescape(direct)), check,
                                          limit_img=MAX_IMAGE_BYTES)
        if ctype == "text/html":
            raise ImageError(f"Ссылка ведёт не на картинку: {url}")
    try:
        formats._sniff_ext(data)
    except formats.FormatError:
        raise ImageError(f"Это не JPEG/PNG/WEBP: {url}")
    return data


def _scan(text: str):
    """Разбирает текст в строки-токены: ("text", s) | ("img", url, caption)."""
    out = []
    for line in text.replace("\r\n", "\n").split("\n"):
        bare = BARE_URL.match(line)
        if bare and host_allowed(urlparse(bare.group(1)).hostname):
            out.append(("img", bare.group(1), ""))
            continue
        pos = 0
        for m in MD_IMG.finditer(line):
            if line[pos:m.start()].strip():
                out.append(("text", line[pos:m.start()].strip()))
            out.append(("img", m.group(2), m.group(1).strip()))
            pos = m.end()
        tail = line[pos:]
        if pos == 0 or tail.strip():
            out.append(("text", tail if pos == 0 else tail.strip()))
    return out


async def embed_images(text: str, *, client: httpx.AsyncClient | None = None, check=ensure_public_host):
    """Возвращает (новый_текст, [байты картинок]). Без картинок в тексте — (text, [])."""
    tokens = _scan(text)
    urls = list(dict.fromkeys(t[1] for t in tokens if t[0] == "img"))  # уникальные, по порядку
    if not urls:
        return text, []
    if len(urls) > formats.MAX_IMAGES:
        raise ImageError(f"Не более {formats.MAX_IMAGES} иллюстраций в главе")
    own = client is None
    if own:
        try:
            client, proxy = net.make_client(TIMEOUT)
        except net.ProxyError as e:
            raise ImageError(str(e))
        if proxy:  # соединяется прокси, локальный DNS-ответ не показателен (fake-ip и т.п.); белый список хостов остаётся
            async def check(host): return None
    sem = asyncio.Semaphore(4)

    async def one(u):
        async with sem:
            return await download_image(client, u, check)
    try:
        blobs = await asyncio.gather(*(one(u) for u in urls))
    finally:
        if own:
            await client.aclose()
    if sum(map(len, blobs)) > MAX_TOTAL_BYTES:
        raise ImageError(f"Суммарный размер картинок больше {MAX_TOTAL_BYTES // 1024 // 1024} МБ")
    index = {u: i for i, u in enumerate(urls, 1)}
    lines = [t[1] if t[0] == "text" else f"![{t[2].replace(']', ')')}](img:{index[t[1]]})" for t in tokens]
    return "\n".join(lines), list(blobs)
