# CareOn Wear OS

API와 DB 연결 전에도 Wear OS 흐름을 확인할 수 있는 Kotlin + Jetpack Compose 데모 앱입니다.

현재 구현된 더미 흐름:

```text
연결 코드 입력
→ 심박수 데모 값 측정
→ 상태 확인 또는 SOS
→ 데모 보호자 확인
→ 워치 확인 완료
```

## 실행 전 준비

- Android Studio 내장 JDK 21 선택
- Android SDK Platform 36 설치
- Device Manager에서 `Wear OS Small Round` AVD 생성
- `local.properties`에 Android SDK 경로 설정

현재 더미 연결 코드는 `111111`입니다.

## 빌드와 테스트

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

생성된 APK는 아래 위치에 있습니다.

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 데모 방법

1. 워치 앱을 실행하고 `111111`을 입력합니다.
2. 홈에서 `심박수 확인`을 눌러 정상값 `78 BPM` 흐름을 봅니다.
3. `다음 데모값`을 눌러 `124 BPM`으로 바꾼 뒤 다시 측정합니다. 연결 후에는 30초 간격으로 더미 심박수도 확인합니다.
4. `도움이 필요해요`를 누르거나 `긴급 도움` 화면 전체를 3초 길게 누릅니다. 위치 권한을 허용하면 현재 위치 또는 2분 이내 최근 위치가 함께 표시됩니다. 위치 확인 실패는 SOS를 막지 않습니다.
5. 더미 보호자가 3초 뒤 확인하고 워치에 완료 화면이 표시됩니다.
6. 홈의 `안심 구역 이탈 데모`로 이탈 확인 화면도 시연할 수 있습니다.

## 다음 연결 지점

`DemoCareOnRepository`가 현재 더미 데이터와 자동 확인을 담당합니다. 백엔드 준비 후 같은 `CareOnRepository` 인터페이스를 Retrofit 구현으로 교체합니다.

- `pair()` → `/api/wear/auth/pair`
- `measureHeartRate()` → Health Services `MeasureClient` + `/api/wear/heart-rates`
- `createEmergency()` → `/api/wear/emergency-events`
- `getEmergency()` → `/api/wear/emergency-events/active`
