"""common/formats.py — форматы .mtx / .mpx, разбиение текста, коды глав."""
import json
from pathlib import Path

import pytest

from common import formats
from common.formats import FormatError


# ───────────── коды и номера глав ─────────────
@pytest.mark.parametrize("kind, vol, ctype, num, expected", [
    ("ranobe", 1, "chapter", 3, "R01-03"),
    ("manga", 12, "chapter", 5, "M12-05"),
    ("ranobe", 1, "prologue", 0, "R01-P"),
    ("ranobe", 2, "epilogue", 0, "R02-E"),
    ("ranobe", 1, "extra", 2, "R01-A02"),
    ("manga", 2, "extra", 10, "M02-A10"),
    ("manga", 1, "prologue", 0, "M01-P"),
    ("ranobe", 100, "chapter", 1, "R100-01"),
])
def test_chapter_code(kind, vol, ctype, num, expected):
    assert formats.chapter_code(kind, vol, ctype, num) == expected


@pytest.mark.parametrize("ctype", ["prologue", "epilogue"])
def test_normalize_number_prologue_epilogue_always_zero(ctype):
    assert formats.normalize_number(ctype, 7) == 0
    assert formats.normalize_number(ctype, None) == 0


def test_normalize_number_accepts_numeric_strings():
    assert formats.normalize_number("chapter", "3") == 3
    assert formats.normalize_number("extra", 2) == 2


@pytest.mark.parametrize("bad", [0, -1, "abc", None, ""])
def test_normalize_number_rejects_bad_numbers(bad):
    with pytest.raises(FormatError):
        formats.normalize_number("chapter", bad)


def test_normalize_number_extra_has_own_message():
    with pytest.raises(FormatError, match="экстры"):
        formats.normalize_number("extra", 0)


def test_normalize_number_unknown_type():
    with pytest.raises(FormatError, match="Неизвестный тип"):
        formats.normalize_number("bonus", 1)


def test_type_rank_order():
    ordered = sorted(formats.CHAPTER_TYPES, key=formats.TYPE_RANK.get)
    assert ordered == ["prologue", "chapter", "epilogue", "extra"]


# ───────────── сигнатуры картинок ─────────────
def test_sniff_ext(png, jpg, webp):
    assert formats._sniff_ext(png) == ".png"
    assert formats._sniff_ext(jpg) == ".jpg"
    assert formats._sniff_ext(webp) == ".webp"


@pytest.mark.parametrize("data", [b"", b"GIF89a....", b"<html>", b"RIFF\x00\x00\x00\x00WAVE", b"\x89PNG"])
def test_sniff_ext_rejects_other(data):
    with pytest.raises(FormatError):
        formats._sniff_ext(data)


# ───────────── разбиение текста ─────────────
def test_split_text_empty():
    assert formats.split_text("") == []


def test_split_text_min_size_enforced():
    with pytest.raises(FormatError):
        formats.split_text("x", formats.MIN_PART_BYTES - 1)
    assert formats.split_text("x", formats.MIN_PART_BYTES) == ["x"]


def test_split_text_small_text_is_one_part():
    assert formats.split_text("раз\nдва\nтри") == ["раз\nдва\nтри"]


def test_split_text_roundtrip_and_limit():
    text = "\n".join(f"Строка номер {i} " * 5 for i in range(300))
    parts = formats.split_text(text, 1024)
    assert len(parts) > 1
    assert "".join(parts) == text
    assert all(len(p.encode("utf-8")) <= 1024 for p in parts)


def test_split_text_breaks_on_line_boundaries():
    line = "a" * 99 + "\n"                      # 100 байт
    parts = formats.split_text(line * 25, 1024)  # в часть влезает 10 строк
    assert [p.count("\n") for p in parts] == [10, 10, 5]
    assert all(p.endswith("\n") for p in parts)


def test_split_text_keeps_missing_trailing_newline():
    text = "a\nb\nc"
    assert "".join(formats.split_text(text)) == text


def test_split_text_long_line_without_spaces_keeps_utf8_intact():
    text = "я" * 3000                            # 2 байта на символ, 6000 байт в одной строке
    parts = formats.split_text(text, 1024)
    assert "".join(parts) == text
    assert all(len(p.encode("utf-8")) <= 1024 for p in parts)


def test_split_text_long_line_prefers_space():
    text = "слово " * 1000
    parts = formats.split_text(text, 1024)
    assert "".join(parts) == text
    assert all(p.endswith(" ") for p in parts[:-1])


def test_split_text_handles_4_byte_characters():
    text = "😀" * 1000
    parts = formats.split_text(text, 1024)
    assert "".join(parts) == text
    assert all(len(p.encode("utf-8")) <= 1024 for p in parts)


# ───────────── строка-иллюстрация ─────────────
@pytest.mark.parametrize("line, expected", [
    ("![подпись](img:3)", ("подпись", 3)),
    ("  ![](img:1)  ", ("", 1)),
    ("![a](img:12)", ("a", 12)),
    ("текст ![a](img:1)", None),
    ("![a](https://i.postimg.cc/x.png)", None),
    ("![a](img:x)", None),
    ("обычная строка", None),
])
def test_parse_image_line(line, expected):
    assert formats.parse_image_line(line) == expected


# ───────────── .mtx ─────────────
def names_in(data: bytes) -> list[str]:
    import io, zipfile
    return zipfile.ZipFile(io.BytesIO(data)).namelist()


def test_build_mtx_manifest_fields():
    data = formats.build_mtx("Привет\nмир", title="Название", volume=1, chapter=3, author="Автор")
    m = formats.read_manifest(data)
    assert m["format"] == "mtx"
    assert m["version"] == formats.MTX_VERSION == 4
    assert m["title"] == "Название"
    assert (m["volume"], m["chapter"]) == (1, 3)
    assert m["code"] == "R01-03"
    assert m["type"] == "chapter"
    assert m["author"] == "Автор" and m["lang"] == "ru"
    assert m["chars"] == len("Привет\nмир")
    assert m["parts"] == ["0001.txt"]
    assert m["images"] == []
    assert isinstance(m["created"], int)
    assert names_in(data) == ["manifest.json", "0001.txt"]


def test_build_mtx_manifest_is_readable_utf8_json():
    import io, zipfile
    data = formats.build_mtx("текст", title="Заголовок", volume=1, chapter=1)
    raw = zipfile.ZipFile(io.BytesIO(data)).read("manifest.json").decode("utf-8")
    assert "Заголовок" in raw                    # ensure_ascii=False
    assert raw.startswith("{\n    ")             # indent=4
    json.loads(raw)


def test_build_mtx_roundtrip_text():
    text = "Первая строка\n\nТретья строка"
    assert formats.read_mtx_text(formats.build_mtx(text, title="t", volume=1, chapter=1)) == text


def test_build_mtx_normalizes_crlf_and_strips():
    data = formats.build_mtx("  a\r\nb  \n\n", title="t", volume=1, chapter=1)
    assert formats.read_mtx_text(data) == "a\nb"


def test_build_mtx_multiple_parts():
    text = "\n".join(f"Строка {i} " * 10 for i in range(200))
    data = formats.build_mtx(text, title="t", volume=1, chapter=1, max_part_bytes=1024)
    m = formats.read_manifest(data)
    assert len(m["parts"]) > 1
    assert m["parts"][0] == "0001.txt" and m["parts"][1] == "0002.txt"
    assert m["part_bytes"] == 1024
    assert formats.read_mtx_text(data) == text.strip()      # build_mtx обрезает пробелы по краям
    assert list(formats.iter_mtx_parts(data))[0].startswith("Строка 0")


def test_build_mtx_prologue_and_epilogue_have_no_number():
    p = formats.read_manifest(formats.build_mtx("x", title="t", volume=2, chapter=5, chapter_type="prologue"))
    e = formats.read_manifest(formats.build_mtx("x", title="t", volume=2, chapter=5, chapter_type="epilogue"))
    assert (p["chapter"], p["code"], p["type"]) == (0, "R02-P", "prologue")
    assert (e["chapter"], e["code"], e["type"]) == (0, "R02-E", "epilogue")


def test_build_mtx_extra_code():
    m = formats.read_manifest(formats.build_mtx("x", title="t", volume=1, chapter=2, chapter_type="extra"))
    assert m["code"] == "R01-A02"


def test_build_mtx_chapter_needs_number():
    with pytest.raises(FormatError):
        formats.build_mtx("x", title="t", volume=1, chapter=0)


@pytest.mark.parametrize("text", ["", "   ", "\n\r\n  \n"])
def test_build_mtx_empty_text(text):
    with pytest.raises(FormatError, match="Пустой"):
        formats.build_mtx(text, title="t", volume=1, chapter=1)


def test_build_mtx_with_images(png, jpg):
    text = "до\n![подпись](img:1)\nпосле\n![](img:2)"
    data = formats.build_mtx(text, title="t", volume=1, chapter=1, images=[png, jpg])
    m = formats.read_manifest(data)
    assert m["images"] == ["images/0001.png", "images/0002.jpg"]
    import io, zipfile
    z = zipfile.ZipFile(io.BytesIO(data))
    assert z.read("images/0001.png") == png
    assert z.read("images/0002.jpg") == jpg
    assert formats.read_mtx_text(data) == text


def test_build_mtx_unused_images_are_allowed(png, jpg):
    data = formats.build_mtx("просто текст", title="t", volume=1, chapter=1, images=[png, jpg])
    assert len(formats.read_manifest(data)["images"]) == 2


@pytest.mark.parametrize("text, n_images", [
    ("![x](img:2)", 1),      # номер больше числа картинок
    ("![x](img:0)", 1),      # нумерация с 1
    ("![x](img:1)", 0),      # картинок нет совсем
])
def test_build_mtx_dangling_image_reference(png, text, n_images):
    with pytest.raises(FormatError, match="несуществующую"):
        formats.build_mtx(text, title="t", volume=1, chapter=1, images=[png] * n_images)


def test_build_mtx_too_many_images(monkeypatch, png):
    monkeypatch.setattr(formats, "MAX_IMAGES", 2)
    with pytest.raises(FormatError, match="Не более 2"):
        formats.build_mtx("x", title="t", volume=1, chapter=1, images=[png] * 3)


def test_build_mtx_rejects_non_image_bytes():
    with pytest.raises(FormatError):
        formats.build_mtx("x", title="t", volume=1, chapter=1, images=[b"not an image"])


# ───────────── .mpx ─────────────
def test_build_mpx_manifest(png, jpg, webp):
    data = formats.build_mpx([png, jpg, webp], title="Манга", volume=1, chapter=3)
    m = formats.read_manifest(data)
    assert m["format"] == "mpx" and m["version"] == 1
    assert m["direction"] == "rtl"
    assert m["code"] == "M01-03"
    assert m["pages"] == ["pages/0001.png", "pages/0002.jpg", "pages/0003.webp"]
    assert names_in(data) == ["manifest.json"] + m["pages"]


def test_build_mpx_direction_and_prologue(png):
    m = formats.read_manifest(formats.build_mpx([png], title="t", volume=1, chapter=9, direction="ltr",
                                                chapter_type="prologue"))
    assert (m["direction"], m["chapter"], m["code"]) == ("ltr", 0, "M01-P")


def test_build_mpx_requires_images():
    with pytest.raises(FormatError, match="Нет изображений"):
        formats.build_mpx([], title="t", volume=1, chapter=1)


def test_build_mpx_page_limit(monkeypatch, png):
    monkeypatch.setattr(formats, "MAX_PAGES", 2)
    with pytest.raises(FormatError, match="Не более 2"):
        formats.build_mpx([png] * 3, title="t", volume=1, chapter=1)


def test_build_mpx_rejects_non_image_bytes():
    with pytest.raises(FormatError):
        formats.build_mpx([b"junk"], title="t", volume=1, chapter=1)


def test_extract_mpx(tmp_path, png, jpg):
    data = formats.build_mpx([png, jpg], title="t", volume=1, chapter=1)
    paths = formats.extract_mpx(data, tmp_path / "out")
    assert [Path(p).name for p in paths] == ["0001.png", "0002.jpg"]
    assert open(paths[0], "rb").read() == png
    assert open(paths[1], "rb").read() == jpg
    assert formats.extract_mpx(data, tmp_path / "out") == paths     # повторный вызов безопасен


def test_extract_mpx_rejects_mtx(tmp_path):
    data = formats.build_mtx("x", title="t", volume=1, chapter=1)
    with pytest.raises(FormatError, match="не .mpx"):
        formats.extract_mpx(data, tmp_path)


# ───────────── чтение: ошибки и совместимость ─────────────
def make_archive(manifest: dict | None, extra: dict[str, bytes] | None = None) -> bytes:
    files = {}
    if manifest is not None:
        files["manifest.json"] = json.dumps(manifest).encode()
    files.update(extra or {})
    return formats._pack(files)


def test_read_manifest_from_path(tmp_path):
    p = tmp_path / "R01-01.mtx"
    p.write_bytes(formats.build_mtx("текст", title="t", volume=1, chapter=1))
    assert formats.read_manifest(str(p))["code"] == "R01-01"
    assert formats.read_mtx_text(p) == "текст"


def test_read_manifest_not_a_zip():
    with pytest.raises(FormatError, match="Повреждённый"):
        formats.read_manifest(b"this is not a zip")


def test_read_manifest_missing_manifest():
    with pytest.raises(FormatError, match="Повреждённый"):
        formats.read_manifest(make_archive(None, {"0001.txt": b"x"}))


def test_read_manifest_invalid_json():
    with pytest.raises(FormatError, match="Повреждённый"):
        formats.read_manifest(formats._pack({"manifest.json": b"{not json"}))


@pytest.mark.parametrize("manifest", [{}, {"format": "zip"}, {"format": None}])
def test_read_manifest_unknown_format(manifest):
    with pytest.raises(FormatError, match="Неизвестный тип"):
        formats.read_manifest(make_archive(manifest))


@pytest.mark.parametrize("fmt, version", [("mtx", formats.MTX_VERSION + 1), ("mpx", formats.MPX_VERSION + 1)])
def test_read_manifest_newer_version_refused(fmt, version):
    with pytest.raises(FormatError, match="новее|более новой"):
        formats.read_manifest(make_archive({"format": fmt, "version": version}))


def test_read_manifest_older_versions_accepted():
    assert formats.read_manifest(make_archive({"format": "mtx", "version": 1}))["format"] == "mtx"


def test_mtx_version1_single_content_file():
    data = make_archive({"format": "mtx", "version": 1}, {"content.txt": "старый формат".encode()})
    assert formats.read_mtx_text(data) == "старый формат"


def test_mtx_version2_parts_without_images():
    data = make_archive({"format": "mtx", "version": 2, "parts": ["0001.txt", "0002.txt"]},
                        {"0001.txt": "раз\n".encode(), "0002.txt": "два".encode()})
    assert formats.read_mtx_text(data) == "раз\nдва"
    with pytest.raises(FormatError, match="Нет иллюстрации"):
        formats.extract_mtx_image(data, 1, "/tmp/never-used")


def test_iter_mtx_parts_rejects_mpx(png):
    data = formats.build_mpx([png], title="t", volume=1, chapter=1)
    with pytest.raises(FormatError, match="не .mtx"):
        list(formats.iter_mtx_parts(data))


def test_iter_mtx_parts_missing_part():
    data = make_archive({"format": "mtx", "version": 3, "parts": ["0001.txt"]})
    with pytest.raises(FormatError, match="нет части"):
        list(formats.iter_mtx_parts(data))


@pytest.mark.parametrize("name", ["../evil.txt", "/etc/passwd", "a/../../b.txt"])
def test_iter_mtx_parts_rejects_path_tricks(name):
    data = make_archive({"format": "mtx", "version": 3, "parts": [name]}, {"0001.txt": b"x"})
    with pytest.raises(FormatError, match="Недопустимый путь"):
        list(formats.iter_mtx_parts(data))


# ───────────── иллюстрации из .mtx ─────────────
def test_extract_mtx_image(tmp_path, png, jpg):
    data = formats.build_mtx("![](img:1)\n![](img:2)", title="t", volume=1, chapter=1, images=[png, jpg])
    out = tmp_path / "a" / "b"                     # вложенной папки ещё нет
    p1 = formats.extract_mtx_image(data, 1, out)
    p2 = formats.extract_mtx_image(data, 2, out)
    assert p1.endswith("0001.png") and open(p1, "rb").read() == png
    assert p2.endswith("0002.jpg") and open(p2, "rb").read() == jpg


def test_extract_mtx_image_does_not_overwrite_existing(tmp_path, png):
    data = formats.build_mtx("![](img:1)", title="t", volume=1, chapter=1, images=[png])
    p = formats.extract_mtx_image(data, 1, tmp_path)
    open(p, "wb").write(b"cached")
    assert formats.extract_mtx_image(data, 1, tmp_path) == p
    assert open(p, "rb").read() == b"cached"


@pytest.mark.parametrize("number", [0, 3, -1])
def test_extract_mtx_image_out_of_range(tmp_path, png, number):
    data = formats.build_mtx("![](img:1)", title="t", volume=1, chapter=1, images=[png, png])
    with pytest.raises(FormatError, match="Нет иллюстрации"):
        formats.extract_mtx_image(data, number, tmp_path)


def test_extract_mtx_image_rejects_path_tricks(tmp_path):
    data = make_archive({"format": "mtx", "version": 4, "parts": [], "images": ["../evil.png"]})
    with pytest.raises(FormatError, match="Недопустимый путь"):
        formats.extract_mtx_image(data, 1, tmp_path)
    assert not (tmp_path.parent / "evil.png").exists()


def test_extract_mpx_rejects_path_tricks(tmp_path):
    data = make_archive({"format": "mpx", "version": 1, "pages": ["../evil.png"]})
    with pytest.raises(FormatError, match="Недопустимый путь"):
        formats.extract_mpx(data, tmp_path)
