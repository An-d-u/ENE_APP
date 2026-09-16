# HTTPS/WSS 전송 구현 상태

검증일: 2026-09-08. 이 문서는 TLS 전송 기반의 인계 기록이며 사용 가능한 동반 앱의 출시 완료를 뜻하지 않는다.

2026-09-16 후속: 연결 Repository·전경 수명·QR 카메라·최소 채팅 화면을 추가했다. 최신 빌드/시험/미검증 경계는 [Android 연결 검증 기록](android-connection-validation.md)에 정리했다. 아래 수치와 APK 해시는 TLS 선행 단계의 과거 기록이다.

## 현재 범위

- QR의 `transport: tls_v1`와 PC별 CA를 필수로 받는다. 구형 평문 등록은 다시 페어링해야 한다.
- 플랫폼 TrustManagerFactory가 QR CA 하나로 체인을 검증한다. URL/SNI는 `ene-<server_id>.invalid`, 실제 IPv4·수동 이름은 라우팅에만 사용한다.
- 기본 호스트 이름 검증은 반드시 통과해야 한다. 추가 CA 유효기간 검사만 제한적 래퍼로 수행하며 검증 실패 허용·trust-all·평문 fallback은 없다.
- OkHttp 5.3.2가 WebSocket에서 EventListener와 network interceptor를 생략하므로 이름 검증 전후의 CA 날짜 검사와 유휴 연결 0 설정이 함께 필요하다. HTTPS의 활성 HTTP/2 공유 연결은 connectionAcquired에서도 검사한다. [공식 연결 구현](https://raw.githubusercontent.com/square/okhttp/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/ws/RealWebSocket.kt)
- DNS 조회는 실제 작업 스레드 1개·추가 대기열 0개로 제한한다. 시간 초과가 OS DNS 작업 종료를 보장한다고 가정하지 않는다.
- CA·토큰·PC/기기 ID·등록 세대·전송 프로필을 하나의 AES-GCM 레코드로 보관한다. Keystore에는 비추출 AES 키를 두고 암호문은 noBackupFilesDir에 둔다. 암호화 envelope 버전은 1, 내부 TLS 등록 버전은 2다.
- CA는 QR 해석·등록 복원/저장·요청·TLS 이름 검증·소켓 송수신·만료 감시에서 재검사한다. 승인되지 않은 401이나 일반 네트워크 오류로 토큰을 자동 폐기하지 않는다.
- Manifest와 network security XML에서 평문을 차단한다. 백업 제외는 구형·신형 정책에 모두 적용한다.

## 검증 결과

| 항목 | 결과 |
| --- | --- |
| JVM 단위 시험 | 60개 통과, 공통 계약 사례 포함 |
| 실제 HTTPS/WSS 시험 | 정상/다른 CA/이름/만료·미래 인증서/평문·리다이렉트/서버 교체/취소·만료 경합 검증 |
| 정적 검사 | 오류 0, 경고 23: 버전 업데이트 안내 22개·미구현 앱 아이콘 1개 |
| 디버그 APK 빌드 | 성공, 아직 기본 개발 화면 |
| 기기 시험 코드 | Keystore 시험 6개 컴파일·시험 APK 빌드 성공, 단말 실행 미검증 |
| 공개 시험 인증서 | 시험 APK에만 포함, 앱 APK에는 없음. 두 APK에 개인키 파일 없음 |
| 의존성 | 공식 Google/Maven 저장소, 고정 버전/잠금/해시 검증. 기존 등록 해시 변경 없음 |

집중 리뷰의 Important 1건(협상 중 CA 만료 후 헤더 전송)과 Minor 1건(숫자가 섞인 DNS 이름 오인)은 실패 재현→수정→회귀 통과 및 재검토를 마쳤다. HTTP/1 `/info` 연결이 WSS에 재사용되지 않는 시험도 포함한다.

JVM CA 생성에는 JDK keytool을 사용한다. okhttp-tls HeldCertificate 빌더만으로는 CA의 keyCertSign을 넣을 수 없어 CA 생성만 분리했다. 기기 시험의 공개 CA는 `tools/GenerateTestCa.java`가 빌드 때 생성하고 임시 개인키를 삭제한다. 실제 인증키나 고정 만료 인증서를 소스에 넣지 않는다.

재현 명령(JDK 21·Android SDK 36 설치 후 저장소 루트):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --offline --dependency-verification strict --console=plain
.\gradlew.bat :app:assembleDebugAndroidTest --offline --dependency-verification strict --console=plain
```

최초 환경은 필요한 공식 의존성을 먼저 받아야 한다. 오프라인 캐시 부재를 검사 통과로 취급하거나 검증을 끄지 않는다. 이번 Lint 실행에서 누락된 31.13.2 도구를 받아 해시를 기록한 후 오프라인 검증을 완료했다. 로컬 SDK 경로 파일은 Git에서 제외하며 Windows 드라이브 콜론은 properties 규칙으로 이스케이프한다.

검증한 앱 디버그 APK SHA-256:

`A03B11423FFEBD3727C874E4A94714DF0CE8CBBD560F0F999523B78CCAABB1E1`

이는 출시 서명 APK가 아니며 빌드 산출물은 커밋하지 않는다. 시험 APK는 실행 때 생성한 공개 인증서 때문에 다시 빌드하면 해시가 달라질 수 있다.

## 다음 작업과 남은 경계

2026-09-08 당시 미구현이던 연결 Repository·전경 수명·카메라·최소 화면은 후속 구현에 연결했다. 실제 단말 검증은 남아 있다. PC의 실제 ENE 대화·AI 접수·트레이/종료 통합도 별도 기본 계획의 후속 단계다.

휴대폰 설치, 실제 LAN 왕복, Android Keystore 실제 실행, release 서명, GitHub 게시를 완료했다고 주장하지 않는다. 단말 선택/설치와 방화벽 변경·외부 API 호출·원격 업로드는 각각 후속 승인 경계를 유지한다. Tailscale, LTE/5G, TTS, Live2D는 현재 구현 범위가 아니다.
