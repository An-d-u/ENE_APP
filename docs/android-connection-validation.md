# Android 연결·최소 화면 검증 기록

검증일: 2026-09-16. 기본 계획 Task 5의 구현 기록이다. 실제 휴대폰 LAN 시험과 실제 ENE 통합 완료 기록이 아니다.

## 구현 범위

- `EneApplication`이 연결 Repository 하나를 소유한다. ProcessLifecycleOwner의 전경/배경 전환으로 연결을 열고 회수한다. 화면은 저장소를 새로 만들지 않는다. 회전과 실제 배경 전환의 차이는 [Android 공식 수명 문서](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner)를 따르며 실제 단말 동작은 아래 미검증 항목이다.
- QR의 PC 전용 CA로 HTTPS 후보를 확인하고 WSS를 연다. PC 승인 전 토큰 저장은 없다. 승인 후 주소와 암호화 등록 저장이 끝나야 일반 연결을 연다. 이미 시작한 동기 저장은 취소 요청 후에도 실제 작업 종료까지 기다린다.
- 전체 스냅샷은 조립·해시·내용 검증이 끝난 뒤 한 번에 교체한다. 큰 본문 해석은 화면 스레드 밖에서 수행한다. 부분 수신 중에는 전송하지 않으며 기존 완성 대화는 오래된 내용임을 표시한다.
- 이벤트 번호 누락은 전체 재동기화, 중복은 무시한다. PC reset과 전송이 겹쳐 `sync_required`가 반환되어도 초안을 보존하며 복구한다.
- callback 큐와 본문 처리 큐는 각각 128개/2 MiB로 합계 256개/4 MiB를 제한한다. 제어 응답은 큰 본문 처리와 분리하되 수신·소비·생존 확인 작업 수는 고정이다. 이는 처리 대기열 상한이며 OkHttp 내부 버퍼와 전체 객체 메모리 상한은 아니다.
- ping 15초/pong 10초, 주소 후보 전환, 단계적 재접속, 첫 스냅샷 무응답 제한, 전송 결과 무응답 시 조회 복구를 연결했다. 승인되지 않은 401과 인증서 오류는 등록을 자동 삭제하지 않는다.
- 초안·대화·미확정 요청은 메모리에만 둔다. `reserved`에서는 초안을 유지하고 `accepted`에서 전송 당시 버전만 비운다. 재접속 후 전체 대화와 미확정 요청을 조회하며, 같은 등록/실행/대화의 `unknown`만 동일 ID·본문으로 한 번 재시도한다. PC가 다른 작업 중이면 idle까지 기다린다.
- CameraX Preview/ImageAnalysis와 번들 ML Kit QR 인식기를 사용한다. 권한은 명시적 버튼으로 요청하고, 유효한 첫 QR 후 분석기를 멈춘다. 실패·취소·정상 처리에서 ImageProxy를 회수한다. [CameraX 분석 수명](https://developer.android.com/media/camera/camerax/analyze), [ML Kit QR 인식 지침](https://developers.google.com/ml-kit/vision/barcode-scanning/android).
- 최소 화면에 연결 상태, TLS/QR 오류 안내, 주소 수정, 등록 해제 확인, 전체 대화 목록, 입력·전송을 연결했다. 48dp 이상 터치 영역과 텍스트 상태 설명을 사용한다. UI 상태에 대화 복원을 넣지 않으며 FLAG_SECURE로 최근 앱 캡처 노출을 제한한다.

## 자동 검증

| 항목 | 결과 |
| --- | --- |
| JVM 전체 | 93개 통과, 실패·오류·제외 0 |
| 새 연결 Repository | 24개: 승인/보관 실패, 전경, 원자 동기화, 순서 누락, heartbeat, 초안/접수, 조회 재시도, 주소/등록 복구, 실제 IO 저장 중 취소, 늦은 decode 폐기 |
| 새 교환·QR·정적 정책 | 9개: 종료 직전 프레임, EOF 후 heartbeat 취소, 상한 초과, 느린 소비 중 제어 응답, QR 일회 수락/만료/취소, 화면 비보관·권한 요청 정책 |
| Lint | 오류 0, 경고 1: 앱 아이콘 미지정. 출시 화면 정리 단계에서 처리 |
| 개발 APK / 계측 APK | 둘 다 빌드 성공. 계측 코드 8개(Keystore 6, 권한 설명 UI 2)는 컴파일만 확인 |
| 의존성 | 오프라인 strict 해시·잠금 검증 성공. 기존 해시 변경 0. CameraX/ML Kit 공식 의존성 추가 |
| 산출물 경로 검사 | 앱 APK에 시험 CA·개인키·등록/대화 파일 없음. 계측 APK에 생성한 공개 CA만 존재 |

집중 리뷰의 두 복구 결함은 실패 시험으로 재현 후 수정했다. 추가 시험에서 중간 큐가 최종 응답을 버리고 `slow_consumer` 원인을 덮는 문제도 재현·수정했다. 통합 빌드에서 드러난 Lint의 시험 인증서 생성 의존성 누락을 수정하고 같은 전체 명령으로 재검증했다.

최종 통합 리뷰에서는 EOF 후 heartbeat가 마지막 해석 작업을 취소할 수 있는 경계를 추가로 재현했다. EOF를 확인하는 순간 해당 timer를 취소하도록 수정하고 전체 검증을 다시 통과했다. 읽기 전용 리뷰와 단위 검증만으로 실제 단말 검증을 대신하지 않는다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --dependency-verification strict --console=plain
```

개발 APK: `app/build/outputs/apk/debug/app-debug.apk`, 약 36.9 MiB. 출시 서명물이 아니다. 확인한 SHA-256:

`3C9862D5799FE0EC078CF2BE2CE7BA6BA2D4DE9E554FAB1C4753C865323B56A5`

## 아직 확인하지 않은 것

- 실제 Android 카메라/OS 권한 거절 및 설정 복구, QR 인식 거리·속도, UI 배치·큰 글꼴·키보드·회전, 실제 프로세스 종료 후 비복원.
- 실제 Android Keystore 동작과 백업/기기 전송 제외, APK 설치, PC와 휴대폰의 같은 LAN 왕복·무응답 단절·재접속.
- PC 실제 ENE 대화·AI 접수·트레이/종료 연동. 현재 상대편 조기 시험은 합성 응답 전용 smoke 도구다.
- 출시 아이콘·서명·배포, GitHub 게시, LTE/5G·Tailscale, TTS·Live2D.

단위 시험은 카메라와 휴대폰 시험을 대체하지 않는다. 단말 선택과 설치 동의를 받은 뒤 [설치·LAN 확인 절차](build-and-install.md)를 진행한다. 방화벽 변경과 외부 API 호출도 별도 확인 없이 수행하지 않는다.
