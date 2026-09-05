# TokenWatch Android

TokenWatch iOS의 Android 동등 구현입니다. 현재 기준선은 iOS 저장소의 commit
`8d7073e`이며, 기능 범위와 플랫폼별 대응은
[`docs/PARITY_BASELINE.md`](docs/PARITY_BASELINE.md)에 고정되어 있습니다.

## 주요 기능

- 공식 API 또는 검증된 엔드포인트를 사용하는 7개 서비스
- OAuth code, OAuth device flow, API key 인증
- 로그인 없이 전체 UI를 둘러보는 격리된 데모 모드
- 업무시간에만 진행하는 주간 게이지 현재시각 마커
- 구독 게이지와 충전형 크레딧 역방향 게이지(API 총액 또는 최고 관측 잔액 기준)
- Claude 추가 사용량(extra usage) 크레딧 표시
- foreground 전용 고정/적응형 자동 새로고침(10초~5분 사다리, 급증 시 급강하)
- 에이전트별 in-flight/20초 중복 조회 억제
- 429 backoff 중 마지막 정상 그래프 유지와 재시도 안내
- 공식 status 컴포넌트 기반 4단계 서비스 상태·전체 점검 표시와 마지막 정상 상태 보존
- 한국어/영어, heartbeat cursor, usage tracking heart, 계단 페이드 gauge slime
- Android Keystore AES-GCM 기반 자격증명 암호화 및 backup/transfer 제외

## 개발 환경

- Android Studio JDK 21
- compile/target SDK 36
- min SDK 28
- Application ID: `com.ScienceFiction.TokenWatchAndroid`

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
./gradlew lintDebug assembleRelease
```

`connectedDebugAndroidTest`는 연결 기기의 Debug 앱을 테스트용으로 다시 설치하며 저장된
에이전트와 자격증명을 초기화할 수 있습니다. 실제 계정으로 로그인한 기기에서는 실행하지
말고, 전용 테스트 기기나 로그인 전에만 실행합니다.

Release 빌드는 R8/minify까지 검증하지만 배포 서명 키는 저장소에 포함하지 않습니다.
실제 배포 전에 별도 keystore와 안전한 로컬/CI secret 설정이 필요합니다.

## 보안 원칙

- 토큰과 API key는 Agent/DataStore 모델이나 로그에 기록하지 않습니다.
- 자격증명 암호화 키는 Android Keystore 밖으로 내보내지 않습니다.
- 앱 데이터는 cloud backup 및 device transfer 대상에서 제외됩니다.
- keystore, 비밀번호, `local.properties`는 Git에 커밋하지 않습니다.
