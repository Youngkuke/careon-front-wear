# CareOn Wear OS

CareOn Wear OS는 돌봄 대상자가 손목에서 심박수를 확인하고, 위급 상황이나 안심 구역 이탈 시 보호자에게 도움을 요청할 수 있도록 만든 독립 실행형 Wear OS 앱입니다.

이 프로젝트는 휴대폰 앱과 직접 Bluetooth 메시지를 주고받는 구조가 아닙니다. 워치가 인터넷을 통해 CareOn 백엔드 API에 직접 연결하며, 연결 코드로 발급받은 워치 전용 토큰을 사용합니다. 매니페스트에도 `com.google.android.wearable.standalone=true`가 선언되어 있습니다.

> 이 문서는 `careon-front-wear`의 현재 소스 코드를 기준으로 작성되었습니다. 모바일·웹 구현에 대한 설명은 포함하지 않습니다.

## 1. 주요 기능

| 기능 | 현재 구현 |
| --- | --- |
| 워치 연결 | 6자리 연결 코드 입력, 워치 전용 access/refresh token 저장 |
| 세션 복원 | 앱 재실행 시 저장된 토큰과 돌봄 대상 ID로 연결 상태 복원 |
| 토큰 갱신 | API가 `401`을 반환하면 refresh token으로 갱신 후 원래 요청 1회 재시도 |
| 심박수 측정 | Android `Sensor.TYPE_HEART_RATE`를 이용한 실제 센서 측정 |
| 자동 심박 확인 | 앱이 실행 중일 때 10초 간격으로 새 센서 값 수신 시도 |
| 심박 이상 확인 | 현재 기준값인 `110 BPM` 이상이면 사용자 상태 확인 화면 표시 |
| 수동 SOS | 긴급 도움 화면을 3초간 길게 눌러 오작동을 줄인 도움 요청 |
| SOS 상태 확인 | 서버에서 보호자 확인 상태를 polling하여 워치에 완료 화면 표시 |
| 현재 위치 확인 | 고정밀 현재 위치를 먼저 요청하고, 실패하면 2분 이내 최근 위치 사용 |
| 안심 구역 감지 | 위치 정확도와 연속 외부 표본을 함께 고려한 자체 거리 판정 |
| 이탈 후 응답 | `괜찮아요`, `도움이 필요해요`, 무응답 상태 전송 |
| 실시간 위치 공유 | 서버에서 공유가 활성화된 경우에만 설정된 주기로 위치 업로드 |
| 연결 정보/해제 | 연결된 보호자 정보 조회 및 워치에서 연결 해제 |
| 기기 상태 보고 | 배터리 잔량 전송 로직 구현 |

## 2. 전체 사용자 흐름

```text
앱 실행
  ├─ 저장된 세션 없음
  │    └─ 6자리 연결 코드 입력
  │         └─ 위치 권한 안내
  │              └─ 홈
  │
  └─ 저장된 세션 있음
       └─ 세션 복원
            └─ 위치 권한 안내 또는 홈

홈
  ├─ 심박수 확인
  │    └─ 센서 측정
  │         ├─ 정상 → 결과 기록 → 홈
  │         └─ 기준 이상 → 상태 확인
  │              ├─ 괜찮아요 → 홈
  │              └─ 도움이 필요해요 → SOS 전송
  │
  ├─ 긴급 도움
  │    └─ 3초 길게 누르기
  │         └─ SOS 전송 → 보호자 확인 대기 → 확인 완료
  │
  ├─ 안심 구역 이탈 자동 감지
  │    └─ 사용자 상태 확인
  │         ├─ 괜찮아요 → 이탈 응답 전송 → 홈
  │         ├─ 도움이 필요해요 → 이탈 응답 + SOS 전송
  │         └─ 무응답 → NO_RESPONSE 전송 → 홈
  │
  └─ 설정
       ├─ 연결된 보호자 정보 조회
       └─ 워치 연결 해제
```

## 3. 기능별 구현 상세

### 3.1 워치 연결과 인증

워치 연결 화면은 원형 화면에서도 입력하기 쉽도록 숫자 키패드를 직접 구성했습니다. 사용자가 6자리 코드를 모두 입력하면 연결 버튼이 활성화됩니다.

연결 과정은 다음과 같습니다.

1. `POST /api/wear/auth/pair`에 연결 코드를 전송합니다.
2. 응답의 `wear_access_token`, `wear_refresh_token`, `cared_id`, `cared_relation`을 받습니다.
3. 토큰과 연결 정보를 Android `SharedPreferences`의 `wear_session` 영역에 저장합니다.
4. 위치 권한 안내 화면을 거쳐 홈으로 이동합니다.
5. 앱을 다시 실행하면 저장된 access token과 돌봄 대상 ID를 이용해 세션을 복원합니다.

API 요청에서 `401 Unauthorized`가 발생하면 다음 순서로 처리합니다.

```text
원래 API 요청
→ 401 수신
→ POST /api/wear/auth/refresh
→ 새 access/refresh token 저장
→ 원래 요청 1회 재시도
→ 다시 401 또는 refresh 실패
→ 로컬 세션 삭제
→ 연결 코드 입력 화면으로 이동
```

설정 화면에서는 `GET /api/wear/connection`으로 연결된 보호자의 이름과 이메일을 조회합니다. `DELETE /api/wear/connection`이 성공하면 서버 연결과 로컬 토큰을 함께 제거합니다.

연결 해제 직후 같은 요청이 재시도되어 `401`이 발생한 경우에도, 서버에서 이미 토큰을 무효화했을 수 있으므로 연결 해제가 완료된 것으로 처리합니다.

### 3.2 심박수 측정

심박수는 Health Services의 `MeasureClient`가 아니라 Android 표준 센서 API를 직접 사용합니다.

```kotlin
SensorManager
    .getDefaultSensor(Sensor.TYPE_HEART_RATE)
```

`AndroidHeartRateSensorClient`는 측정 요청 시 센서 listener를 등록하고, `0`보다 큰 첫 BPM 값이 들어오면 즉시 listener를 해제합니다. 이 방식은 한 번의 측정 요청마다 짧게 센서를 구독하므로 UI 상태와 센서 수명을 단순하게 맞출 수 있습니다.

수동 측정 흐름은 다음과 같습니다.

1. 심박 권한이 없으면 런타임 권한을 요청합니다.
2. 측정 화면에서 센서 이벤트를 기다립니다.
3. 20초 동안 유효한 값이 없으면 측정을 취소하고 안내 메시지를 표시합니다.
4. 값을 받으면 `HeartRateReading`으로 변환합니다.
5. BPM이 기준값 이상인지 평가합니다.
6. 측정값을 서버로 전송합니다.

현재 기준값은 `110 BPM`입니다.

```kotlin
if (bpm >= threshold) CHECK_IN else NORMAL
```

- `110 BPM` 미만: `NORMAL`
- `110 BPM` 이상: `CHECK_IN`

측정값은 다음 정보를 포함합니다.

| 필드 | 값 |
| --- | --- |
| `bpm` | 센서가 전달한 정수 BPM |
| `measured_at` | 워치에서 측정한 UTC 시각 |
| `source` | `WATCH_SENSOR` |

서버 기록 요청에는 UUID 기반 `Idempotency-Key`를 넣어 동일 요청이 중복 처리될 가능성을 줄입니다. 일시적인 심박 기록 API 실패는 SOS와 사용자 상태 확인을 막지 않도록 best-effort로 처리합니다.

#### 전경 자동 측정

심박 권한과 센서가 준비되면 앱이 실행 중인 동안 10초마다 새 심박 이벤트를 요청합니다. 홈에는 다음 자동 측정까지 남은 시간이 표시됩니다.

자동 측정도 임의의 BPM을 만들지 않습니다. 센서가 새 이벤트를 전달한 경우에만 최근 심박수와 서버 기록을 갱신합니다. 사용자가 홈 화면에 있을 때 측정값을 다음처럼 처리합니다.

| 심박수 | 처리 |
| --- | --- |
| 40 BPM 이하 또는 130 BPM 이상 | 재측정과 상태 확인 없이 보호자에게 즉시 알림 |
| 41~59 BPM 또는 111~129 BPM | 상태 확인 화면을 열고 30초 동안 응답이 없으면 보호자에게 알림 |
| 60~110 BPM | 정상 기록 |

상태 확인에서 `괜찮아요`를 선택하면 홈으로 돌아갑니다. 같은 확인 화면이 10초마다 반복되지 않도록 이후 1분 동안 중간 단계의 자동 상태 확인만 제한하며, 40 BPM 이하 또는 130 BPM 이상의 위험 수치는 제한 없이 즉시 알립니다.

### 3.3 수동 SOS

홈에서 `긴급 도움`을 누르면 SOS 전용 화면으로 이동합니다. 화면 진입 시 위치를 갱신하고, 안내 영역을 3초 동안 계속 눌러야 실제 요청이 전송됩니다.

- 3초 전에 손을 떼면 요청을 취소합니다.
- 누르는 동안 진행률을 표시합니다.
- 누르지 않는 동안에는 홈으로 돌아갈 수 있습니다.
- 위치를 확인하지 못해도 SOS 자체는 막지 않습니다.

SOS에는 다음 정보가 포함됩니다.

| 정보 | 설명 |
| --- | --- |
| `trigger` | `MANUAL_SOS` 또는 `HEART_RATE_CHECK_IN` |
| `heart_rate_bpm` | 가장 최근 심박수, 없으면 `null` |
| `requested_at` | 요청 시각 |
| `location_status` | 현재 위치, 최근 위치, 권한 거부, GPS 비활성, 확인 불가 |
| `location` | 위도, 경도, 정확도, 측정 시각, 위치 출처. 없으면 `null` |

SOS 생성 후에는 `GET /api/wear/emergency-events/{eventId}`를 반복 호출합니다.

- 정상 상태에서는 1초 간격으로 확인합니다.
- 네트워크 오류가 발생하면 2배씩 늘려 최대 16초 간격으로 재시도합니다.
- 서버 상태가 `ACKNOWLEDGED`가 되면 `보호자 확인` 화면으로 이동합니다.
- polling 중 일시적인 네트워크 오류가 발생해도 대기 중인 SOS 화면은 유지합니다.

### 3.4 위치 확인

`FusedCareOnLocationClient`는 Google Play services의 `FusedLocationProviderClient`를 사용합니다.

위치 선택 규칙은 다음과 같습니다.

1. GPS 또는 네트워크 위치 provider가 켜져 있는지 먼저 확인합니다.
2. `PRIORITY_HIGH_ACCURACY`로 현재 위치를 요청합니다.
3. 현재 위치 요청의 유효 시간은 5초이며, 최대 6초 동안 결과를 기다립니다.
4. 현재 위치가 없으면 마지막 위치를 최대 2초 동안 조회합니다.
5. 마지막 위치가 현재 시각 기준 2분 이내인 경우에만 사용합니다.
6. 사용할 위치가 없으면 `UNAVAILABLE`, 위치 서비스가 꺼져 있으면 `GPS_DISABLED`로 처리합니다.

`LocationSnapshot`은 다음 값을 보존합니다.

- 위도와 경도
- 정확도(m)
- 위치 측정 시각
- `CURRENT` 또는 `LAST_KNOWN` 출처

위치 권한을 거부하거나 `나중에`를 선택해도 심박수 확인과 SOS는 사용할 수 있습니다. 다만 안심 구역 감지와 실시간 위치 공유는 동작하지 않습니다.

### 3.5 안심 구역 감지

안심 구역은 OS의 Geofencing API가 아니라, 워치가 받은 위치와 서버의 원형 구역을 앱 내부에서 비교하는 방식으로 구현되어 있습니다.

앱이 실행 중이고 위치 권한이 있으면 10초마다 다음 작업을 수행합니다.

1. `GET /api/wear/safe-zone`으로 최신 안심 구역을 조회합니다.
2. 현재 또는 사용 가능한 최근 위치를 가져옵니다.
3. 정확도가 `100m`보다 나쁜 표본은 판정에서 제외합니다.
4. Haversine 공식을 사용해 구역 중심과 워치 사이의 거리를 계산합니다.
5. 연속된 외부 표본인지 확인합니다.

경계 오차를 줄이기 위한 내부 판정식은 다음과 같습니다.

```text
distance < radius - 20m
→ 구역 내부

margin = max(locationAccuracy, 30m)
distance <= radius + margin
→ 경계 오차를 고려해 구역 내부

위 조건을 모두 벗어남
→ 외부 후보
```

외부 후보가 곧바로 이탈 이벤트가 되지는 않습니다. 다음 두 조건을 모두 만족해야 `OUTSIDE_CONFIRMED`가 됩니다.

- 외부 판정을 최소 2회 받음
- 첫 외부 판정부터 최소 10초가 지남

확정된 한 번의 연속 이탈에 대해서는 이벤트를 한 번만 생성합니다. 사용자가 구역 안으로 돌아오면 latch를 해제하여 이후의 새로운 이탈을 다시 감지할 수 있습니다. 안심 구역 ID, 중심 좌표, 반경이 변경되면 기존 판정 상태도 초기화합니다.

이탈 이벤트가 만들어지면 워치에 다음 선택지를 표시합니다.

- `괜찮아요`: `USER_OKAY` 전송 후 홈으로 이동
- `도움이 필요해요`: `NEED_HELP` 전송 후 SOS 생성
- 제한 시간 내 응답 없음: `NO_RESPONSE` 전송 후 홈으로 이동

응답 제한 시간은 서버의 `response_deadline_at`을 우선 사용하고, 값이 없으면 30초를 사용합니다. 앱 프로세스가 다시 만들어져도 서버에 활성 이탈 이벤트가 남아 있으면 `GET /api/wear/safe-zone-events/active`로 화면과 제한 시간을 복원합니다.

### 3.6 실시간 위치 공유

위치 권한만 허용했다고 자동으로 실시간 위치를 공유하지는 않습니다. 앱은 `GET /api/wear/live-location/tracking`을 조회하여 서버에서 공유가 명시적으로 활성화된 경우에만 위치를 업로드합니다.

```text
추적 설정 조회
  ├─ enabled = false → 위치를 보내지 않고 다음 설정 조회 대기
  └─ enabled = true
       → 위치 확인
       → POST /api/wear/live-location
       → 서버가 지정한 interval 후 반복
```

서버가 주는 전송 간격은 최소 5초, 최대 60초 범위로 보정하며 기본값은 10초입니다. 현재 구현은 앱이 보이는 전경 실행 상태를 기준으로 합니다.

### 3.7 배터리 상태 보고

Android `BatteryManager.BATTERY_PROPERTY_CAPACITY`로 0~100 범위의 배터리 잔량을 읽고 `POST /api/wear/device-status`로 전송합니다. 설계된 반복 간격은 15분입니다.

다만 현재 `startSafeZoneMonitoring()`과 안심 구역 응답 timeout 시작 시 배터리 보고 job을 취소하는 경로가 남아 있습니다. 따라서 API와 보고 코드는 구현되어 있지만, 앱 실행 내내 15분 반복이 유지된다고 보장할 수는 없습니다. 운영 사용 전에는 이 job을 안심 구역 job과 독립적으로 관리하도록 보완해야 합니다.

### 3.8 오류 처리

연결 오류와 일반 동작 오류는 화면 중앙의 반투명 빨간 오버레이로 표시합니다. 사용자가 `×`를 누르면 오류 문구만 닫히며, 실패한 API를 자동으로 다시 실행하지는 않습니다.

기능별 실패 정책은 다음과 같습니다.

| 상황 | 처리 |
| --- | --- |
| 연결 코드 오류 | pairing 화면을 유지하고 서버 메시지 표시 |
| access token 만료 | refresh 후 원래 요청 1회 재시도 |
| refresh 실패 | 세션 삭제 후 pairing 화면으로 이동 |
| 심박 센서 없음 | 측정 취소 후 지원하지 않는 기기 안내 |
| 심박 측정 timeout | 20초 후 listener 해제 및 홈 복귀 |
| 심박 기록 실패 | 사용자 흐름을 막지 않고 다음 측정 진행 |
| 위치 권한 없음 | 위치 상태만 `PERMISSION_DENIED`, SOS 전송은 허용 |
| GPS 꺼짐 | `GPS_DISABLED` 상태로 SOS 전송 가능 |
| SOS 상태 조회 실패 | 대기 화면 유지, 최대 16초 backoff로 재시도 |
| 안심 구역 위치 실패 | 실패 표본을 이탈로 간주하지 않음 |

## 4. 화면 상태

화면 이동은 Navigation 라이브러리 대신 `WearScreen` enum과 단일 `CareOnWearUiState`로 관리합니다.

| 상태 | 화면 역할 |
| --- | --- |
| `PAIRING` | 6자리 연결 코드 입력 |
| `LOCATION_PERMISSION` | 위치 사용 목적 안내와 런타임 권한 요청 |
| `HOME` | 최근 심박수, 자동 측정 countdown, 주요 기능 진입 |
| `MEASURING` | 심박수 측정 중 애니메이션 |
| `RESULT` | BPM과 정상/확인 필요 결과 |
| `CHECK_IN` | `괜찮아요` 또는 `도움이 필요해요` 선택 |
| `SOS` | 위치 확인 및 3초 길게 누르기 |
| `WAITING` | SOS 보호자 확인 대기 |
| `ACKNOWLEDGED` | 보호자가 요청을 확인한 상태 |
| `SAFE_ZONE_EXIT` | 안심 구역 이탈 후 사용자 상태 확인 |
| `SETTINGS` | 연결 정보 조회와 연결 해제 |

각 화면은 워치 너비 `192dp`를 기준으로 레이아웃 크기를 계산하고, 실제 화면 너비에 따라 `0.82~1.25` 범위에서 배율을 조정합니다. 원형 화면의 하단 요소가 잘리지 않도록 주요 페이지에는 세로 스크롤도 적용했습니다.

## 5. 아키텍처

```text
MainActivity
  └─ CareOnWearApp
       ├─ Compose for Wear OS 화면
       ├─ 런타임 권한 처리
       ├─ FusedCareOnLocationClient 주입
       ├─ AndroidHeartRateSensorClient 주입
       └─ CareOnWearViewModel
            ├─ StateFlow<CareOnWearUiState>
            ├─ 심박수 측정 job
            ├─ SOS polling job
            ├─ 안심 구역 감지/응답 job
            ├─ 실시간 위치 공유 job
            ├─ 기기 상태 보고 job
            └─ CareOnRepository
                 ├─ RemoteCareOnRepository
                 │    ├─ HttpURLConnection
                 │    ├─ JSON 직렬화/역직렬화
                 │    └─ SharedPreferences 세션
                 └─ DemoCareOnRepository
                      └─ API 없이 일부 흐름을 확인하는 대체 구현
```

### 상태 관리

`CareOnWearViewModel`이 화면, 권한, 측정값, 위치, SOS, 안심 구역 상태를 하나의 `CareOnWearUiState`로 보관합니다. UI는 `collectAsStateWithLifecycle()`로 `StateFlow`를 구독하므로 생명주기에 맞춰 화면을 다시 그립니다.

장시간 또는 반복 동작은 `viewModelScope`의 coroutine `Job`으로 분리되어 있습니다.

- `automaticHeartRateJob`: 10초 간격 심박 이벤트 요청
- `emergencyPollingJob`: SOS 확인 상태 조회
- `liveLocationTrackingJob`: 서버 설정 기반 위치 공유
- `safeZoneMonitoringJob`: 10초 간격 안심 구역 판정
- `safeZoneResponseTimeoutJob`: 이탈 응답 제한 시간
- `deviceStatusJob`: 배터리 상태 보고
- `heartRateTimeoutJob`: 수동 심박 측정 20초 제한

연결 해제, 세션 만료, ViewModel 종료 시 관련 job과 센서 listener를 취소합니다.

### Repository 경계

`CareOnRepository`는 UI와 데이터 공급자를 분리하는 계약입니다. ViewModel은 HTTP 세부 구현을 알지 않으며 같은 인터페이스를 통해 원격 서버 또는 데모 구현을 사용할 수 있습니다.

- `RemoteCareOnRepository`: 현재 `MainActivity`가 사용하는 실제 구현
- `DemoCareOnRepository`: API 없이 일부 상태 흐름을 확인하기 위한 개발용 구현

원격 구현은 추가 네트워크 라이브러리 없이 `HttpURLConnection`과 `org.json.JSONObject`를 사용합니다. 모든 네트워크 작업은 `Dispatchers.IO`에서 실행합니다.

## 6. 기술 스택

| 구분 | 기술/버전 |
| --- | --- |
| 언어 | Kotlin 2.1.0 |
| 플랫폼 | Wear OS, `minSdk 30`, `targetSdk 36`, `compileSdk 36` |
| UI | Jetpack Compose, Compose BOM `2025.08.00` |
| Wear UI | Compose Material 3 for Wear OS `1.6.2` |
| Activity | `androidx.activity:activity-compose:1.10.1` |
| 상태/생명주기 | Lifecycle Runtime/ViewModel Compose `2.9.1` |
| 비동기 처리 | Kotlin Coroutines Android `1.9.0` |
| 위치 | Google Play services Location `21.3.0` |
| 심박수 | Android `SensorManager`, `Sensor.TYPE_HEART_RATE` |
| 네트워크 | `HttpURLConnection`, `JSONObject` |
| 로컬 세션 | Android `SharedPreferences` |
| 빌드 | Android Gradle Plugin `8.10.1`, Gradle `8.13` |
| JVM target | Java 17 |
| 단위 테스트 | JUnit `4.13.2` |

앱 버전은 현재 `0.1.0-demo`, `versionCode=1`입니다.

## 7. 프로젝트 구조

```text
careon-front-wear/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/careon/wear/
│       │   │   ├── MainActivity.kt
│       │   │   ├── data/
│       │   │   │   ├── DemoCareOnRepository.kt
│       │   │   │   └── RemoteCareOnRepository.kt
│       │   │   ├── location/
│       │   │   │   ├── CareOnLocationClient.kt
│       │   │   │   └── SafeZoneEvaluator.kt
│       │   │   ├── sensor/
│       │   │   │   └── HeartRateSensorClient.kt
│       │   │   └── ui/
│       │   │       ├── CareOnWearApp.kt
│       │   │       ├── CareOnWearTheme.kt
│       │   │       └── CareOnWearViewModel.kt
│       │   └── res/
│       └── test/java/com/careon/wear/
│           ├── HeartRateAssessmentTest.kt
│           └── SafeZoneEvaluatorTest.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew
```

| 파일 | 책임 |
| --- | --- |
| `MainActivity.kt` | Compose 앱 시작, 원격 repository 선택 |
| `CareOnWearApp.kt` | 화면 구성, 권한 launcher, 센서·위치 client 생성 |
| `CareOnWearViewModel.kt` | 상태 전이, 반복 job, 비즈니스 흐름 |
| `CareOnWearTheme.kt` | 워치용 색상 체계 |
| `DemoCareOnRepository.kt` | 도메인 모델, repository 계약, 데모 구현 |
| `RemoteCareOnRepository.kt` | Wear API, 토큰 저장·갱신, JSON 변환 |
| `HeartRateSensorClient.kt` | 실제 심박 센서 단발 측정 |
| `CareOnLocationClient.kt` | 현재/최근 위치 선택 |
| `SafeZoneEvaluator.kt` | 거리 계산과 연속 이탈 판정 |

## 8. 권한

`AndroidManifest.xml`에는 다음 권한과 기능이 선언되어 있습니다.

| 선언 | 사용 목적 |
| --- | --- |
| `android.hardware.type.watch` | 워치 기기용 앱임을 선언 |
| `BODY_SENSORS` | API 35 이하에서 심박 센서 읽기 |
| `android.permission.health.READ_HEART_RATE` | API 36 이상에서 심박수 읽기 |
| `ACCESS_FINE_LOCATION` | 정밀 위치와 안심 구역 판정 |
| `ACCESS_COARSE_LOCATION` | 대략적 위치 fallback |
| `INTERNET` | CareOn 백엔드 직접 통신 |

앱은 실행 중인 OS 버전에 따라 심박 권한을 선택합니다.

```text
API 36 이상 → android.permission.health.READ_HEART_RATE
API 35 이하 → android.permission.BODY_SENSORS
```

심박 권한은 사용자가 처음 측정을 요청할 때 받습니다. 위치 권한은 연결 직후 별도 안내 화면에서 요청하며, 건너뛸 수 있습니다.

## 9. Wear API

기본 서버 주소는 `RemoteCareOnRepository`에 `https://api.careon.site`로 설정되어 있습니다.

| 기능 | 메서드와 경로 | 인증 | 멱등성 키 |
| --- | --- | --- | --- |
| 워치 연결 | `POST /api/wear/auth/pair` | 연결 코드 | 없음 |
| 토큰 갱신 | `POST /api/wear/auth/refresh` | refresh token body | 없음 |
| 연결 정보 | `GET /api/wear/connection` | Bearer token | 없음 |
| 연결 해제 | `DELETE /api/wear/connection` | Bearer token | 없음 |
| 심박수 기록 | `POST /api/wear/heart-rates` | Bearer token | UUID |
| SOS 생성 | `POST /api/wear/emergency-events` | Bearer token | UUID |
| SOS 상태 | `GET /api/wear/emergency-events/{eventId}` | Bearer token | 없음 |
| 안심 구역 조회 | `GET /api/wear/safe-zone` | Bearer token | 없음 |
| 활성 이탈 복원 | `GET /api/wear/safe-zone-events/active` | Bearer token | 없음 |
| 이탈 이벤트 생성 | `POST /api/wear/safe-zone-events` | Bearer token | UUID |
| 이탈 응답 | `PATCH /api/wear/safe-zone-events/{eventId}/response` | Bearer token | 없음 |
| 위치 공유 설정 | `GET /api/wear/live-location/tracking` | Bearer token | 없음 |
| 실시간 위치 업로드 | `POST /api/wear/live-location` | Bearer token | 없음 |
| 기기 상태 보고 | `POST /api/wear/device-status` | Bearer token | 없음 |

모든 요청에는 JSON 응답을 기대하는 `Accept` header를 추가하고, 인증이 필요한 요청에는 Bearer token을 추가합니다. JSON body가 있는 요청에만 `Content-Type`을 설정합니다.

```http
Accept: application/json
Authorization: Bearer <wear_access_token>
Content-Type: application/json  # JSON body가 있을 때
```

심박수, SOS, 이탈 이벤트 생성에는 요청별 UUID를 `Idempotency-Key` header로 추가합니다. 연결과 읽기 timeout은 각각 10초입니다.

## 10. 빌드 및 실행

### 준비 환경

- Android Studio 최신 안정 버전
- JDK 21 권장. JDK 17도 사용 가능
- Android SDK Platform 36
- Wear OS API 36 시스템 이미지
- `Wear OS Small Round` AVD 권장

앱의 bytecode target은 Java 17입니다. Gradle 실행 JDK와 앱 target JVM은 서로 다른 개념이므로 Android Studio의 Gradle JDK가 17 또는 21로 선택되어 있는지 확인해야 합니다. 이 프로젝트의 Gradle 8.13/Kotlin 2.1.0 조합은 JDK 25로 실행하면 빌드가 시작되지 않습니다.

필요하다면 프로젝트 루트의 `local.properties`에 Android SDK 경로를 설정합니다.

```properties
sdk.dir=/path/to/Android/Sdk
```

### Android Studio에서 실행

1. Android Studio에서 `careon-front-wear` 디렉터리를 엽니다.
2. Gradle Sync가 완료될 때까지 기다립니다.
3. Device Manager에서 Wear OS AVD를 생성하고 실행합니다.
4. 실행 구성을 `app`으로 선택합니다.
5. 대상 기기로 Wear OS 에뮬레이터를 선택한 뒤 Run을 누릅니다.

### 명령행 빌드

```bash
cd careon-front-wear
./gradlew :app:assembleDebug
```

생성되는 APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

연결된 워치 또는 에뮬레이터에 직접 설치하려면 다음 명령을 사용할 수 있습니다.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

여러 Android 기기가 연결되어 있다면 `adb devices`로 워치 emulator ID를 확인하고 `adb -s <device-id> install -r ...` 형식으로 대상을 지정합니다.

## 11. 에뮬레이터 검증 방법

### 심박수

1. Wear OS 에뮬레이터에서 앱을 실행합니다.
2. 연결을 완료한 뒤 홈의 `심박수 확인`을 누릅니다.
3. 심박 권한을 허용합니다.
4. 에뮬레이터의 Extended Controls에서 Virtual Sensors의 Heart rate 값을 변경합니다.
5. 새 센서 이벤트가 전달되면 워치에 BPM이 표시되는지 확인합니다.
6. `110` 미만에서는 정상 결과, `110` 이상에서는 상태 확인 화면이 나오는지 확인합니다.

에뮬레이터가 새 센서 이벤트를 전달하지 않으면 자동 측정 countdown이 지나도 BPM이 바뀌지 않을 수 있습니다. 앱은 테스트 편의를 위해 가짜 값을 생성하지 않습니다.

### 위치와 안심 구역

1. 위치 권한을 허용합니다.
2. 에뮬레이터 Extended Controls의 Location에서 안심 구역 내부 좌표를 설정합니다.
3. 앱이 내부 상태를 유지하는지 확인합니다.
4. 구역 반경과 정확도 여유를 충분히 벗어난 좌표로 이동합니다.
5. 최소 2회의 외부 판정과 10초가 지난 뒤 이탈 화면이 표시되는지 확인합니다.
6. `괜찮아요`, `도움이 필요해요`, 무응답 흐름을 각각 확인합니다.

### SOS

1. 홈에서 `긴급 도움`을 엽니다.
2. 화면의 안내 영역을 3초 전에 놓아 요청이 취소되는지 확인합니다.
3. 다시 3초 이상 눌러 SOS를 전송합니다.
4. 위치 상태와 최근 심박수가 요청에 포함되는지 확인합니다.
5. 보호자 확인 상태가 서버에 반영되면 워치가 완료 화면으로 바뀌는지 확인합니다.

## 12. 테스트

단위 테스트와 debug 빌드를 한 번에 확인하려면 다음 명령을 실행합니다.

```bash
cd careon-front-wear
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

현재 단위 테스트 범위:

- 심박수 기준값 직전인 `109 BPM`이 `NORMAL`인지 확인
- 기준값인 `110 BPM`이 `CHECK_IN`인지 확인
- 같은 좌표의 거리가 거의 `0m`인지 확인
- 약 1km 떨어진 좌표가 외부로 계산되는지 확인
- 같은 외부 위치라도 두 번의 판정과 10초가 지나야 이탈 확정되는지 확인

센서, 권한, 실제 HTTP, `SharedPreferences`, Compose 화면은 현재 instrumented test가 없으므로 에뮬레이터 또는 실기기에서 별도 통합 검증이 필요합니다.

## 13. 데모 Repository 사용

현재 앱은 `MainActivity`에서 아래 원격 구현을 사용합니다.

```kotlin
CareOnWearApp(
    repository = RemoteCareOnRepository(applicationContext),
)
```

백엔드 없이 일부 흐름만 확인하려면 개발 중에 `DemoCareOnRepository()`로 교체할 수 있습니다.

```kotlin
CareOnWearApp(
    repository = DemoCareOnRepository(),
)
```

데모 연결 코드는 `111111`이며, 데모 SOS는 생성 후 약 3초가 지나면 보호자가 확인한 상태로 바뀝니다.

주의할 점은 데모 repository가 심박수 센서까지 가짜 값으로 바꾸지는 않는다는 것입니다. 현재 UI는 repository 종류와 관계없이 `AndroidHeartRateSensorClient`를 사용하므로, 심박수는 실기기 센서 또는 에뮬레이터 Virtual Sensors에서 새 값을 발생시켜야 합니다. 데모 세션도 `SharedPreferences`에 영속 저장되지 않습니다.

## 14. 현재 제한 사항과 후속 개선

### 전경 실행 의존

다음 반복 기능은 ViewModel coroutine으로 구현되어 있어 워치 앱 프로세스가 살아 있고 전경에서 실행되는 시연 환경을 기준으로 합니다.

- 10초 자동 심박 측정
- 10초 안심 구역 감지
- 실시간 위치 업로드
- 배터리 상태 보고

화면이 꺼지거나 Doze에 들어가거나 앱 프로세스가 종료된 뒤에도 계속 실행되는 상시 보호 기능은 아직 보장하지 않습니다. 운영 수준의 상시 동작이 필요하면 foreground service, WorkManager, Wear OS 백그라운드 실행 정책, 사용자에게 보이는 알림과 추가 센서 권한을 함께 설계해야 합니다.

### 오프라인 영속 queue 없음

SOS 또는 이탈 이벤트 생성이 네트워크 오류로 실패했을 때 요청 body와 `Idempotency-Key`를 디스크에 저장했다가 재실행 후 전송하는 queue가 없습니다. SOS 상태 조회는 메모리 안에서 재시도하지만, 앱 프로세스가 종료되면 polling도 종료됩니다.

### 토큰 저장

워치 access/refresh token은 일반 `SharedPreferences`에 저장됩니다. 운영 보안 수준을 높이려면 Android Keystore 기반 암호화 저장소 적용을 검토해야 합니다.

### 고정 설정

- API 기본 주소 `https://api.careon.site`가 소스에 고정되어 있습니다.
- 심박 상태 확인 기준 `110 BPM`이 앱 상수로 고정되어 있습니다.
- 개발·스테이징·운영 환경별 `BuildConfig` 분리가 없습니다.

환경별 endpoint와 정책값을 빌드 설정 또는 서버 프로필로 분리하면 배포와 운영이 더 안전해집니다.

### 의료적 판단 범위

현재 심박 판정은 단일 기준값 비교이며 의료 진단 알고리즘이 아닙니다. 측정값은 센서 상태, 착용 상태, 움직임, 기기별 정확도에 영향을 받을 수 있습니다. 실제 서비스에서는 의료기기 또는 응급의료 서비스를 대체하는 기능으로 안내하면 안 되며, 정책·문구·검증 기준을 별도로 마련해야 합니다.
