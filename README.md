# 워치 설치 (watchinstall)

내가 만든 **Wear OS 앱(APK)을 폰에서 바로 갤럭시 워치에 설치**하는 안드로이드 앱이에요.
PC나 ADB 명령어 없이, 시계의 **무선 디버깅**으로 설치해요.

- 대상: 갤럭시 워치4 이후 (Wear OS 3+), 갤럭시 워치7에서 사용을 가정
- 폰: Android 8.0 (API 26) 이상

## 기능

- 같은 Wi-Fi에 있는 시계를 **자동으로 찾기** (mDNS)
- 시계에 뜨는 **6자리 코드로 페어링**, 다음부터는 자동 연결
- APK 고르기 또는 파일 관리자에서 **"공유 → 워치 설치"**
- 전송 진행률 표시, 설치 실패 원인을 **한국어로 설명**
- 워치용 앱인지, 시계 Android 버전에 맞는지(minSdk) 미리 경고
- 설치 후 **시계에서 바로 실행**

## 사용법

### 1. 시계 준비 (처음 한 번)
1. 시계 **설정 → 시계 정보 → 소프트웨어 정보 → 소프트웨어 버전**을 7번 눌러 개발자 옵션을 켜요.
2. **설정 → 개발자 옵션**에서 **ADB 디버깅**과 **무선 디버깅**을 켜요.
3. 시계를 폰과 **같은 Wi-Fi**에 연결해요.

### 2. 페어링 (처음 한 번)
1. 시계에서 **무선 디버깅 → 새 기기 페어링**을 눌러요. 6자리 코드가 나와요.
2. 앱의 "페어링 대기 중인 시계"에 시계가 나타나면 **페어링**을 누르고 코드를 입력해요.
   (안 보이면 **코드로 페어링**을 누르고 시계에 나온 IP, 포트, 코드를 직접 입력해요.)
3. 페어링이 끝나면 자동으로 연결돼요.

### 3. 설치
1. **APK 파일 고르기**로 설치할 APK를 골라요.
2. **시계에 설치**를 눌러요.

> 팁: 설치하는 동안 시계 화면을 켜 두세요. 화면이 꺼지면 시계 Wi-Fi가 꺼져서 연결이 끊길 수 있어요.

## 다운로드

GitHub Actions가 푸시마다 APK를 빌드해요.
**Actions 탭 → 최근 "Build APK" 실행 → Artifacts → `watchinstall-apk`** 에서 받을 수 있어요.
`v*` 태그를 푸시하면 Releases에도 올라가요.

## 직접 빌드

```bash
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest   # 단위 테스트
```

## 구조

| 파일 | 역할 |
|---|---|
| `adb/WatchDiscovery.kt` | mDNS로 시계의 페어링/연결 서비스 찾기 |
| `adb/WatchAdb.kt` | ADB 연결, 페어링, 셸 명령 실행 ([libadb-android](https://github.com/MuntashirAkon/libadb-android) 사용) |
| `adb/AdbKeys.kt` | 이 앱의 ADB 키와 인증서 생성·보관 |
| `install/ApkFile.kt` | 고른 APK 복사, 정보 읽기 |
| `install/WatchInstaller.kt` | `cmd package install -S` 스트리밍 설치 |
| `install/InstallResult.kt` | 설치 결과 해석, 오류 코드 설명 |
| `ui/` | Compose 화면과 ViewModel |

## 알려진 제한

- 분할 APK 묶음(.apks / .xapk)은 아직 지원하지 않아요. 하나로 된 APK를 써 주세요.
- 릴리스 APK는 아직 **디버그 키로 서명**돼요. 빌드한 곳(내 PC / GitHub Actions)이 바뀌면 서명이 달라서,
  폰에 이미 설치된 워치 설치 앱을 지우고 다시 설치해야 할 수 있어요.
