# 개발 APK와 조기 LAN 시험

이 앱은 실제 ENE 대화·캐릭터·음성 연결을 구현한 개발 버전이다. 소스 자동 검증을 마쳤지만 실제 휴대폰 설치·LAN 인수는 아직 하지 않았다. 최신 커밋·APK 해시·지원 범위는 [캐릭터·음성 검증 기록](media-support.md)을 참조한다. 아래 합성 응답 시험으로 기본 연결을 먼저 확인한다.

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

## 같은 개인 LAN에서 기본 연결 확인

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

## 이후 실제 ENE 미디어 확인

기본 LAN 확인을 통과한 뒤 별도로 수행한다. PC는 로컬 `main`의 실제 ENE를 실행한다. 사용할 시험 모델의 권리와 AI/TTS 제공자·비용을 먼저 확인하고, 실제 대화가 없는 시험 환경에서 중립 문장만 사용한다.

1. PC에서 동반 앱 서버를 켜고 새 QR로 폰을 승인한다. 현재 대화 동기화 뒤 PC와 폰에서 각각 보내도 접수·응답이 중복되지 않는지 확인한다.
2. PC에서 지원 Live2D 모델을 선택하고 폰 표시·표정·모션·입 모양을 확인한다. 공통 설정을 양쪽에서 조정하고 충돌·취소·모델 교체 뒤 최신 값이 유지되는지 검사한다.
3. 쓰다듬기를 PC/폰에서 각각 시도하고 정상 종료당 최대 한 번 기록되는지 확인한다. 동시에 시작하거나 회전·단절해도 중복 기록이 생기지 않아야 한다.
4. 전경 폰의 음성 출력, 홈/잠금·통화·블루투스·오디오 포커스를 확인한다. 폰 재생 시작 뒤 단절된 발화는 PC에서 다시 나오지 않고, 다음 발화부터 자동 선택되는지 구분해 기록한다.
5. 등록 해제·재등록 뒤 이전 모델 캐시가 제거되는지, 새 연결의 모델을 다시 받는지 확인한다. 삭제 오류가 나면 앱을 다시 열고 재시도하며 앱 데이터 초기화로 우회하지 않는다.

계측 시험의 캐릭터·음성 코드는 빌드만 확인했다. 실제 WebGL·AudioTrack·터치·발열 검증은 수동 인수와 함께 별도로 실행한다. 자동 검사 성공을 실제 소리·화면의 성공으로 기록하지 않는다.

## 현재 결과

2026-09-17: 설치·실제 LAN·계측 실행 모두 **미검증**. PC 코드 기준은 `a66b9239`, Android 코드 기준과 APK 해시는 [지원 기록](media-support.md)에 고정했다. 이후 시험 결과에는 OS/API·앱 버전·두 저장소 커밋과 성공/실패만 기록하며 기기 식별자와 대화 원문은 기록하지 않는다.
