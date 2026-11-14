# 제출용 APK

`wheeling_드라이브메이트_release.apk`: 드라이브메이트 0.1.0 release 빌드 (공모전 규칙 `팀명_앱이름_release.apk`).

- GitHub Actions(`.github/workflows/android.yml`의 `assembleRelease`)에서 키 없이 빌드했습니다. 서명은 debug 키이고, 카카오 키가 들어 있지 않아 장소 검색은 OpenStreetMap(Nominatim)을 씁니다.
- 설치: `adb install -r apk/wheeling_드라이브메이트_release.apk`

다시 만들 때:

1. 프로젝트 루트에서 `./gradlew assembleRelease`를 실행합니다. `local.properties`에 카카오 키(`kakao.rest.key`)가 있으면 APK 안에 들어가므로 빼고 빌드합니다.
2. `app/build/outputs/apk/release/app-release.apk`를 이 폴더로 복사하고 이름을 바꿉니다.
3. 파일이 너무 커서 올라가지 않으면 GitHub Releases에 올리고, 루트 README의 'APK 위치'에 Release 링크를 적습니다.
