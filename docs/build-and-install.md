# 개발 APK와 조기 LAN 시험

이 앱은 PC 합성 응답 도구와 시험할 개발 버전이다. 실제 ENE 통합 완성본이 아니다. 현재 자동 검증과 남은 경계는 [연결 검증 기록](android-connection-validation.md)을 참조한다.

## 빌드

JDK 21, Android SDK 36/Build Tools 35.0.0과 공식 의존성 캐시가 필요하다. 로컬 SDK 주소는 Git에서 제외된 `local.properties`로 관리한다. 버전 잠금과 해시 검증을 끄지 않는다.

```powershell
.\gradlew.bat :app:assembleDebug --offline --dependency-verification strict --console=plain
```

결과는 `app/build/outputs/apk/debug/app-debug.apk`다. APK·빌드 캐시·개인키·등록 파일은 커밋하지 않는다. ENE_APP은 PC ENE와 별도 Git 저장소이며 PC 쪽 코드는 기존 격리 작업 트리에서 유지한다.

## 단말 선택과 설치

사용자가 시험용 Android 단말 하나를 선택하고 설치에 동의한 뒤에만 실행한다. 기존 같은 패키지의 데이터가 있으면 삭제/초기화를 자동 수행하지 않는다. 단말 일련번호·개인 대화·QR·토큰을 문서나 Git에 남기지 않는다.

```powershell
adb devices -l
adb -s <선택한-단말> install -r app/build/outputs/apk/debug/app-debug.apk
```

## 같은 개인 LAN에서 확인

1. PC의 companion 작업 트리에서 `.venv\Scripts\python.exe -m tools.companion_smoke`를 실행한다. 이 도구는 합성 대화와 임시 등록만 쓰며 실제 AI·기억을 호출하지 않는다.
2. PC(유선 가능)와 휴대폰을 같은 개인 LAN에 연결한다. 공유기의 기기 격리/게스트 Wi-Fi는 통신을 막을 수 있다.
3. 앱에서 QR 연결 → 카메라 권한 허용 → PC QR 스캔 → PC에서 명시 승인한다. 승인 전 앱 등록이 생성되지 않는지 확인한다.
4. 새로 만든 중립 시험 문장을 전송한다. 앱 대화와 PC의 가상 응답 계수가 한 번씩만 증가하는지 확인한다. 실제 개인 대화는 시험 자료로 쓰지 않는다.
5. 앱 배경/전경 복귀, 회전, 연결 단절/복구에서 전체 대화·초안·중복 전송 여부를 확인한다. PC를 다시 실행하면 새 실행/등록 상황에 맞게 QR을 재발급할 수 있다.
6. 권한 거절 후 재시도/앱 설정 복구, QR 만료/승인 거절, 주소 후보 실패, 잘못된 인증서 차단, 주소만 수정할 때 신뢰 유지도 확인한다.

방화벽 때문에 막히면 개인 네트워크 프로필·해당 실행 파일/포트·로컬 서브넷만 범위를 정해 사용자에게 확인받는다. 방화벽 전체 해제·공유기 포트 포워딩·Tailscale 설치는 이 시험에 포함하지 않는다.

계측 시험도 선택한 시험 단말에서만 실행한다. 시험 코드는 아직 실제 단말에서 실행하지 않았다.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.ene.companion.TokenStoreTest,dev.ene.companion.PairingPermissionTest --offline --dependency-verification strict --console=plain
```

연결된 단말이 여러 개면 선택을 확정하고 해당 Gradle 실행에만 ANDROID_SERIAL을 지정한다. 권한 설명 UI 시험은 OS 권한 대화상자와 카메라 수동 시험을 대체하지 않는다.

## 현재 결과

2026-09-16: 설치·실제 LAN·계측 실행 모두 **미검증**. PC 코드 기준은 `d3d00149`, Android 기준은 이 문서가 포함된 커밋이다. 이후 시험 결과에는 OS/API·앱 버전·두 저장소 커밋과 성공/실패만 기록하며 기기 식별자와 대화 원문은 기록하지 않는다.
