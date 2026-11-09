# (앱 이름 미정)

> 한 줄 소개: (아이디어 확정 후 작성)

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 현대차그룹 인포테인먼트 Pleos Connect(Android Automotive OS 기반)에서 동작하는 차량용 앱입니다.

## 실행 방법

1. Pleos Connect Emulator v2.1.0 AVD를 실행합니다. 설치 방법은 [docs/SETUP.md](docs/SETUP.md)에 있습니다.
2. APK를 설치합니다.

   ```bash
   adb install -r apk/팀명_앱이름_release.apk
   ```

3. 런처에서 앱을 실행합니다.

소스에서 바로 실행하려면 Pleos Connect AVD를 켠 상태에서 `./gradlew installDebug`를 실행합니다.

## 테스트 환경

| 항목 | 버전·설정 |
| --- | --- |
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 (API 34 system image), RAM 4 GB, Cold boot |
| Vehicle SDK | 2.3.2 |
| NaviHelper SDK | 3.0.5 |
| 빌드 | JDK 17, Gradle 8.10.2, Android Gradle Plugin 8.8.2, Kotlin 2.0.0 |
| minSdk / targetSdk | 28 / 35 |

## 필요한 설정

- 차량 상태(도어 등)는 에뮬레이터의 Virtual Console에서 바꿉니다.
- (아이디어 확정 후 기능별 테스트 시나리오 작성)

## APK 위치

- `apk/팀명_앱이름_release.apk`
- 용량이 커서 저장소에 못 올리면 GitHub Releases 링크로 대체합니다.

## 팀

- (이름 · 역할)
