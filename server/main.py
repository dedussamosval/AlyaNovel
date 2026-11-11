import asyncio
import os
import re
import secrets
import sys
from datetime import datetime
from zoneinfo import ZoneInfo
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote
from dotenv import load_dotenv
load_dotenv()

import httpx
from sqlite3 import IntegrityError
from fastapi import (Depends, FastAPI, File, Form, Header, HTTPException, Request,
                     Response, UploadFile, WebSocket, WebSocketDisconnect)
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.exception_handlers import http_exception_handler as DHEH   # default http exception handler
from starlette.middleware.sessions import SessionMiddleware
from starlette.exceptions import HTTPException as StarletteHTTPException

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from common import formats  # noqa: E402
from server import db  # noqa: E402
from server import images as imgmod  # noqa: E402
from server import net  # noqa: E402
from server import push  # noqa: E402
from server import security # noqa: E402


BOT_TOKEN = os.environ.get("BOT_TOKEN", "")
BOT_USERNAME = os.environ.get("BOT_USERNAME", "your_bot")
SECRET_KEY = os.environ.get("SECRET_KEY", "change-me")
APP_VERSION = os.environ.get("APP_LATEST_VERSION", "0.1.0")
APP_DOWNLOAD_URL = os.environ.get("APP_DOWNLOAD_URL", "")
APK_DIR = Path(os.environ["APK_PATH"]).expanduser() if os.environ.get("APK_PATH") else None # путь к APK на сервере — тогда /download отдаёт его сам
PUBLIC_URL = os.environ.get("PUBLIC_URL", "").rstrip("/")  # https://домен — для ссылки на APK в проверке обновлений
MTX_PART_BYTES = int(os.environ.get("MTX_PART_KB", "32")) * 1024  # вес одной части текста в .mtx
AUTH_TTL = 600
PUBLISH_TZ = ZoneInfo(os.environ.get("PUBLISH_TZ", "Europe/Moscow"))

NOTICE_VERSION_RE = re.compile(r"^\d+(\.\d+){0,3}(-[A-Za-z0-9.]+)?$")   # 0.2.14 / 0.2.14-beta

templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))


# ───────────── Telegram-бот (long polling, без доп. зависимостей) ─────────────
async def tg_call(client: httpx.AsyncClient, method: str, **params):
    r = await client.post(f"https://api.telegram.org/bot{BOT_TOKEN}/{method}", json=params, timeout=65)
    return r.json()


async def run_bot():
    if not BOT_TOKEN:
        print("BOT_TOKEN не задан — вход через Telegram отключён")
        return
    offset = 0
    try:
        client, _ = net.make_client(httpx.Timeout(65.0))
    except net.ProxyError as e:
        print("Бот не запущен:", e)
        return
    async with client:
        while True:
            try:
                res = await tg_call(client, "getUpdates", offset=offset, timeout=50)
                for upd in res.get("result", []):
                    offset = upd["update_id"] + 1
                    msg = upd.get("message") or {}
                    text = msg.get("text", "")
                    if not text.startswith("/start "):
                        if text.startswith("/start"):
                            await tg_call(client, "sendMessage", chat_id=msg["chat"]["id"],
                                          text="Откройте приложение и нажмите «Войти через Telegram».")
                        continue
                    token = text.split(maxsplit=1)[1].strip()
                    u = msg["from"]
                    ok = confirm_auth(token, u["id"], u.get("username"),
                                      " ".join(filter(None, [u.get("first_name"), u.get("last_name")])))
                    await tg_call(client, "sendMessage", chat_id=msg["chat"]["id"],
                                  text="✅ Готово, вернитесь в приложение." if ok
                                  else "Ссылка устарела. Повторите вход в приложении.")
            except Exception as e:  # сеть моргнула — не падаем
                print("bot error:", e)
                await asyncio.sleep(5)


def confirm_auth(token: str, tg_id: int, username: str | None, name: str) -> bool:
    with db.conn() as c:
        row = c.execute("SELECT * FROM auth_requests WHERE token=? AND tg_id IS NULL AND created>?",
                        (token, db.now() - AUTH_TTL)).fetchone()
        if not row:
            return False
        c.execute("UPDATE auth_requests SET tg_id=? WHERE token=?", (tg_id, token))
        c.execute("INSERT INTO users(tg_id, username, name) VALUES(?,?,?) "
                  "ON CONFLICT(tg_id) DO UPDATE SET username=excluded.username, name=excluded.name",
                  (tg_id, username, name or username or str(tg_id)))
    return True

def read_app_version() -> str | None:
    if APK_DIR is None:
        return None
    try:
        version = (APK_DIR / "version.txt").read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError):
        return None

    return version

def parse_publish_at(value: str) -> int | None:
    """'2026-11-10T18:00' из datetime-local → unix. Пусто / в прошлом / мусор → None (публикуем сразу)."""
    if not value:
        return None
    try:
        ts = int(datetime.fromisoformat(value).replace(tzinfo=PUBLISH_TZ).timestamp())
    except ValueError:
        return None
    return ts if ts > db.now() else None

async def publish_scheduler():
    """Раз в 30 с: наступило время отложенной главы → один раз шлём пуш."""
    while True:
        try:
            with db.conn() as c:
                due = c.execute(
                    "SELECT c.id, c.type, c.number, c.title, c.created, v.kind, v.number AS volume "
                    "FROM chapters c JOIN volumes v ON v.id=c.volume_id "
                    "WHERE c.notified=0 AND c.publish_at<=?", (db.now(),)).fetchall()
            for r in due:
                with db.conn() as c:   # «захват»: даже при двух процессах пуш уйдёт один раз
                    claimed = c.execute("UPDATE chapters SET notified=1 WHERE id=? AND notified=0",
                                        (r["id"],)).rowcount
                if claimed and push.enabled():
                    await push.notify_new_chapter(chapter_id=r["id"], kind=r["kind"], volume=r["volume"],
                                                  number=r["number"], title=r["title"], created=r["created"],
                                                  chapter_type=r["type"])
        except Exception as e:
            print("scheduler error:", e)
        await asyncio.sleep(30)


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init()
    tasks = [asyncio.create_task(run_bot()), asyncio.create_task(publish_scheduler())]
    yield
    for task in tasks:
        task.cancel()


app = FastAPI(title="AlyaNovel", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=str(Path(__file__).parent / "static")), name="static")
app.add_middleware(SessionMiddleware, secret_key=SECRET_KEY, same_site="strict",
                   https_only=os.environ.get("HTTPS_ONLY", "0") == "1", max_age=8 * 3600)
app.middleware("http")(security.guard)


# ───────────── Публичный API ─────────────
@app.get("/api/{kind}/volumes")
def list_volumes(kind: str):
    if kind not in ("manga", "ranobe"):
        raise HTTPException(404)
    with db.conn() as c:
        vols = c.execute("SELECT * FROM volumes WHERE kind=? ORDER BY number", (kind,)).fetchall()
        out = []
        for v in vols:
            chs = c.execute("SELECT id, type, number, title, fmt, size, created FROM chapters "
            f"WHERE volume_id=? AND {db.VISIBLE_SQL} ORDER BY {db.ORDER_SQL}", (v["id"],)).fetchall()
            if chs:  # пустые тома клиенту не показываем
                out.append({"id": v["id"], "number": v["number"], "title": v["title"],
                            "chapters": [dict(ch) for ch in chs]})
    return out


@app.get("/api/chapters/{chapter_id}/file")
def chapter_file(chapter_id: int):
    with db.conn() as c:
        row = c.execute("SELECT c.fmt, c.path, c.type, c.number, v.kind, v.number AS volume "
                "FROM chapters c JOIN volumes v ON v.id=c.volume_id "
                f"WHERE c.id=? AND {db.VISIBLE_SQL}", (chapter_id,)).fetchone()
    if not row:
        raise HTTPException(404)
    try:
        full = db.chapter_file_path(row["path"])
    except ValueError:
        raise HTTPException(404)
    if not os.path.exists(full):
        raise HTTPException(404, "Файл главы не найден на диске")
    code = formats.chapter_code(row["kind"], row["volume"], row["type"], row["number"])  # R01-P, R01-C03, R01-A02…
    return FileResponse(full, media_type="application/octet-stream", filename=f"{code}.{row['fmt']}")


@app.get("/api/app-version")
def app_version():
    release = apk_file()
    latest = read_app_version() or APP_VERSION
    url = f"{PUBLIC_URL}/download" if release and PUBLIC_URL else ""
    return {"latest": latest, "url": url}


@app.get("/api/latest")
def latest(since: int = 0):
    """Для уведомлений о новых главах."""
    with db.conn() as c:
        rows = c.execute("SELECT c.id, c.type, c.number, c.title, c.created, v.kind, v.number AS volume "
                 "FROM chapters c JOIN volumes v ON v.id=c.volume_id "
                 f"WHERE c.created>? AND {db.VISIBLE_SQL} ORDER BY c.created", (since,)).fetchall()
    return [dict(r) for r in rows]

@app.get("/api/notices")
def api_notices(after: int = 0, limit: int = 5):
    limit = max(1, min(limit, 50))
    with db.conn() as c:
        rows = c.execute("SELECT id, kind, version, title, body, created FROM notices "
                         "WHERE id>? ORDER BY id DESC LIMIT ?", (after, limit)).fetchall()
        top = c.execute("SELECT COALESCE(MAX(id), 0) FROM notices").fetchone()[0]
    return {"top": top,
            "url": f"{PUBLIC_URL}/download" if apk_file() and PUBLIC_URL else "",
            "items": [dict(r) for r in reversed(rows)]}



# ───────────── Вход через Telegram ─────────────
@app.post("/api/auth/start")
def auth_start():
    token = secrets.token_urlsafe(24)
    with db.conn() as c:
        c.execute("DELETE FROM auth_requests WHERE created<?", (db.now() - AUTH_TTL,))
        c.execute("INSERT INTO auth_requests(token, created) VALUES(?,?)", (token, db.now()))
    return {"token": token, "url": f"https://t.me/{BOT_USERNAME}?start={token}"}


@app.get("/api/auth/poll/{token}")
def auth_poll(token: str):
    with db.conn() as c:
        row = c.execute("SELECT tg_id FROM auth_requests WHERE token=? AND created>?",
                        (token, db.now() - AUTH_TTL)).fetchone()
        if not row:
            raise HTTPException(410, "expired")
        if row["tg_id"] is None:
            return {"status": "pending"}
        c.execute("DELETE FROM auth_requests WHERE token=?", (token,))
        session = db.create_session(c, row["tg_id"])
        user = c.execute("SELECT name FROM users WHERE tg_id=?", (row["tg_id"],)).fetchone()
    return {"status": "ok", "session": session, "name": user["name"]}


def current_user(authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    with db.conn() as c:
        user = db.session_user(c, token)
    if not user:
        raise HTTPException(401)
    return user


# ───────────── Чат ─────────────
class Hub:
    def __init__(self):
        self.clients: set[WebSocket] = set()

    async def broadcast(self, payload: dict):
        for ws in list(self.clients):
            try:
                await ws.send_json(payload)
            except Exception:
                self.clients.discard(ws)


hub = Hub()


@app.get("/api/chat/messages")
def chat_history(limit: int = 50, user=Depends(current_user)):
    with db.conn() as c:
        rows = c.execute("SELECT id, name, text, created FROM messages ORDER BY id DESC LIMIT ?",
                         (min(limit, 200),)).fetchall()
    return [dict(r) for r in reversed(rows)]


@app.websocket("/ws/chat")
async def chat_ws(ws: WebSocket, token: str = ""):
    user = None
    with db.conn() as c:
        user = db.session_user(c, token)
    if not user:
        await ws.close(code=4401)
        return

    await ws.accept()
    hub.clients.add(ws)
    try:
        while True:
            text = (await ws.receive_text()).strip()[:2000]
            if not text:
                continue

            with db.conn() as c:
                cur = c.execute(
                    "INSERT INTO messages(tg_id, name, text, created) VALUES(?,?,?,?)",
                    (user["tg_id"], user["name"], text, db.now()),
                )
            await hub.broadcast({
                "id": cur.lastrowid,
                "name": user["name"],
                "text": text,
                "created": db.now(),
            })
    except WebSocketDisconnect:
        pass
    finally:
        hub.clients.discard(ws)


# ───────────── Сайт администратора ─────────────
def need_admin(request: Request):
    if not request.session.get("admin"):
        raise HTTPException(303, headers={"Location": "/admin/login"})


@app.get("/admin/login", response_class=HTMLResponse)
def login_page(request: Request):
    if request.session.get("admin"):
        return RedirectResponse("/admin", status_code=303)
    return templates.TemplateResponse(request, "login.html", {"error": None})


@app.post("/admin/login")
def login(request: Request, login: str = Form(), password: str = Form()):
    with db.conn() as c:
        row = c.execute("SELECT pwhash FROM admins WHERE login=?", (login,)).fetchone()
    if not row or not db.check_pw(password, row["pwhash"]):
        return templates.TemplateResponse(request, "login.html", {"error": "Неверный логин или пароль"}, 401)
    request.session["admin"] = login
    return RedirectResponse("/admin", 303)


@app.post("/admin/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/admin/login", 303)


@app.get("/admin", response_class=HTMLResponse, dependencies=[Depends(need_admin)])
def admin_home(request: Request, msg: str = ""):
    with db.conn() as c:
        vols = [dict(v) for v in c.execute("SELECT * FROM volumes ORDER BY kind, number")]
        for v in vols:
            v["chapters"] = [dict(r) for r in c.execute(
                f"SELECT id, type, number, title, fmt, size, publish_at FROM chapters WHERE volume_id=? ORDER BY {db.ORDER_SQL}", (v["id"],))]
            for r in v["chapters"]:
                r["code"] = formats.chapter_code(v["kind"], v["number"], r["type"], r["number"])
                r["scheduled"] = bool(r["publish_at"] and r["publish_at"] > db.now())
                r["when"] = (datetime.fromtimestamp(r["publish_at"], PUBLISH_TZ).strftime("%d.%m %H:%M")
                    if r["scheduled"] else "")

        notices = [dict(r) for r in c.execute(
        "SELECT id, kind, version, title, created, push_ok FROM notices ORDER BY id DESC LIMIT 20")]
        for n in notices:
            n["when"] = datetime.fromtimestamp(n["created"], PUBLISH_TZ).strftime("%d.%m.%Y %H:%M")
    return templates.TemplateResponse(request, "admin.html", {"vols": vols, "msg": msg, "publish_tz": PUBLISH_TZ.key, 
                                                              "server_now": datetime.now(PUBLISH_TZ).strftime("%Y-%m-%dT%H:%M"), 
                                                              "notices": notices, "app_version": read_app_version() or APP_VERSION})

@app.get("/admin/settings", response_class=HTMLResponse,
         dependencies=[Depends(need_admin)])
def admin_settings(request: Request):
    return templates.TemplateResponse(request, "settings.html", {"error": None})


@app.post("/admin/settings", response_class=HTMLResponse, dependencies=[Depends(need_admin)])
def admin_settings_update(request: Request, current_password: str = Form(), new_login: str = Form(""), new_password: str = Form(""), new_password_confirm: str = Form("")):
    current_login = request.session.get("admin")
    new_login = new_login.strip()

    with db.conn() as c:
        admin = c.execute(
            "SELECT id, login, pwhash FROM admins WHERE login=?",
            (current_login,),
        ).fetchone()

        if not admin or not db.check_pw(current_password, admin["pwhash"]):
            error = "Текущий пароль указан неверно."
            return templates.TemplateResponse(
                request, "settings.html", {"error": error}, status_code=400
            )

        if new_login and len(new_login) > 64:
            return templates.TemplateResponse(
                request, "settings.html",
                {"error": "Логин не должен превышать 64 символа."},
                status_code=400,
            )

        if new_password and len(new_password) < 12:
            return templates.TemplateResponse(
                request, "settings.html",
                {"error": "Новый пароль должен содержать не менее 12 символов."},
                status_code=400,
            )

        if new_password != new_password_confirm:
            return templates.TemplateResponse(
                request, "settings.html",
                {"error": "Новые пароли не совпадают."},
                status_code=400,
            )

        updated_login = new_login or current_login
        updated_hash = (
            db.hash_pw(new_password)
            if new_password
            else admin["pwhash"]
        )

        if updated_login == current_login and not new_password:
            return templates.TemplateResponse(
                request, "settings.html",
                {"error": "Нет изменений для сохранения."},
                status_code=400,
            )

        try:
            c.execute(
                "UPDATE admins SET login=?, pwhash=? WHERE id=?",
                (updated_login, updated_hash, admin["id"]),
            )
        except IntegrityError:
            return templates.TemplateResponse(
                request, "settings.html",
                {"error": "Такой логин уже занят."},
                status_code=400,
            )

    # Чтобы текущий администратор остался в системе после смены логина.
    request.session["admin"] = updated_login
    return RedirectResponse("/admin?msg=Настройки+сохранены", status_code=303)


@app.post("/admin/volumes", dependencies=[Depends(need_admin)])
def volume_create(kind: str = Form(), number: int = Form(), title: str = Form("")):
    with db.conn() as c:
        c.execute("INSERT OR IGNORE INTO volumes(kind, number, title) VALUES(?,?,?)", (kind, number, title))
    return RedirectResponse("/admin", 303)


@app.post("/admin/volumes/{vid}/delete", dependencies=[Depends(need_admin)])
def volume_delete(vid: int):
    with db.conn() as c:
        paths = [r["path"] for r in c.execute("SELECT path FROM chapters WHERE volume_id=?", (vid,))]
        c.execute("DELETE FROM volumes WHERE id=?", (vid,))
    db.delete_chapter_files(paths)
    return RedirectResponse("/admin?msg=Том+удалён", 303)


@app.post("/admin/chapters", dependencies=[Depends(need_admin)])
async def chapter_create(volume_id: int = Form(), number: str = Form(""), chapter_type: str = Form("chapter"),
                         title: str = Form(""), text: str = Form(""), direction: str = Form("rtl"),
                         publish_at: str = Form(""), images: list[UploadFile] = File(default=[])):
    ts = parse_publish_at(publish_at)   
    with db.conn() as c:
        vol = c.execute("SELECT * FROM volumes WHERE id=?", (volume_id,)).fetchone()
    if not vol:
        raise HTTPException(404, "Нет такого тома")
    try:
        # у пролога и эпилога номера нет (0), у глав и экстра — от 1; проверяем до скачивания картинок
        number = formats.normalize_number(chapter_type, number)
        if vol["kind"] == "ranobe":
            # ссылки на картинки (postimg.cc и др.) скачиваются сюда и упаковываются в .mtx
            text, pics = await imgmod.embed_images(text)
            blob = formats.build_mtx(text, title=title, volume=vol["number"], chapter=number,
                                     max_part_bytes=MTX_PART_BYTES, images=pics, chapter_type=chapter_type)
            fmt = "mtx"
        else:
            files = [await f.read() for f in images if f.filename]
            blob = formats.build_mpx(files, title=title, volume=vol["number"], chapter=number,
                                     direction=direction, chapter_type=chapter_type)
            fmt = "mpx"
    except formats.FormatError as e:
        return RedirectResponse(f"/admin?msg={quote(str(e)[:300])}", 303)
    new_path = db.write_chapter_file(blob, fmt, type=chapter_type, volume=vol["number"], chapter=number)
    with db.conn() as c:
        old = c.execute("SELECT path FROM chapters WHERE volume_id=? AND type=? AND number=?",
                        (volume_id, chapter_type, number)).fetchone()
        # created при замене не меняем (правка опечатки это не «новая глава»), кроме переноса ещё не вышедшей главы (для уведомлений)
        c.execute(
            "INSERT INTO chapters(volume_id, type, number, title, fmt, path, size, created, publish_at, notified) "
            "VALUES(?,?,?,?,?,?,?,?,?,?) "
            "ON CONFLICT(volume_id, type, number) DO UPDATE SET title=excluded.title, fmt=excluded.fmt, "
            "path=excluded.path, size=excluded.size, "
            "created=CASE WHEN excluded.publish_at IS NOT NULL AND notified=0 THEN excluded.publish_at ELSE created END, "
            "publish_at=COALESCE(excluded.publish_at, publish_at)",
            (volume_id, chapter_type, number, title, fmt, new_path, len(blob),
             ts or db.now(), ts, 0 if (ts and not old) else 1))
        saved = c.execute("SELECT id, created FROM chapters WHERE volume_id=? AND type=? AND number=?",
                          (volume_id, chapter_type, number)).fetchone()
    if old:  # главу заменили — старый файл больше не нужен
        db.delete_chapter_files([old["path"]])
    elif push.enabled() and not ts:  # новая глава и без отложенной публикации — мгновенное уведомление всем подписанным устройствам (в фоне)
        push.fire_and_forget(push.notify_new_chapter(
            chapter_id=saved["id"], kind=vol["kind"], volume=vol["number"], number=number,
            title=title, created=saved["created"], chapter_type=chapter_type))
    return RedirectResponse("/admin?msg=Глава+сохранена", 303)


@app.post("/admin/chapters/{cid}/delete", dependencies=[Depends(need_admin)])
def chapter_delete(cid: int):
    with db.conn() as c:
        row = c.execute("SELECT path FROM chapters WHERE id=?", (cid,)).fetchone()
        c.execute("DELETE FROM chapters WHERE id=?", (cid,))
    if row:
        db.delete_chapter_files([row["path"]])
    return RedirectResponse("/admin?msg=Глава+удалена", 303)

@app.post("/admin/chapters/{cid}/publish-now", dependencies=[Depends(need_admin)])
def chapter_publish_now(cid: int):
    t = db.now()
    with db.conn() as c:
        c.execute("UPDATE chapters SET publish_at=?, created=? WHERE id=? AND publish_at>?", (t, t, cid, t))
    return RedirectResponse("/admin?msg=Опубликовано", 303)

@app.post("/admin/notices", dependencies=[Depends(need_admin)])
async def notice_send(kind: str = Form(), version: str = Form(""), title: str = Form(""), body: str = Form("")):
    kind, version, title, body = kind.strip(), version.strip(), title.strip()[:100], body.strip()[:4000]

    def back(msg: str):
        return RedirectResponse(f"/admin?msg={quote(msg)}", 303)

    if kind == "changelog":
        if not NOTICE_VERSION_RE.match(version):
            return back("Укажите версию в формате 0.2.14 или 0.2.14-beta")
        if not body:
            return back("Добавьте описание обновления")
        title = "Доступна новая версия"
    elif kind == "important":
        if not title:
            return back("Укажите заголовок")
        version = ""
    else:
        raise HTTPException(400)

    with db.conn() as c:
        nid = c.execute("INSERT INTO notices(kind, version, title, body, created) VALUES(?,?,?,?,?)",
                        (kind, version, title, body, db.now())).lastrowid
    if not push.enabled():
        return back("Сохранено. Push отключён (нет FCM_SERVICE_ACCOUNT) — увидят при запуске приложения")
    if await push.notify_notice(notice_id=nid, kind=kind, version=version, title=title, body=body):
        with db.conn() as c:
            c.execute("UPDATE notices SET push_ok=1 WHERE id=?", (nid,))
        return back("Уведомление отправлено")
    return back("Сохранено, но push не ушёл (см. лог сервера). Увидят при запуске приложения")


@app.post("/admin/notices/{nid}/delete", dependencies=[Depends(need_admin)])
def notice_delete(nid: int):
    with db.conn() as c:
        c.execute("DELETE FROM notices WHERE id=?", (nid,))
    return RedirectResponse("/admin?msg=Объявление+удалено", 303)


# ───────────── Публичные страницы ─────────────
LEGAL_DIR = Path(__file__).parent / "legal"
LEGAL = {"tos": "Пользовательское соглашение", "privacy": "Политика конфиденциальности"}


def apk_file() -> tuple[str, Path] | None:
    version = read_app_version()
    if APK_DIR is None or version is None:
        return None

    apk = APK_DIR / f"alyanovel-v.{version}.apk"
    return (version, apk) if apk.is_file() else None


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    return templates.TemplateResponse(request, "index.html",
                                      {"has_download": apk_file() is not None})


@app.get("/download")
def download():
    release = apk_file()
    if release:
        _, apk = release
        return FileResponse(
            apk,
            media_type="application/vnd.android.package-archive",
            filename=apk.name,
        )
    raise HTTPException(404, "Файл приложения пока не загружен")


def legal_page(request: Request, key: str):
    import markdown  # импорт здесь: нужен только этим страницам
    text = (LEGAL_DIR / f"{key}.md").read_text(encoding="utf-8")
    html = markdown.markdown(text, extensions=["sane_lists"])
    return templates.TemplateResponse(request, "legal.html", {"title": LEGAL[key], "content": html})


@app.get("/tos", response_class=HTMLResponse)
def tos(request: Request):
    return legal_page(request, "tos")


@app.get("/privacy", response_class=HTMLResponse)
def privacy(request: Request):
    return legal_page(request, "privacy")


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    if exc.status_code == 404:
        return templates.TemplateResponse(
            request=request,
            name="notfound.html",
            # context={"request": request},
            status_code=404,
        )
    return await DHEH(request, exc)