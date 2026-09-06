# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TokenWatch Android는 [TokenWatch iOS](https://github.com/JuseongMoon/TokenWatch)의
Android 네이티브 구현입니다. AI 코딩 서비스 7종의 사용량·크레딧 잔량을 한 화면에서 보여줍니다.
두 플랫폼은 같은 서비스 목록·인증 방식·게이지 정책을 공유하며, 대조표는
[`docs/PARITY_BASELINE.md`](docs/PARITY_BASELINE.md)에 있습니다.

## Build Configuration

- **Application ID**: `com.ScienceFiction.TokenWatchAndroid`
- **min SDK**: 28 / **target SDK**: 36 / **compile SDK**: 36
- **JDK**: 21 (Android Studio 번들 JBR 사용)
- **Java/Kotlin target**: 11
- UI: Jetpack Compose (XML 레이아웃 없음)
- 네트워크: OkHttp + Moshi (Retrofit·Firebase SDK 사용하지 않음)

## Development Commands

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew lintDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

⚠️ `connectedDebugAndroidTest`는 연결 기기의 Debug 앱을 다시 설치하며 **저장된 에이전트와
자격증명을 초기화할 수 있습니다.** 실제 계정으로 로그인한 기기에서 실행하지 말고,
전용 테스트 기기나 로그인 전에만 실행합니다.

Release 빌드는 R8/minify까지 검증하지만 배포 서명 키는 저장소에 포함하지 않습니다.

## Project Structure

```text
app/src/main/java/com/ScienceFiction/TokenWatchAndroid/
├── auth/              CredentialVault(Android Keystore AES-GCM), OAuth code/device, 세션
├── data/              AppSettings, SettingsRepository, 코덱
├── domain/            WorkHoursSchedule, Announcement, 새로고침 정책
├── network/
│   ├── core/          NetworkTransport
│   ├── providers/     서비스 7종 (apikey / session / subscription)
│   ├── orchestration/ 중복 조회 억제, backoff
│   ├── status/        공식 status 컴포넌트 파싱
│   ├── parsing/
│   └── announcements/ Firestore REST 공지 피드
├── notifications/
├── store/             AnnouncementStore 등 영속 상태
├── localization/      ko / en
└── ui/                Compose 화면 · 컴포넌트 · 테마
```

## Secrets — `local.properties` 강제

**모든 키는 `local.properties`에만 두고, `app/build.gradle.kts`가 `BuildConfig`로 주입합니다.**
`local.properties`는 `.gitignore`에 등록되어 있으며 값을 소스·문서·커밋 메시지에 쓰지 않습니다.

```properties
ANNOUNCEMENT_PROJECT_ID=<firestore project id>
ANNOUNCEMENT_API_KEY=<firestore web api key>
ANNOUNCEMENT_FEED_DOC=android
```

`app/build.gradle.kts`는 `rootProject.file("local.properties")`를 `java.util.Properties`로
읽어 `localProperty(name)`로 꺼내고, 값이 없으면 `""`를 넣습니다. 빈 값이면
`AnnouncementFeedClient.feedUrl()`이 `null`을 돌려주어 **공지 기능만 조용히 꺼지고
빌드와 나머지 기능은 정상 동작합니다.** 빌드 스크립트에 키 값을 직접 쓰지 마십시오 —
`buildConfigField`에 리터럴을 넣으면 그대로 공개됩니다.

커밋 금지 대상:

- `*.jks`, `*.keystore`, `keystore.properties` (서명 키와 비밀번호)
- `local.properties`
- Firebase/GCP 서비스 계정 키
- 실제 사용자 토큰·API key·계정 데이터

## 토큰 취급 원칙

- 사용자 토큰과 API key는 **`AndroidCredentialVault`(Android Keystore AES-GCM)** 에만 저장합니다.
  암호화 키는 Keystore 밖으로 나가지 않습니다.
- Agent/DataStore 모델과 로그에는 토큰을 기록하지 않습니다. 표시할 때는 마스킹합니다.
- 앱 데이터는 cloud backup과 device transfer 대상에서 제외돼 있습니다
  (`allowBackup="false"`, `fullBackupContent="false"`, `dataExtractionRules`).

## `docs/` 정책

이 저장소의 `docs/`에는 **공개용 플랫폼 대조표만 둡니다.** 현재는
`PARITY_BASELINE.md` 하나이며 순수 기능 대조표입니다.

iOS TokenWatch 저장소는 `docs/`를 통째로 gitignore 합니다. 가격 전략과 미출시 기획이
거기 있었기 때문입니다. **같은 제품이지만 두 저장소의 `docs/` 정책은 다릅니다.**
Android 쪽 `docs/`에 다음을 두지 마십시오 — 로컬에 두고 gitignore 합니다.

- 가격·구독 전략
- 미출시 기능 기획
- 운영 절차, 스토어 콘솔 절차, 릴리스 진행 상태
- 서버 계약·인프라 식별자

## iOS 동기화

`docs/PARITY_BASELINE.md`가 **어느 iOS 커밋까지 맞췄는지**를 기록하는 유일한 곳입니다.
README에는 커밋 해시를 적지 않습니다 — 동기화할 때마다 손으로 갱신해야 하고, 잊으면
"관리 안 됨" 신호가 됩니다. 동기화 커밋에서 baseline 문서를 반드시 함께 갱신합니다.

## 공개 저장소 규칙

이 저장소는 공개되어 있다. 커밋한 것은 되돌려도 남는다.

- **시크릿 금지** — API 키·토큰·서명 키(`*.jks`/`*.p12`)·서비스 계정 키·실제 사용자 데이터를 커밋하지 않는다.
  값은 **`local.properties`** 에만 두고 저장소에는 `*.example`만 올린다.
  소스·plist·manifest·주석·커밋 메시지 어디에도 값을 쓰지 않는다.
  이미 올렸다면 되돌리는 것으로 끝내지 말고 **키를 폐기·재발급**한다.
  **예외** — Firebase 클라이언트 설정(`GoogleService-Info.plist`, `google-services.json`, `AIzaSy…`)과
  OAuth public client ID는 Google이 앱 바이너리 내장을 전제로 문서화한 **식별자**이며 비밀이 아니다.
  커밋해도 되고 재발급 대상이 아니다. 접근 통제는 Firestore 보안 규칙과 API 키의 `apiTargets`·앱 제한이 담당한다.
  **단 서비스 계정 키·Admin SDK 자격증명·서명 키는 이 예외에 해당하지 않는다.**
- **내부 정보 금지** — 로컬 절대경로(`/Users/…`), 저장소 밖 파일 참조, 관리자 URL,
  인프라 식별자(버킷·배포 ID·계정 번호), 개인 기기 식별자(UDID·시리얼),
  릴리스 진행 상태와 스토어 콘솔 절차는 문서에 남기지 않는다.
- **내부 문서 위치** — 가격 전략·미출시 기획·운영 절차·서버 계약은 저장소에 두지 않는다.
  로컬에 두고 gitignore 하되 **그 판단 근거를 이 문서에 적어** 다음 세션이 되돌리지 않게 한다.
  gitignore된 경로를 코드 주석이나 문서에서 참조하지 않는다 — 방문자에게는 끊어진 링크다.
- **문서 정확성** — 여기 적힌 버전·경로·명령·구조가 코드와 다르면 코드가 아니라 문서를 고친다.
  배포 타깃과 언어 버전은 프로젝트 기본값이 아니라 **앱 타깃의 실제 값**을 확인해 적는다.
- **브랜치** — 에이전트 작업 브랜치는 머지 후 지운다. 원격에 실험 브랜치를 남기지 않는다.
  **처음 push 하는 순간 그 브랜치의 문서·메모도 함께 공개된다.**
- **`main`에 force-push 하지 않는다.** 공개된 히스토리를 다시 쓰면 클론·포크한 쪽이 깨진다.
  (예외: 시크릿 제거 — 이때도 키 폐기가 먼저다.)
- **push 전 확인** — `git fetch origin && git status -sb`로 원격이 앞섰는지 보고, 앞섰으면 덮지 말고 rebase 한다.
  `git log origin/main..HEAD --stat`으로 올라갈 파일 전체를 확인해 무관한 파일을 분리하고,
  `git diff`에서 키·절대경로·기기 식별자가 없는지 본다. **`git add .` 금지.**
