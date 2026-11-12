# Pleos Connect 앱

초보, 고령, 색각이상 운전자를 위한 음성 우선 운전 보조 앱.

## 실행
1. Android Studio에서 이 폴더 열기 → Sync
2. Pleos Connect 에뮬레이터(API 34) 켜고 ▶ Run
3. 처음 화면에서 운전자 프로필 고르기 (색 구분 테스트 있음)

## 기능
| 기능 |위치 |
|---|---|
| 운전자 프로필 3종 + 색 구분 테스트 | 첫 화면 / 설정 |
| 깜빡이 장시간 (1분, 시연 10초) | engine/AlertEngine |
| 밝은데 상향등 (5분, 시연 20초) · 밤에 전조등 꺼짐 | engine/AlertEngine |
| 문 · 트렁크 열고 주행 | engine/AlertEngine |
| 연료 예측 (남은 경로 > 주행 가능 거리) + 경로상 주유소 [예시] | engine/AlertEngine, data/Prefs |
| 브레이크 습관 (세게 · 늦게 · 여러 번) → 멈춘 뒤에만 한마디 | engine/BrakeCoach |
| "방금 뭐 켜졌어?" (버튼 · 음성) | AppController.askWhatsOn |
| 주차 요약 (기어 P): 내일 비·눈 [예시] × 열린 창문, 가족 요약 공유 | engine/ParkingSummary |
| "사고 났어" 단계 가이드, 차 신호로 단계 자동 확인, 119 · 1588-2504 · 보험사 | engine/AccidentGuide |

## 테스트 신호 목록
주행 화면 아래 **테스트 신호 목록**.
- 음성 점검: 말하기 · 듣기 · 지도 위 카드가 되는지 ✓/✕로 표시
- "테스트 신호 쓰기"를 켜면 가짜 차 신호로, 끄면 실제 차(가상 콘솔) 신호로 동작
- 상황 바로 만들기: 깜빡이 켠 채로 주행 / 낮인데 상향등 / 밤인데 라이트 꺼짐 / 뒷문 열린 채 출발 / 기름 부족 / 창문 열고 주차 직전(→ P 누르면 주차 요약)

## 지도 위 알림 카드
앱 화면을 닫고 지도로 가면, 알림이 지도 왼쪽 위에 작은 카드로 뜨고 음성으로도 말하는 기능 구현
'다른 앱 위에 표시' 권한이 필요. 차 화면에 설정 메뉴가 없으면 Android Studio 아래 Terminal 탭에서:

    # 차(AAOS)는 운전자 사용자 번호가 0이 아니라 보통 10이라 --user를 붙임
    ADB=~/Library/Android/sdk/platform-tools/adb
    $ADB shell appops set --user $($ADB shell am get-current-user) com.signallens.app SYSTEM_ALERT_WINDOW allow

## 소리 · 마이크가 안 될 때
1. 에뮬레이터 옆 ⋯ (Extended controls) → Microphone → "Virtual microphone uses host audio input" 켜기
2. macOS 시스템 설정 → 개인정보 보호 및 보안 → 마이크 → Android Studio 허용
3. 음성 엔진 확인 (Terminal):
       ~/Library/Android/sdk/platform-tools/adb shell pm list packages | grep -iE "tts|speech|voice|google"

- 브레이크: 급정거 · 늦게 꾹 · 여러 번 밟기를 2번 하면(시연 기준) 멈춘 뒤 코칭

## 차 신호 연결
- `vehicle/PleosAutoSource.kt`: Pleos Vehicle SDK 2.3.2의 register 함수를 자동으로 찾아 구독
- `vehicle/CarPropertySource.kt`: AAOS 표준 속도 · 기어 · 주야간 · 주행 가능 거리
- Logcat에서 `SignalLens` 태그로 구독 목록과 들어온 값을 볼 수 있음

## 아직 예시인 것
- 주유소 가격: 오피넷 오픈API 키 필요 → `StationProvider` 구현
- 내일 날씨: 기상청 단기예보(공공데이터포털) 키 필요 → `DemoForecast` 교체
- 계기판 경고등(Telltale) · 타이어 공기압 API는 공식 샘플에서 비공개라 사용 안 함
- 음성: Pleos TTS/STT가 "준비 중"이라 안드로이드 기본 TTS · 음성 인식 사용

## 테스트
`app/src/test/.../EngineTest.kt` 왼쪽 → 에뮬레이터 없이 규칙 11개 검사

## 실시간 로그
    ~/Library/Android/sdk/platform-tools/adb logcat -s SignalLens
[음성] 들은 말, [말함] 앱이 한 말, 차 신호가 실시간으로 나와요. 앱 화면에도 음성 로그 출력하여 확인.
