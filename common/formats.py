"""Форматы глав.

Оба формата — обычные ZIP-контейнеры с собственным расширением:

  .mtx  (ранобэ, версия 3)       .mpx  (манга, версия 1)
  ├─ manifest.json               ├─ manifest.json
  ├─ 0001.txt                    └─ pages/0001.jpg, 0002.png, ...
  ├─ 0002.txt
  ├─ ...
  └─ images/0001.jpg, ...        (иллюстрации необязательны: глава может быть просто текстом)

Текст .mtx режется на части по размеру (в байтах UTF-8) строго по границам строк;
если одна строка длиннее лимита — режется по пробелу или по символу (UTF-8 не ломается).
"".join(части) всегда даёт исходный текст, поэтому части можно показывать по мере чтения.

manifest.json (общие поля):
  format   — "mtx" | "mpx"
  version  — версия формата; клиент отказывается открывать файл новее, чем знает
  title, volume, chapter, created
  type     — тип главы: chapter | prologue | epilogue | extra (необязательно: нет поля = обычная глава)
  code     — код файла: R01-P, R01-C03, R01-E, R01-A02 (R — ранобэ, M — манга)
Только mtx:  lang, author, chars, parts (список файлов по порядку), part_bytes (лимит),
             images (файлы иллюстраций, нумерация с 1; список может быть пустым)
Только mpx:  direction ("rtl" | "ltr" | "vertical"), pages (список файлов по порядку)

Совместимость (читаются как раньше): mtx версии 1 — весь текст в одном content.txt; версии 2 — части
0001.txt, 0002.txt… без иллюстраций.
"""
import io
import json
import re
import time
import zipfile

MTX_VERSION = 4          # одна версия и для текста, и для текста с иллюстрациями
MPX_VERSION = 1
MAX_VERSION = {"mtx": MTX_VERSION, "mpx": MPX_VERSION}
MAX_PAGES = 400
MAX_IMAGES = 60
DEFAULT_PART_BYTES = 32 * 1024
MIN_PART_BYTES = 1024


class FormatError(ValueError):
    pass


# ───────────── типы глав ─────────────
CHAPTER_TYPES = ("chapter", "prologue", "epilogue", "extra")
# порядок внутри тома: пролог → главы → эпилог → экстра (дополнительные, вне рамок сюжета)
TYPE_RANK = {"prologue": 0, "chapter": 1, "epilogue": 2, "extra": 3}


def normalize_number(chapter_type: str, number) -> int:
    """У пролога и эпилога номера нет (0, по одному на том); у глав и экстра номер ≥ 1."""
    if chapter_type not in CHAPTER_TYPES:
        raise FormatError(f"Неизвестный тип главы: {chapter_type}")
    if chapter_type in ("prologue", "epilogue"):
        return 0
    try:
        n = int(number)
    except (TypeError, ValueError):
        n = 0
    if n < 1:
        raise FormatError("Укажите номер главы (от 1)" if chapter_type == "chapter" else "Укажите номер экстры (от 1)")
    return n


def chapter_code(kind: str, volume: int, chapter_type: str, number: int | str) -> str:
    """R01-P — пролог, R01-03 — глава 3, R01-E — эпилог, R01-A02 — экстра 2; для манги вместо R — M."""
    base = f"{'R' if kind == 'ranobe' else 'M'}{volume:02d}"
    if chapter_type == "prologue":
        return f"{base}-P"
    if chapter_type == "epilogue":
        return f"{base}-E"
    if chapter_type == "extra":
        return f"{base}-A{number:02d}"
    return f"{base}-{number:02d}"


def _sniff_ext(data: bytes) -> str:
    if data.startswith(b"\xff\xd8\xff"):
        return ".jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return ".webp"
    raise FormatError("Допустимы только JPEG, PNG и WEBP")


def _pack(files: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    # картинки уже сжаты — для них STORED, текст и манифест — DEFLATE
    with zipfile.ZipFile(buf, "w") as z:
        for name, data in files.items():
            comp = zipfile.ZIP_DEFLATED if name.endswith((".json", ".txt")) else zipfile.ZIP_STORED
            z.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), data, compress_type=comp)
    return buf.getvalue()


def _open(src) -> zipfile.ZipFile:
    return zipfile.ZipFile(io.BytesIO(src) if isinstance(src, bytes) else src)


# ───────────── разбиение текста ─────────────
def _cut_long(line: str, max_bytes: int):
    """Режет одну слишком длинную строку на куски <= max_bytes (по пробелу, иначе по символу)."""
    raw = line.encode("utf-8")
    while len(raw) > max_bytes:
        cut = max_bytes
        while cut > 0 and (raw[cut] & 0xC0) == 0x80:  # не попадаем в середину символа
            cut -= 1
        piece = raw[:cut].decode("utf-8")
        sp = piece.rfind(" ")
        if sp > len(piece) // 2:  # красивый разрез по пробелу, если он не слишком близко к началу
            piece = piece[:sp + 1]
        yield piece
        raw = raw[len(piece.encode("utf-8")):]
    if raw:
        yield raw.decode("utf-8")


def split_text(text: str, max_bytes: int = DEFAULT_PART_BYTES) -> list[str]:
    if max_bytes < MIN_PART_BYTES:
        raise FormatError(f"Размер части не меньше {MIN_PART_BYTES} байт")
    parts, cur, cur_size = [], [], 0
    for line in text.splitlines(keepends=True):
        for piece in _cut_long(line, max_bytes):
            size = len(piece.encode("utf-8"))
            if cur and cur_size + size > max_bytes:
                parts.append("".join(cur))
                cur, cur_size = [], 0
            cur.append(piece)
            cur_size += size
    if cur:
        parts.append("".join(cur))
    return parts


# ───────────── сборка ─────────────
# Строка-иллюстрация внутри текста: ![подпись](img:N)
IMG_LINE = re.compile(r"^!\[(.*)\]\(img:(\d+)\)$")


def parse_image_line(line: str):
    """Возвращает (подпись, номер) если строка — иллюстрация, иначе None."""
    m = IMG_LINE.match(line.strip())
    return (m.group(1), int(m.group(2))) if m else None


def build_mtx(text: str, *, title: str, volume: int, chapter: int, author: str = "",
              lang: str = "ru", max_part_bytes: int = DEFAULT_PART_BYTES,
              images: list[bytes] | None = None, chapter_type: str = "chapter") -> bytes:
    chapter = normalize_number(chapter_type, chapter)
    text = text.replace("\r\n", "\n").strip()
    if not text:
        raise FormatError("Пустой текст главы")
    images = images or []
    if len(images) > MAX_IMAGES:
        raise FormatError(f"Не более {MAX_IMAGES} иллюстраций в главе")
    used = {n for line in text.split("\n") if (pl := parse_image_line(line)) for n in [pl[1]]}
    if used and (min(used) < 1 or max(used) > len(images)):
        raise FormatError("В тексте есть ссылка на несуществующую иллюстрацию")
    parts = split_text(text, max_part_bytes)
    names = [f"{i:04d}.txt" for i in range(1, len(parts) + 1)]
    image_names, files_img = [], {}
    for i, img in enumerate(images, 1):
        name = f"images/{i:04d}{_sniff_ext(img)}"
        image_names.append(name)
        files_img[name] = img
    manifest = {"format": "mtx", "version": MTX_VERSION,
                "title": title, "volume": volume, "chapter": chapter, "author": author, "lang": lang,
                "chars": len(text), "parts": names, "images": image_names, "part_bytes": max_part_bytes,
                "type": chapter_type, "code": chapter_code("ranobe", volume, chapter_type, chapter),
                "created": int(time.time())}
    files = {"manifest.json": json.dumps(manifest, indent=4, ensure_ascii=False).encode()}
    files.update({n: p.encode("utf-8") for n, p in zip(names, parts)})
    files.update(files_img)
    return _pack(files)


def build_mpx(images: list[bytes], *, title: str, volume: int, chapter: int,
              direction: str = "rtl", chapter_type: str = "chapter") -> bytes:
    chapter = normalize_number(chapter_type, chapter)
    if not images:
        raise FormatError("Нет изображений")
    if len(images) > MAX_PAGES:
        raise FormatError(f"Не более {MAX_PAGES} страниц")
    files, names = {}, []
    for i, img in enumerate(images, 1):
        name = f"pages/{i:04d}{_sniff_ext(img)}"
        files[name] = img
        names.append(name)
    manifest = {"format": "mpx", "version": MPX_VERSION, "title": title,
                "volume": volume, "chapter": chapter, "direction": direction,
                "type": chapter_type, "code": chapter_code("manga", volume, chapter_type, chapter),
                "pages": names, "created": int(time.time())}
    return _pack({"manifest.json": json.dumps(manifest, indent=4, ensure_ascii=False).encode(), **files})


# ───────────── чтение ─────────────
def read_manifest(src) -> dict:
    """src — путь к файлу или bytes."""
    try:
        with _open(src) as z:
            m = json.loads(z.read("manifest.json"))
    except (zipfile.BadZipFile, KeyError, json.JSONDecodeError) as e:
        raise FormatError(f"Повреждённый файл: {e}")
    fmt = m.get("format")
    if fmt not in MAX_VERSION:
        raise FormatError("Неизвестный тип файла")
    if m.get("version", 0) > MAX_VERSION[fmt]:
        raise FormatError("Файл создан в более новой версии формата — обновите приложение")
    return m


def iter_mtx_parts(src):
    """Отдаёт текст главы частями по порядку (удобно показывать первую часть, пока читаются остальные)."""
    m = read_manifest(src)
    if m["format"] != "mtx":
        raise FormatError("Это не .mtx")
    names = m.get("parts") or ["content.txt"]  # версия 1: один файл
    with _open(src) as z:
        for name in names:
            if ".." in name or name.startswith("/"):
                raise FormatError("Недопустимый путь внутри архива")
            try:
                yield z.read(name).decode("utf-8")
            except KeyError:
                raise FormatError(f"В архиве нет части {name}")


def read_mtx_text(src) -> str:
    return "".join(iter_mtx_parts(src))


def extract_mtx_image(src, number: int, out_dir) -> str:
    """Достаёт иллюстрацию №number (с 1) в out_dir, возвращает путь к файлу."""
    import os
    m = read_manifest(src)
    names = m.get("images") or []
    if not 1 <= number <= len(names):
        raise FormatError(f"Нет иллюстрации {number}")
    name = names[number - 1]
    if ".." in name or name.startswith("/"):
        raise FormatError("Недопустимый путь внутри архива")
    os.makedirs(out_dir, exist_ok=True)
    dst = os.path.join(out_dir, os.path.basename(name))
    if not os.path.exists(dst):
        with _open(src) as z:
            with open(dst, "wb") as f:
                f.write(z.read(name))
    return dst


def extract_mpx(src, out_dir) -> list[str]:
    """Распаковывает страницы в out_dir, возвращает пути по порядку."""
    import os
    m = read_manifest(src)
    if m["format"] != "mpx":
        raise FormatError("Это не .mpx")
    os.makedirs(out_dir, exist_ok=True)
    paths = []
    with _open(src) as z:
        for name in m["pages"]:
            if ".." in name or name.startswith("/"):
                raise FormatError("Недопустимый путь внутри архива")
            dst = os.path.join(out_dir, os.path.basename(name))
            if not os.path.exists(dst):
                with open(dst, "wb") as f:
                    f.write(z.read(name))
            paths.append(dst)
    return paths
