#!/usr/bin/env python3
"""
CI 전용: 이 작업 안에서만 Paparazzi(화면 캡처)를 붙인다.

저장소의 빌드 설정(build.gradle.kts)은 그대로 두어 Android Studio 동기화와 APK 빌드에는 영향이 없다.
- 루트·app build.gradle.kts의 plugins 블록에 Paparazzi 플러그인을 넣고
- app/src/snapshotTest/java의 캡처 테스트를 단위 테스트 폴더로 복사한다.
"""
import pathlib
import re
import shutil
import sys

VERSION = "1.3.5"
ROOT = pathlib.Path(__file__).resolve().parents[2]


def add_plugin(path: pathlib.Path, line: str) -> None:
    text = path.read_text(encoding="utf-8")
    match = re.search(r"^plugins\s*\{", text, flags=re.MULTILINE)
    if match is None:
        sys.exit(f"plugins 블록을 찾지 못했어요: {path}")
    text = text[: match.end()] + "\n    " + line + text[match.end():]
    path.write_text(text, encoding="utf-8")


def main() -> None:
    add_plugin(ROOT / "build.gradle.kts", f'id("app.cash.paparazzi") version "{VERSION}" apply false')
    add_plugin(ROOT / "app" / "build.gradle.kts", 'id("app.cash.paparazzi")')
    source = ROOT / "app" / "src" / "snapshotTest" / "java"
    target = ROOT / "app" / "src" / "test" / "java"
    shutil.copytree(source, target, dirs_exist_ok=True)
    print(f"Paparazzi {VERSION}를 이 CI 작업에만 붙였어요.")


if __name__ == "__main__":
    main()
