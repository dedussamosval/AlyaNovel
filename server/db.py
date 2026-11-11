import hashlib
import secrets
import hmac
import os
import sqlite3
import sys
import time
import getpass
from contextlib import contextmanager
from common import formats

DB_PATH = os.environ.get("DB_PATH", "alyanovel.db")
# Папка с файлами глав (.mtx/.mpx). В базе хранится только имя файла.
STORAGE_DIR = os.environ.get("STORAGE_DIR", "storage/chapters")
# Сколько дней живёт сессия пользователя в чате (продлевается при использовании не чаще раза в сутки)
SESSION_TTL = int(float(os.environ.get("SESSION_TTL_DAYS", "90")) * 86400)

SCHEMA = """
CREATE TABLE IF NOT EXISTS volumes(
  id INTEGER PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('manga','ranobe')),
  number INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', UNIQUE(kind, number));
CREATE TABLE IF NOT EXISTS chapters(
  id INTEGER PRIMARY KEY, volume_id INTEGER NOT NULL REFERENCES volumes(id) ON DELETE CASCADE,
  type TEXT NOT NULL DEFAULT 'chapter' CHECK(type IN ('chapter','prologue','epilogue','extra')),
  number INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL DEFAULT '', fmt TEXT NOT NULL,
  path TEXT NOT NULL, size INTEGER NOT NULL, created INTEGER NOT NULL,
  UNIQUE(volume_id, type, number));
CREATE TABLE IF NOT EXISTS admins(id INTEGER PRIMARY KEY, login TEXT UNIQUE NOT NULL, pwhash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS users(tg_id INTEGER PRIMARY KEY, username TEXT, name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, tg_id INTEGER NOT NULL, created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS auth_requests(token TEXT PRIMARY KEY, tg_id INTEGER, created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS messages(
  id INTEGER PRIMARY KEY, tg_id INTEGER NOT NULL, name TEXT NOT NULL,
  text TEXT NOT NULL, created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS notices(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL CHECK(kind IN ('changelog','important')),
  version TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',
  created INTEGER NOT NULL, push_ok INTEGER NOT NULL DEFAULT 0);
"""


@contextmanager
def conn():
    c = sqlite3.connect(DB_PATH)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys=ON")
    try:
        yield c
        c.commit()
    finally:
        c.close()


def init():
    os.makedirs(STORAGE_DIR, exist_ok=True)
    with conn() as c:
        c.execute("PRAGMA journal_mode=WAL")
        c.executescript(SCHEMA)
        _migrate_blobs_to_files(c)
        _migrate_chapter_types(c)
        _migrate_publish_at(c)
        _migrate_session_hashes(c)
        c.execute("DELETE FROM sessions WHERE created<?", (now() - SESSION_TTL,))
    _harden_permissions()


# Порядок глав внутри тома: пролог → главы → эпилог → экстра. Пригодится в любом SELECT: ORDER BY {ORDER_SQL}
ORDER_SQL = "CASE type WHEN 'prologue' THEN 0 WHEN 'chapter' THEN 1 WHEN 'epilogue' THEN 2 ELSE 3 END, number"
# Для отложенного публикования. Без алиасов — работает и в "FROM chapters", и в "chapters c JOIN volumes v"
VISIBLE_SQL = "(publish_at IS NULL OR publish_at <= CAST(strftime('%s','now') AS INTEGER))"


def _migrate_chapter_types(c):
    """Старые базы: UNIQUE(volume_id, number) → UNIQUE(volume_id, type, number) + колонка type.
    SQLite не умеет менять UNIQUE на месте, поэтому таблица пересоздаётся (данные и id сохраняются)."""
    cols = {r["name"] for r in c.execute("PRAGMA table_info(chapters)")}
    if "type" in cols:
        return
    c.execute("ALTER TABLE chapters RENAME TO chapters_old")
    c.execute("""CREATE TABLE chapters(
      id INTEGER PRIMARY KEY, volume_id INTEGER NOT NULL REFERENCES volumes(id) ON DELETE CASCADE,
      type TEXT NOT NULL DEFAULT 'chapter' CHECK(type IN ('chapter','prologue','epilogue','extra')),
      number INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL DEFAULT '', fmt TEXT NOT NULL,
      path TEXT NOT NULL, size INTEGER NOT NULL, created INTEGER NOT NULL,
      UNIQUE(volume_id, type, number))""")
    c.execute("INSERT INTO chapters(id, volume_id, type, number, title, fmt, path, size, created) "
              "SELECT id, volume_id, 'chapter', number, title, fmt, path, size, created FROM chapters_old")
    c.execute("DROP TABLE chapters_old")


def _migrate_blobs_to_files(c):
    """Старые базы хранили файл в колонке data — выносим на диск один раз."""
    cols = {r["name"] for r in c.execute("PRAGMA table_info(chapters)")}
    if "data" not in cols:
        return
    if "path" not in cols:
        c.execute("ALTER TABLE chapters ADD COLUMN path TEXT NOT NULL DEFAULT ''")
    for r in c.execute("SELECT id, fmt, data FROM chapters WHERE path=''").fetchall():
        c.execute("UPDATE chapters SET path=? WHERE id=?", (write_chapter_file(r["data"], r["fmt"]), r["id"]))
    c.execute("ALTER TABLE chapters DROP COLUMN data")

def _migrate_session_hashes(c):
    """Старые сессии хранились открытым текстом (43 символа) — один раз заменяем их хэшами (64 символа), вход не слетает."""
    for r in c.execute("SELECT token FROM sessions WHERE length(token)!=64").fetchall():
        c.execute("UPDATE sessions SET token=? WHERE token=?", (hash_token(r["token"]), r["token"]))

def _migrate_publish_at(c):
    cols = {r["name"] for r in c.execute("PRAGMA table_info(chapters)")}
    if "publish_at" not in cols:
        c.execute("ALTER TABLE chapters ADD COLUMN publish_at INTEGER")     # NULL = сразу
    if "notified" not in cols:
        c.execute("ALTER TABLE chapters ADD COLUMN notified INTEGER NOT NULL DEFAULT 1")   # 0 = пуш ещё не ушёл

def _harden_permissions() -> None:
    """База и файлы глав — только владельцу процесса (на Windows chmod почти ничего не меняет — это нормально)."""
    try:
        os.chmod(STORAGE_DIR, 0o700)
        for suffix in ("", "-wal", "-shm"):
            if os.path.exists(DB_PATH + suffix):
                os.chmod(DB_PATH + suffix, 0o600)
    except OSError:
        pass

# ───────────── сессии пользователей ─────────────
def hash_token(token: str) -> str:
    """В базе хранится только SHA-256 токена: утечка файла базы не даёт войти в чужие сессии."""
    return hashlib.sha256(token.encode()).hexdigest()


def create_session(c, tg_id: int) -> str:
    token = secrets.token_urlsafe(32)
    c.execute("INSERT INTO sessions(token, tg_id, created) VALUES(?,?,?)", (hash_token(token), tg_id, now()))
    return token


def session_user(c, token: str):
    """{tg_id, name} или None. Просроченная сессия не принимается; живая продлевается раз в сутки."""
    if not token:
        return None
    h = hash_token(token)
    row = c.execute("SELECT u.tg_id, u.name, s.created FROM sessions s JOIN users u USING(tg_id) "
                    "WHERE s.token=? AND s.created>?", (h, now() - SESSION_TTL)).fetchone()
    if not row:
        return None
    if now() - row["created"] > 86400:
        c.execute("UPDATE sessions SET created=? WHERE token=?", (now(), h))
    return {"tg_id": row["tg_id"], "name": row["name"]}


def write_chapter_file(blob: bytes, fmt: str, type: str, volume: str, chapter: str) -> str:
    """Атомарно пишет файл в STORAGE_DIR, возвращает имя (его кладём в БД)."""
    os.makedirs(STORAGE_DIR, exist_ok=True)
    name = formats.chapter_code(kind=f"{'ranobe' if fmt == 'mtx' else 'manga'}", volume=volume, chapter_type=type, number=chapter) + f".{fmt}"
    final = os.path.join(STORAGE_DIR, name)
    with open(final + ".tmp", "wb") as f:
        f.write(blob)
    os.replace(final + ".tmp", final)
    return name


def chapter_file_path(name: str) -> str:
    base = os.path.realpath(STORAGE_DIR)
    full = os.path.realpath(os.path.join(base, name))
    if os.path.dirname(full) != base:  # защита от ../
        raise ValueError("bad path")
    return full


def delete_chapter_files(names):
    for n in names:
        try:
            os.remove(chapter_file_path(n))
        except (OSError, ValueError):
            pass


def hash_pw(password: str, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(16)
    h = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 200_000)
    return salt.hex() + "$" + h.hex()


def check_pw(password: str, stored: str) -> bool:
    salt, _ = stored.split("$")
    return hmac.compare_digest(hash_pw(password, bytes.fromhex(salt)), stored)


def now() -> int:
    return int(time.time())


if __name__ == "__main__":
    # python -m server.db create-admin <login> <password>
    DB_PATH = 'server/alyanovel.db' # заглушка
    init()
    if len(sys.argv) in (3, 4) and sys.argv[1] == "create-admin":
        # пароль лучше не писать в командной строке (остаётся в истории оболочки) — тогда он будет запрошен скрыто
        password = sys.argv[3] if len(sys.argv) == 4 else getpass.getpass("Пароль администратора: ")
        if len(password) < 12:
            sys.exit("Пароль слишком короткий: нужно минимум 12 символов")
        with conn() as c:
            c.execute("INSERT OR REPLACE INTO admins(login, pwhash) VALUES(?,?)", (sys.argv[2], hash_pw(password)))
        print("OK")
    else:
        print("usage: python -m server.db create-admin <login> [<password>]")
