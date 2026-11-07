"""Общие настройки тестов: путь к корню проекта и образцы картинок."""
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]      # папка, где лежат common/ и server/
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
JPG = b"\xff\xd8\xff\xe0" + b"\x00" * 32
WEBP = b"RIFF\x00\x00\x00\x00WEBP" + b"\x00" * 16


@pytest.fixture
def png() -> bytes:
    return PNG


@pytest.fixture
def jpg() -> bytes:
    return JPG


@pytest.fixture
def webp() -> bytes:
    return WEBP
