"""server/images.py — иллюстрации по ссылкам: белый список хостов, SSRF-защита, редиректы, лимиты.

Сеть не используется: httpx.MockTransport подменяет ответы, DNS подменяется через socket.getaddrinfo.
"""
import asyncio
import socket

import httpx
import pytest

from common import formats
from server import images
from server.images import ImageError


# ───────────── помощники ─────────────
def run(coro):
    return asyncio.run(coro)


async def allow_all(host):          # вместо проверки DNS
    return None


def mk(table, calls=None):
    """Клиент с поддельным сервером. table: url -> функция(request) -> Response."""
    def handler(request: httpx.Request):
        url = str(request.url)
        if calls is not None:
            calls.append(url)
        fn = table.get(url)
        return fn(request) if fn else httpx.Response(404)
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def img(data, ctype="image/png"):
    return lambda req: httpx.Response(200, content=data, headers={"content-type": ctype})


def page(body):
    return lambda req: httpx.Response(200, content=body.encode(),
                                      headers={"content-type": "text/html; charset=UTF-8"})


def redirect(location, code=302):
    return lambda req: httpx.Response(code, headers={"location": location})


def embed(text, table, calls=None, check=allow_all):
    return run(images.embed_images(text, client=mk(table, calls), check=check))


@pytest.fixture(autouse=True)
def default_hosts(monkeypatch):
    # тесты не зависят от переменной окружения IMAGE_HOSTS
    monkeypatch.setattr(images, "ALLOWED_HOSTS", ["postimg.cc", "imgur.com"])


# ───────────── белый список и проверка URL ─────────────
@pytest.mark.parametrize("host", ["postimg.cc", "i.postimg.cc", "IMGUR.COM", "i.imgur.com", "postimg.cc.", "a.b.postimg.cc"])
def test_host_allowed_yes(host):
    assert images.host_allowed(host)


@pytest.mark.parametrize("host", ["evil.example", "notpostimg.cc", "postimg.cc.evil.com", "imgur.com.evil.io",
                                  "", None, "localhost"])
def test_host_allowed_no(host):
    assert not images.host_allowed(host)


def test_host_allowed_follows_configured_list(monkeypatch):
    monkeypatch.setattr(images, "ALLOWED_HOSTS", ["cdn.example.org"])
    assert images.host_allowed("cdn.example.org") and images.host_allowed("x.cdn.example.org")
    assert not images.host_allowed("postimg.cc")


@pytest.mark.parametrize("url", ["https://i.postimg.cc/x/pic.jpg", "https://postimg.cc/AbCdEfGh",
                                 "https://i.imgur.com/AbCdEfG.png", "https://imgur.com/AbCdEfG"])
def test_validate_url_ok(url):
    assert images._validate_url(url) == url


@pytest.mark.parametrize("url, msg", [
    ("http://i.postimg.cc/a.png", "https"),
    ("ftp://i.postimg.cc/a.png", "https"),
    ("https://evil.example/a.png", "не разрешён"),
    ("https://postimg.cc@evil.example/a.png", "не разрешён"),      # userinfo-трюк
    ("https://imgur.com/a/AbCd", "Альбомы"),
    ("https://imgur.com/gallery/AbCd", "Альбомы"),
    ("https://i.imgur.com/a/AbCd", "Альбомы"),
])
def test_validate_url_rejected(url, msg):
    with pytest.raises(ImageError, match=msg):
        images._validate_url(url)


def test_image_error_is_format_error():
    assert issubclass(ImageError, formats.FormatError)


# ───────────── защита от SSRF: DNS ─────────────
def fake_dns(monkeypatch, *addrs):
    def fake(host, port, *a, **kw):
        out = []
        for ip in addrs:
            fam = socket.AF_INET6 if ":" in ip else socket.AF_INET
            out.append((fam, socket.SOCK_STREAM, 6, "", (ip, port, 0, 0) if fam == socket.AF_INET6 else (ip, port)))
        return out
    monkeypatch.setattr(socket, "getaddrinfo", fake)


def test_ensure_public_host_accepts_public(monkeypatch):
    fake_dns(monkeypatch, "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")
    run(images.ensure_public_host("i.postimg.cc"))


@pytest.mark.parametrize("ip", ["127.0.0.1", "10.0.0.5", "192.168.1.10", "172.16.0.1",
                                "169.254.169.254", "0.0.0.0", "::1", "fe80::1", "fc00::1"])
def test_ensure_public_host_rejects_non_public(monkeypatch, ip):
    fake_dns(monkeypatch, ip)
    with pytest.raises(ImageError, match="непубличный"):
        run(images.ensure_public_host("i.postimg.cc"))


def test_ensure_public_host_one_private_address_is_enough_to_reject(monkeypatch):
    fake_dns(monkeypatch, "93.184.216.34", "10.0.0.1")
    with pytest.raises(ImageError, match="непубличный"):
        run(images.ensure_public_host("i.postimg.cc"))


def test_ensure_public_host_dns_failure(monkeypatch):
    def boom(*a, **kw):
        raise socket.gaierror("no such host")
    monkeypatch.setattr(socket, "getaddrinfo", boom)
    with pytest.raises(ImageError, match="Не удалось найти адрес"):
        run(images.ensure_public_host("i.postimg.cc"))


# ───────────── разбор текста ─────────────
def test_scan_plain_text_and_blank_lines_are_kept():
    assert images._scan("раз\n\nдва") == [("text", "раз"), ("text", ""), ("text", "два")]


def test_scan_crlf():
    assert images._scan("a\r\nb") == [("text", "a"), ("text", "b")]


def test_scan_markdown_image():
    assert images._scan("![Подпись](https://i.postimg.cc/x.png)") == [("img", "https://i.postimg.cc/x.png", "Подпись")]


def test_scan_bare_allowed_url_becomes_image():
    assert images._scan("  https://postimg.cc/AbCd  ") == [("img", "https://postimg.cc/AbCd", "")]


def test_scan_bare_foreign_url_stays_text():
    assert images._scan("https://example.com/page") == [("text", "https://example.com/page")]


def test_scan_url_inside_sentence_is_not_an_image():
    line = "смотри https://postimg.cc/AbCd тут"
    assert images._scan(line) == [("text", line)]


def test_scan_image_in_the_middle_of_a_line():
    assert images._scan("до ![a](https://i.postimg.cc/x.png) после") == [
        ("text", "до"), ("img", "https://i.postimg.cc/x.png", "a"), ("text", "после")]


def test_scan_two_images_in_one_line():
    tokens = images._scan("![a](https://i.postimg.cc/1.png)![b](https://i.postimg.cc/2.png)")
    assert [t[0] for t in tokens] == ["img", "img"]


def test_scan_caption_is_stripped():
    assert images._scan("![  привет  ](https://i.postimg.cc/x.png)")[0][2] == "привет"


# ───────────── embed_images: основной сценарий ─────────────
URL = "https://i.postimg.cc/x/pic.jpg"


def test_embed_without_images_does_not_touch_network():
    calls = []
    assert embed("просто\nтекст", {}, calls) == ("просто\nтекст", [])
    assert calls == []


def test_embed_markdown_image(jpg):
    text, blobs = embed(f"Перед\n![Подпись]({URL})\nПосле", {URL: img(jpg, "image/jpeg")})
    assert text == "Перед\n![Подпись](img:1)\nПосле"
    assert blobs == [jpg]


def test_embed_bare_url_via_wrapper_page(png):
    table = {
        "https://postimg.cc/AbCd": page('<meta property="og:image" content="https://i.postimg.cc/AbCd/p.png">'),
        "https://i.postimg.cc/AbCd/p.png": img(png),
    }
    text, blobs = embed("https://postimg.cc/AbCd", table)
    assert text == "![](img:1)" and blobs == [png]


def test_embed_same_url_is_downloaded_once(png):
    calls = []
    text, blobs = embed(f"![a]({URL})\nтекст\n![b]({URL})", {URL: img(png)}, calls)
    assert text == "![a](img:1)\nтекст\n![b](img:1)"
    assert len(blobs) == 1 and calls == [URL]


def test_embed_numbers_follow_order_of_appearance(png, jpg):
    u1, u2 = "https://i.postimg.cc/1.png", "https://i.postimg.cc/2.jpg"
    text, blobs = embed(f"![два]({u2})\n![раз]({u1})",
                        {u1: img(png), u2: img(jpg, "image/jpeg")})
    assert text == "![два](img:1)\n![раз](img:2)"
    assert blobs == [jpg, png]


def test_embed_image_inside_sentence(png):
    text, _ = embed(f"Вот ![a]({URL}) и дальше", {URL: img(png)})
    assert text == "Вот\n![a](img:1)\nи дальше"


def test_embed_result_is_accepted_by_build_mtx(png, jpg):
    u1, u2 = "https://i.postimg.cc/1.png", "https://i.imgur.com/2.jpg"
    text, blobs = embed(f"Глава\n![первая]({u1})\nhttps://i.imgur.com/2.jpg",
                        {u1: img(png), u2: img(jpg, "image/jpeg")})
    data = formats.build_mtx(text, title="t", volume=1, chapter=1, images=blobs)
    assert formats.read_manifest(data)["images"] == ["images/0001.png", "images/0002.jpg"]
    assert formats.read_mtx_text(data) == "Глава\n![первая](img:1)\n![](img:2)"


# ───────────── embed_images: что отклоняется ─────────────
def test_embed_foreign_host_rejected_before_any_request():
    calls = []
    with pytest.raises(ImageError, match="не разрешён"):
        embed("![x](https://evil.example/a.png)", {}, calls)
    assert calls == []


def test_embed_http_rejected():
    with pytest.raises(ImageError, match="https"):
        embed("![x](http://i.postimg.cc/a.png)", {})


def test_embed_imgur_album_rejected():
    with pytest.raises(ImageError, match="Альбомы"):
        embed("https://imgur.com/a/AbCd", {})


def test_embed_too_many_images(monkeypatch):
    monkeypatch.setattr(formats, "MAX_IMAGES", 2)
    text = "\n".join(f"![](https://i.postimg.cc/{i}.png)" for i in range(3))
    with pytest.raises(ImageError, match="Не более 2"):
        embed(text, {})


def test_embed_total_size_limit(monkeypatch, png):
    monkeypatch.setattr(images, "MAX_TOTAL_BYTES", len(png) + 10)
    u1, u2 = "https://i.postimg.cc/1.png", "https://i.postimg.cc/2.png"
    with pytest.raises(ImageError, match="Суммарный размер"):
        embed(f"![]({u1})\n![]({u2})", {u1: img(png), u2: img(png)})


def test_embed_single_file_size_limit(monkeypatch, png):
    monkeypatch.setattr(images, "MAX_IMAGE_BYTES", 20)
    with pytest.raises(ImageError, match="слишком большой"):
        embed(f"![]({URL})", {URL: img(png)})


def test_html_page_size_limit(monkeypatch):
    monkeypatch.setattr(images, "MAX_HTML_BYTES", 50)
    with pytest.raises(ImageError, match="слишком большой"):
        embed("https://postimg.cc/AbCd", {"https://postimg.cc/AbCd": page("<p>" + "x" * 200 + "</p>")})


def test_embed_content_is_checked_by_signature_not_content_type():
    with pytest.raises(ImageError, match="не JPEG/PNG/WEBP"):
        embed(f"![]({URL})", {URL: img(b"GIF89a-not-allowed", "image/png")})


def test_embed_accepts_webp_even_with_wrong_content_type(webp):
    _, blobs = embed(f"![]({URL})", {URL: img(webp, "application/octet-stream")})
    assert blobs == [webp]


@pytest.mark.parametrize("status", [403, 404, 500])
def test_embed_http_error_status(status):
    with pytest.raises(ImageError, match=str(status)):
        embed(f"![]({URL})", {URL: lambda req: httpx.Response(status)})


# ───────────── страницы-обёртки (og:image / twitter:image) ─────────────
WRAP = "https://postimg.cc/AbCd"
DIRECT = "https://i.postimg.cc/AbCd/a.png"


@pytest.mark.parametrize("meta", [
    f'<meta property="og:image" content="{DIRECT}">',
    f'<meta content="{DIRECT}" property="og:image">',
    f'<meta name="twitter:image" content="{DIRECT}">',
    f"<META PROPERTY='og:image' CONTENT='{DIRECT}' />",
])
def test_wrapper_page_meta_variants(png, meta):
    _, blobs = embed(WRAP, {WRAP: page(f"<html><head>{meta}</head></html>"), DIRECT: img(png)})
    assert blobs == [png]


def test_wrapper_page_prefers_og_over_twitter(png):
    calls = []
    meta = ('<meta name="twitter:image" content="https://i.postimg.cc/tw.png">'
            f'<meta property="og:image" content="{DIRECT}">')
    embed(WRAP, {WRAP: page(meta), DIRECT: img(png), "https://i.postimg.cc/tw.png": img(png)}, calls)
    assert DIRECT in calls and "https://i.postimg.cc/tw.png" not in calls


def test_wrapper_page_ignores_og_image_subproperties():
    with pytest.raises(ImageError, match="нет картинки"):
        embed(WRAP, {WRAP: page('<meta property="og:image:width" content="800">')})


def test_wrapper_page_without_meta():
    with pytest.raises(ImageError, match="нет картинки"):
        embed(WRAP, {WRAP: page("<html>ничего</html>")})


def test_wrapper_page_relative_url_and_html_entities(png):
    final = "https://postimg.cc/img/a.png?x=1&y=2"
    table = {WRAP: page('<meta property="og:image" content="/img/a.png?x=1&amp;y=2">'), final: img(png)}
    _, blobs = embed(WRAP, table)
    assert blobs == [png]


def test_wrapper_pointing_to_another_page_is_rejected():
    other = "https://postimg.cc/other"
    table = {WRAP: page(f'<meta property="og:image" content="{other}">'), other: page("<html></html>")}
    with pytest.raises(ImageError, match="не на картинку"):
        embed(WRAP, table)


def test_wrapper_og_image_on_foreign_host_is_rejected(png):
    evil = "https://evil.example/a.png"
    table = {WRAP: page(f'<meta property="og:image" content="{evil}">'), evil: img(png)}
    calls = []
    with pytest.raises(ImageError, match="не разрешён"):
        embed(WRAP, table, calls)
    assert evil not in calls


# ───────────── редиректы ─────────────
def test_redirect_is_followed(png):
    table = {"https://postimg.cc/old": redirect("https://i.postimg.cc/new.png"),
             "https://i.postimg.cc/new.png": img(png)}
    _, blobs = embed("![](https://postimg.cc/old)", table)
    assert blobs == [png]


def test_relative_redirect_is_followed(png):
    table = {"https://i.postimg.cc/old.png": redirect("/new.png", 301),
             "https://i.postimg.cc/new.png": img(png)}
    _, blobs = embed("![](https://i.postimg.cc/old.png)", table)
    assert blobs == [png]


def test_redirect_to_foreign_host_is_rejected(png):
    evil = "https://evil.example/a.png"
    calls = []
    table = {"https://postimg.cc/old": redirect(evil), evil: img(png)}
    with pytest.raises(ImageError, match="не разрешён"):
        embed("![](https://postimg.cc/old)", table, calls)
    assert evil not in calls


def test_redirect_to_http_is_rejected():
    table = {"https://postimg.cc/old": redirect("http://i.postimg.cc/a.png")}
    with pytest.raises(ImageError, match="https"):
        embed("![](https://postimg.cc/old)", table)


def test_redirect_without_location():
    table = {"https://postimg.cc/old": lambda req: httpx.Response(302)}
    with pytest.raises(ImageError, match="Пустой редирект"):
        embed("![](https://postimg.cc/old)", table)


def test_redirect_loop_stops_after_limit():
    calls = []
    start = "https://postimg.cc/loop"
    with pytest.raises(ImageError, match="Слишком много редиректов"):
        embed(f"![]({start})", {start: redirect(start)}, calls)
    assert len(calls) == images.MAX_REDIRECTS + 1


def test_exactly_max_redirects_is_allowed(png):
    hops = [f"https://postimg.cc/h{i}" for i in range(images.MAX_REDIRECTS)] + ["https://i.postimg.cc/end.png"]
    table = {hops[i]: redirect(hops[i + 1]) for i in range(len(hops) - 1)}
    table[hops[-1]] = img(png)
    _, blobs = embed(f"![]({hops[0]})", table)
    assert blobs == [png]


def test_check_is_called_for_every_hop(png):
    seen = []

    async def check(host):
        seen.append(host)
    table = {"https://postimg.cc/old": redirect("https://i.postimg.cc/new.png"),
             "https://i.postimg.cc/new.png": img(png)}
    embed("![](https://postimg.cc/old)", table, check=check)
    assert seen == ["postimg.cc", "i.postimg.cc"]


def test_check_failure_stops_before_request(png):
    async def deny(host):
        raise ImageError("приватный адрес")
    calls = []
    with pytest.raises(ImageError, match="приватный"):
        embed(f"![]({URL})", {URL: img(png)}, calls, check=deny)
    assert calls == []


# ───────────── сетевые ошибки ─────────────
def failing_client(exc):
    def handler(request):
        raise exc
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def test_connect_error_gives_proxy_hint():
    with pytest.raises(ImageError, match="OUTBOUND_PROXY") as e:
        run(images.embed_images(f"![]({URL})", client=failing_client(httpx.ConnectError("boom")), check=allow_all))
    assert "ConnectError" in str(e.value)


def test_other_network_errors_have_no_proxy_hint():
    with pytest.raises(ImageError) as e:
        run(images.embed_images(f"![]({URL})", client=failing_client(httpx.ReadTimeout("slow")), check=allow_all))
    assert "ReadTimeout" in str(e.value) and "OUTBOUND_PROXY" not in str(e.value)


# ───────────── собственный клиент и прокси ─────────────
def patch_make_client(monkeypatch, client, proxy):
    monkeypatch.setattr(images.net, "make_client", lambda timeout: (client, proxy))


def test_own_client_is_closed_afterwards(monkeypatch, png):
    client = mk({URL: img(png)})
    patch_make_client(monkeypatch, client, None)
    fake_dns(monkeypatch, "93.184.216.34")
    _, blobs = run(images.embed_images(f"![]({URL})"))
    assert blobs == [png] and client.is_closed


def test_own_client_is_closed_on_error(monkeypatch):
    client = mk({})
    patch_make_client(monkeypatch, client, None)
    fake_dns(monkeypatch, "93.184.216.34")
    with pytest.raises(ImageError):
        run(images.embed_images(f"![]({URL})"))
    assert client.is_closed


def test_passed_client_is_not_closed(png):
    client = mk({URL: img(png)})
    run(images.embed_images(f"![]({URL})", client=client, check=allow_all))
    assert not client.is_closed


def test_without_proxy_private_dns_is_rejected(monkeypatch, png):
    patch_make_client(monkeypatch, mk({URL: img(png)}), None)
    fake_dns(monkeypatch, "127.0.0.1")
    with pytest.raises(ImageError, match="непубличный"):
        run(images.embed_images(f"![]({URL})"))


def test_with_proxy_dns_check_is_skipped_but_host_whitelist_stays(monkeypatch, png):
    # через прокси локальный DNS (fake-ip и т.п.) ничего не значит
    patch_make_client(monkeypatch, mk({URL: img(png)}), "http://127.0.0.1:10809")
    fake_dns(monkeypatch, "198.18.0.5")
    _, blobs = run(images.embed_images(f"![]({URL})"))
    assert blobs == [png]
    patch_make_client(monkeypatch, mk({}), "http://127.0.0.1:10809")
    with pytest.raises(ImageError, match="не разрешён"):
        run(images.embed_images("![](https://evil.example/a.png)"))


def test_proxy_error_becomes_image_error(monkeypatch):
    def boom(timeout):
        raise images.net.ProxyError("нужен socksio")
    monkeypatch.setattr(images.net, "make_client", boom)
    with pytest.raises(ImageError, match="socksio"):
        run(images.embed_images(f"![]({URL})"))
