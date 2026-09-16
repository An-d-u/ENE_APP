# 캐릭터 파일과 WebView 경계

확인일: 2026-09-17. C3 기반 구현 기록이며 채팅 화면 연결·기기별 립싱크는 C4, 설정·쓰다듬기 확정 처리는 D 단계다. 아직 `character_v1`을 Android 연결에서 광고하지 않는다. 실제 단말 설치·계측 실행·WebGL/SDK 모델 렌더링 시험은 하지 않았다.

## 파일 받기와 보관

`CharacterRepository.create()`는 Application에서 한 번 생성해 공유한다. 파일은 `noBackupFilesDir/character` 아래에 보관한다. 서버 ID·CA SHA-256·기기 ID·등록 세대를 묶은 해시로 서버를 분리하며 토큰·LAN 주소는 캐시 이름이나 manifest에 넣지 않는다.

현재 연결에서 새 manifest를 받기 전에는 남은 파일을 표시하지 않는다. 스냅샷은 공개 필드만 남기고 모델 버전, 자산 목록, 매개변수 범위, 설정을 검증한다. 목록 256KiB, 파일 32MiB, 모델 합계 128MiB, 256개 파일, PNG/JPEG 한 변 8192픽셀 한도를 적용한다. 이미지 검사는 픽셀 할당 전 헤더 크기 검사이며 실제 SDK 디코딩 성공을 보장하지 않는다.

캐시는 전체 256MiB·2개 버전 이내다. 받기 시작할 때 파일과 manifest의 예상 크기를 예약하므로 `.part`도 한도에 포함된다. 사용 중인 뷰와 열린 읽기 스트림은 캐시 핀을 소유한다. 미사용 버전만 회수하고, 공간이 부족하면 실패한다. 다른 앱의 캐시를 지우거나 OS 저장소 회수를 강제하지 않는다.

파일별 크기·SHA-256을 검사한 뒤 이름을 바꾸고, 전체가 검증되면 manifest를 기록·동기화한 다음 폴더를 원자적으로 활성화한다. 재시작 때도 손상 여부를 확인한다. 취소·연결 세대 변경·모델 교체는 해당 받기 작업과 임시 파일만 정리한다. 삭제는 이름을 확인한 앱 전용 하위 폴더에 한정하며 링크를 따라가지 않는다.

`CharacterMediaTransport`는 현재 WSS의 TLS/CA/서버 이름 검증을 빌리고 네이티브 HTTPS 요청에만 인증 헤더를 넣는다. 전송은 한 번에 한 요청이며 자산을 순차적으로 받는다. 압축·리다이렉트·Range·query·Origin을 사용하지 않고 자동 재시도하지 않는다. 파일 요청은 유휴 10초/전체 60초, 모델 전체는 5분을 제한한다. 취소는 대기 중인 소켓 읽기도 종료한다. 음성 전송 객체와 수명은 분리한다.

## 캐릭터 전용 WebView

출처는 `https://appassets.androidplatform.net`, 문서는 `/character/index.html` 하나다. 내장 스크립트·스타일의 고정 목록과 현재 manifest에 있는 `/models/{model_version}/assets/{asset_id}`만 네이티브가 제공한다. 다른 요청은 403이며 네트워크로 넘기지 않는다. 토큰이나 PC 주소를 JavaScript에 주입하지 않는다.

파일/content 접근, mixed content, 웹 저장소, 서비스 worker 통신, 외부 이동, 새 창, 다운로드, 권한 요청을 막는다. CSP도 적용한다. WebMessageListener 지원 여부, 정확한 출처, 주 프레임, 문서 UUID, 2KiB 메시지 크기와 알려진 유형을 검사한다. 안전한 메시지 API가 없으면 캐릭터만 사용할 수 없게 하고 `addJavascriptInterface`로 대체하지 않는다.

새 문서는 네이티브의 initialize를 받아야 상태를 수락한다. manifest 256KiB에 문서 세대 봉투용 256바이트를 별도로 허용한다. 초기화 무응답은 10초 뒤 실패하고, 렌더러 종료는 스트림·핀·브리지·뷰를 정리해 실패를 알린다. 자동으로 반복 생성하지 않는다. C4 화면이 이 상태를 표시하고 사용자 재시도를 제공한다.

## 실행부 출처와 빌드 고정

PC의 `tools/export_companion_character.py`가 명시 목록 20개와 해시·고지를 확인해 `app/src/main/assets/character`로 복사한다. 함께 보관한 `import-manifest.json`으로 독립 앱 checkout에서도 목록과 SHA-256을 검사한다. 앱 쪽 복사본을 직접 수정하지 않고 PC 원본 변경 후 내보낸다. 모델·일반 설정·대화 파일은 내보내지 않는다.

Pixi 7.3.0, pixi-live2d-display 0.4.0-cubism4, 고정 Cubism Core의 출처·원문 고지는 가져오기 manifest와 `notices/`에 있다. 기존 PC 조합을 유지했으며 실제 기기의 WebGL 호환성과 공개 배포 권리 검토는 별도 확인 사항이다. SDK/모델 재배포가 자동 승인됐다는 의미가 아니다.

WebKit은 계획대로 1.15.0으로 고정했다. [공식 변경 이력](https://developer.android.com/jetpack/androidx/releases/webkit#1.15.0)과 [안전한 메시지 API](https://developer.android.com/reference/androidx/webkit/WebViewCompat)를 기준으로 사용한다. 기존 잠금·해시는 바꾸지 않고 아래 신규 파일만 추가했으며 Google Maven HTTPS 원본을 다시 읽어 SHA-256을 대조했다.

| 공식 Maven 파일 | SHA-256 |
| --- | --- |
| `androidx/webkit/webkit/1.15.0/webkit-1.15.0.aar` | `241c90aac937b6562592976da5cc73100538bf255000f490ed37296da1773960` |
| `androidx/webkit/webkit/1.15.0/webkit-1.15.0.module` | `c5d34727dfb45d6df66faf1d74927a7f118808a48ef44d0d9f33ab8d1c8df4fe` |
| `androidx/annotation/annotation-experimental/1.3.0/annotation-experimental-1.3.0.module` | `5eebeaff01d042e06dcf292abf8964ad391e4b0159f0090f16253d6045d38da0` |

## 검증 명령

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --dependency-verification strict --console=plain
```

JVM 시험은 합성 manifest/바이트, 임시 폴더, 로컬 TLS 시험 서버, 정책 검사만 사용한다. 계측 테스트 2개는 컴파일만 했고, 실제 캐릭터 표시·손가락 입력·회전·접근성·GPU/음성 동작은 단말 시험 전까지 미검증이다. 빌드 산출물은 Git에 포함하지 않는다.

최종 실행: 전체 JVM 34개 클래스/188개 시험 통과, 실패·오류·제외 0. 이 중 새 캐릭터 시험은 31개다. offline strict Lint/debug APK/계측 APK 빌드도 통과했다. Lint는 오류 0/경고 27개이며 기존 의존성 갱신·아이콘 안내와 보수적인 남은 공간 조회·KTX 사용 제안이다. API 지원 검사 경고는 명시적 검사로 해소했다. 원본 Pixi 파일에 있는 공백 4곳은 출처 해시 보존을 위해 수정하지 않았으며 그 외 변경분의 공백 검사는 통과했다. 가져온 20개 파일은 스테이징한 Git 바이트까지 원본과 일치하고 UTF-8 BOM이 없다.
