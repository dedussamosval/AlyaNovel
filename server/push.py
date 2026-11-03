"""Мгновенные push-уведомления о новых главах через Firebase Cloud Messaging (HTTP v1).

Как это работает: приложение подписывается на «тему» (topic) FCM, а при публикации новой главы сервер
отправляет в эту тему ОДНО сообщение — Google доставляет его всем подписанным устройствам сразу.
Базу токенов хранить не нужно.

Настройка (один раз):
  1. console.firebase.google.com → создать проект → добавить Android-приложение с пакетом org.dedussamosval.alya →
     скачать google-services.json и положить в alyanovel-android/app/.
  2. Project settings → Service accounts → Generate new private key → JSON-файл положить на сервер (СЕКРЕТ, не в Git).
  3. export FCM_SERVICE_ACCOUNT=/путь/к/ключу.json      (и, если нужно, OUTBOUND_PROXY — Google API идёт через net.py)
  4. pip install cryptography
  5. Проверка: python -m server.push      — на телефоны с приложением придёт тестовое уведомление.

Без FCM_SERVICE_ACCOUNT модуль ничего не делает, а приложение продолжает проверять новые главы по расписанию (WorkManager).

Сообщение — data-only с высоким приоритетом: уведомление рисует само приложение (так работает переключатель
«Новые главы» в настройках и не нужен отдельный код для разных состояний приложения).
"""
import asyncio
import base64
import json
import os
import sys
import time
from pathlib import Path
from dotenv import load_dotenv
load_dotenv()

import httpx
from server import net


FCM_SERVICE_ACCOUNT = os.environ.get("FCM_SERVICE_ACCOUNT", "")
TOPIC = os.environ.get("FCM_TOPIC", "new_chapters")  # то же имя, что Push.TOPIC в приложении
SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"

_token_cache: dict = {"value": None, "exp": 0.0}


def enabled() -> bool:
    return bool(FCM_SERVICE_ACCOUNT) and Path(FCM_SERVICE_ACCOUNT).is_file()


def _load_account() -> dict:
    return json.loads(Path(FCM_SERVICE_ACCOUNT).read_text(encoding="utf-8"))


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _signed_jwt(account: dict, now: int) -> str:
    """JWT RS256 для обмена на access token (OAuth 2.0 JWT bearer, RFC 7523)."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    header = {"alg": "RS256", "typ": "JWT"}
    claims = {"iss": account["client_email"], "scope": SCOPE,
              "aud": account.get("token_uri", DEFAULT_TOKEN_URI), "iat": now, "exp": now + 3600}
    signing_input = _b64(json.dumps(header).encode()) + "." + _b64(json.dumps(claims).encode())
    key = serialization.load_pem_private_key(account["private_key"].encode(), password=None)
    signature = key.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
    return signing_input + "." + _b64(signature)


async def _access_token(client: httpx.AsyncClient, account: dict) -> str:
    now = int(time.time())
    if _token_cache["value"] and _token_cache["exp"] - 60 > now:
        return _token_cache["value"]
    r = await client.post(account.get("token_uri", DEFAULT_TOKEN_URI), data={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": _signed_jwt(account, now),
    })
    r.raise_for_status()
    data = r.json()
    _token_cache.update(value=data["access_token"], exp=now + int(data.get("expires_in", 3600)))
    return _token_cache["value"]


async def send_to_topic(data: dict, *, client: httpx.AsyncClient | None = None) -> bool:
    """Отправляет data-сообщение всем подписчикам темы. Не бросает исключений: сбой push не должен ломать загрузку главы."""
    if not enabled():
        return False
    own = client is None
    try:
        account = _load_account()
        if own:
            client, _ = net.make_client(httpx.Timeout(20.0))
        token = await _access_token(client, account)
        url = f"https://fcm.googleapis.com/v1/projects/{account['project_id']}/messages:send"
        body = {"message": {"topic": TOPIC, "data": {k: str(v) for k, v in data.items()},
                            "android": {"priority": "HIGH"}}}
        r = await client.post(url, json=body, headers={"Authorization": f"Bearer {token}"})
        if r.status_code >= 400:
            _token_cache.update(value=None, exp=0.0)
            print(f"FCM: ошибка {r.status_code}: {r.text[:300]}")
            return False
        return True
    except Exception as e:  # сеть, прокси, битый ключ — только в лог
        print(f"FCM: не удалось отправить push ({type(e).__name__}): {e}")
        return False
    finally:
        if own and client is not None:
            await client.aclose()


async def notify_new_chapter(*, chapter_id: int, kind: str, volume: int, number: int, title: str, created: int,
                             chapter_type: str = "chapter", client: httpx.AsyncClient | None = None) -> bool:
    # type=chapter — тип СООБЩЕНИЯ для приложения; тип самой главы (пролог/эпилог/экстра) — chapter_type
    return await send_to_topic({"type": "chapter", "chapter_id": chapter_id, "kind": kind, "volume": volume,
                                "number": number, "title": title, "created": created,
                                "chapter_type": chapter_type}, client=client)


_background: set = set()


def fire_and_forget(coro) -> None:
    """Запускает отправку в фоне (ответ админке не ждёт Google); ссылка хранится, чтобы задачу не собрал GC."""
    task = asyncio.create_task(coro)
    _background.add(task)
    task.add_done_callback(_background.discard)


if __name__ == "__main__":
    # python -m server.push [текст]  — тестовое уведомление всем подписанным устройствам
    if not enabled():
        sys.exit("FCM_SERVICE_ACCOUNT не задан или файл не найден")
    text = " ".join(sys.argv[1:]) or "Если вы это видите — мгновенные уведомления работают"
    ok = asyncio.run(send_to_topic({"type": "test", "title": "Тест AlyaNovel", "body": text}))
    print("отправлено" if ok else "не отправлено — см. сообщение выше")
