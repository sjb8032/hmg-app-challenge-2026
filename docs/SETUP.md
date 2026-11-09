# 개발 환경 세팅 가이드

팀원 모두가 같은 환경에서 빌드하고 실행할 수 있게 맞추는 문서입니다. 2026년 11월 9일, Pleos 공식 문서 기준입니다.

## 0. 버전 기준

| 항목 | 버전 |
| --- | --- |
| Android Studio | 최신 버전 |
| JDK | 17 이상 (Android Studio 내장 JDK 사용 가능) |
| Gradle | 8.10.2 (wrapper가 자동으로 받음) |
| Android Gradle Plugin | 8.8.2 |
| Kotlin | 2.0.0 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 28 |
| Pleos Connect Emulator | 2.1.0 (API 34 system image) |
| Vehicle SDK | 2.3.2 |
| NaviHelper SDK | 3.0.5 |

버전은 `gradle/libs.versions.toml` 한 곳에서 관리합니다. 바꿀 때는 팀에 먼저 알립니다.

## 1. 계정

1. 각자 [Pleos Playground](https://pleos.ai/playground)에 가입합니다.
2. 대표 한 명이 Workspace > My Project에서 프로젝트를 만들고, 프로젝트의 SDK 메뉴에서 SDK 제공 상태를 확인합니다.
   - 문서상 Vehicle(상태 조회)과 NaviHelper는 신청 없이 바로 쓸 수 있습니다.
   - TextToSpeech, SpeechToText, FusedLocation, Vehicle 제어는 '준비 중'으로 안내돼 있어 콘솔에서 다시 확인해야 합니다.

## 2. Android Studio

1. [Android Studio](https://developer.android.com/studio) 최신 버전을 설치합니다.
2. 예전 Pleos Connect Emulator가 깔려 있으면 먼저 지웁니다.

## 3. Pleos Connect 에뮬레이터

1. Tools > SDK Manager > **SDK Update Sites** 탭에서 **+** 를 누르고 아래처럼 추가합니다.
   - Name: `Pleos Connect System Image`
   - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - URL (Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms** 탭에서 **Show Package Details**를 켜고, Android API 34의 Pleos Connect system image를 체크해 설치합니다. 설치가 끝나면 Android Studio를 재시작합니다.
3. Tools > Device Manager > **Create device** > Form Factor `Automotive` > `Pleos Connect v2.1.0` > Next
4. **Advanced Settings**에서 Default boot를 `Cold`, RAM을 `4 GB`로 바꿉니다.
5. System image에서 API 34의 `Pleos Connect v2.1.0 APIs`를 고르고 Finish를 누릅니다.
6. 차량 값을 바꾸는 Virtual Console은 [공식 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)대로 설정합니다.

일반 기기나 기본 에뮬레이터 이미지에서는 Pleos SDK가 동작하지 않습니다. 꼭 Pleos Connect AVD를 씁니다.

## 4. 프로젝트 받아서 실행하기

1. 저장소를 clone하고 Android Studio로 엽니다.
2. Gradle Sync를 기다립니다. 처음 Sync할 때 Pleos Maven 저장소에서 SDK를 받습니다.
3. Pleos Connect AVD를 켜고 Run(▶)을 누르거나 `./gradlew installDebug`를 실행합니다.
4. 첫 화면 '개발 환경 점검'에서 Vehicle SDK와 NaviHelper SDK가 모두 '연결됨'이면 세팅이 끝난 것입니다.
5. '도어 위치 읽기'를 눌러 값이 나오는지 보고, Virtual Console에서 도어 값을 바꾼 뒤 다시 읽어 봅니다.

## 5. 브랜치 규칙

- `main`은 항상 빌드되는 상태로 둡니다. 직접 push하지 않고 PR로 합칩니다.
- 작업 브랜치 이름은 `feat/기능이름`, `fix/버그이름`으로 씁니다.
- PR을 올리면 GitHub Actions가 APK를 빌드합니다. 빌드가 실패하면 합치지 않습니다.

## 6. 제출용 release APK

1. `./gradlew assembleRelease`를 실행합니다.
2. `app/build/outputs/apk/release/app-release.apk`를 `apk/팀명_앱이름_release.apk`로 복사합니다.

서명은 프로젝트 루트에 `keystore.properties`가 있으면 그 키로, 없으면 debug 키로 합니다. debug 키로도 설치·실행은 됩니다. 팀 공용 키를 쓰려면 `keystore.properties.example`을 참고하고, 키 파일과 `keystore.properties`는 절대 커밋하지 않습니다.

## 7. 권한

Manifest에는 실제로 쓰는 API의 최소 권한만 선언합니다. 지금은 `CAR_DOORS`, `NAVI_ROUTE` 두 개뿐입니다.

- Vehicle 조회 권한 예: `CAR_INFO`, `CAR_SPEED`, `CAR_DRIVE`, `CAR_ENERGY`, `CAR_MILEAGE`, `CAR_LIGHTS`, `CAR_WINDOWS`, `READ_WINDSHIELD_WIPERS` (모두 `pleos.car.permission.` 접두사)
- Vehicle 제어 권한은 `CONTROL_` 으로 시작합니다. 예: `CONTROL_CAR_CLIMATE`
- NaviHelper: `NAVI_ROUTE`(경로 정보 조회, 여정 요청), `NAVI_ROUTE_SEARCH`(경로 요청·취소, 재탐색, 경유지 추가·삭제, 경로 옵션 변경), `NAVI_CUSTOM_MAP`(내비 화면 요청)
- 전체 목록: [Vehicle 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [NaviHelper 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)

## 8. 문제 해결

- **SDK가 안 받아짐**: 브라우저로 `https://nexus-playground.pleos.ai/repository/maven-releases/`에 접속되는지 확인합니다. Vehicle 2.3.2가 안 받아지면 `gradle/libs.versions.toml`의 `pleosVehicle`을 공식 샘플 앱이 쓰는 `2.3.1`로 바꿉니다.
- **'실패'가 뜸**: Pleos Connect AVD인지, minSdk가 28 이상인지, Manifest에 권한이 있는지, Gradle Sync를 했는지 확인하고 AVD를 Cold boot로 다시 켭니다.
- **Gradle JDK 오류**: Settings > Build, Execution, Deployment > Build Tools > Gradle에서 Gradle JDK를 17 이상(내장 JDK)으로 바꿉니다.

## 참고 링크

- [앱 개발 시작하기](https://document.pleos.ai/docs/pleos-only/getting-started)
- [Vehicle SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro) / [NaviHelper SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [공식 Vehicle 샘플 앱](https://github.com/Pleos-Playground/pleos-sample-vehicle-app): 라이선스 표기가 없어 코드는 복사하지 않고 사용법만 참고합니다.
- [차량 앱 기획·설계 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro) / [개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/intro)
- [에뮬레이터 설치 원문](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
