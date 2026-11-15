# 훈수 VAR 기능 현황 (2026-11-15)

현재 코드 기준입니다. 판정 기준값과 화면 문구는 [README의 "무엇을 판정하나"](../README.md#무엇을-판정하나)를 따릅니다.

- 상태: **완료**, **부분**(이유를 메모에 적음), **불가**(이유를 메모에 적음)
- 근거: 파일과 함수 또는 테스트 이름
- 테스트: `./gradlew :core:test` 전부 통과 (2026-11-15)
- 시연 리플레이: 실제 주행 기록이 아니라, 여러 장면을 7분 주행 하나에 모아 만든 임의 주행입니다([README 3(b)](../README.md#3-시연)).

## 1. 동승자 판정 (끼어들기 · 주차 · 길 선택)

| 기능 | 상태 | 근거 | 메모 |
|---|---|---|---|
| "아까 들어갔어야지!" | 부분 | `Verdict.kt` judgeGap, mergeAtMs · `LaneChangeTest` | 깜빡이를 켠 쪽의 후측방 경고(LCA)가 3초 이상 꺼진 틈이 있었는지로 판정합니다. 틈은 핸들이 깜빡이를 켤 때보다 3° 이상 움직여 들어가기 시작하기 전까지만 셉니다. 에뮬레이터는 LCA 값을 넣을 수 없어서 시연 리플레이로만 확인했습니다. |
| "차! 차!" | 부분 | `Verdict.kt` judgeLaneChange · `LaneChangeTest` | 깜빡이 쪽 LCA가 켜진 채 핸들을 5° 이상 꺾었으면 적중, 아니면 판독 불가입니다. 확인 방법은 위와 같습니다. |
| 빠질 길을 놓쳤는지(내비) | 부분 | `Detector.kt` exitAheadM · `Verdict.kt` exitOutcome · `LaneChangeTest` | 내비 다음 안내가 1 km 안에 있는 깜빡이 쪽 출구·분기·회전일 때만 빠질 길로 봅니다(터널·톨게이트·직진 안내 제외). 에뮬레이터에서 NaviHelper 값을 만들 수 없어 시연 리플레이로만 확인했습니다. |
| 주차 훈수 2가지 | 완료 | `Parking.kt` · `ParkingTest` | 에뮬레이터에 실제 신호를 넣어 확인했습니다: "핸들 똑바로 잡으라니까!" 마지막 후진 40° 적중, "거기 못 들어가!" 2번 고쳐 댐 판독 불가. 시연 리플레이에서는 "핸들 똑바로 잡으라니까!"가 ±12° 기각입니다. |
| "이 길로 오면 돌아가잖아!" | 부분 | `Verdict.kt` judgeRoute · `RouteChoiceTest` | 경로 재탐색 전후의 내비 예상 도착 시각을 비교합니다. 에뮬레이터에서 NaviHelper 값을 만들 수 없어 시연 리플레이로만 확인했습니다. |
| 정차 중 한 줄 판정 | 완료 | `Quick.kt` · `QuickTest` | 빠질 길 장면에만 나옵니다. 차속을 받고 있고 시속 1 km 미만일 때만 보이고, 차가 움직이면 바로 사라집니다. 근거 문장은 주차한 뒤에 보여 줍니다. |

## 2. 기본 흐름

| 기능 | 상태 | 근거 | 메모 |
|---|---|---|---|
| 주행 중 화면 | 완료 | `Screens.kt` Recording · `AndroidManifest.xml` distractionOptimized | 깜이 얼굴과 '훈수!' 버튼만 나옵니다. 혼자 모드는 버튼 없이 안내 한 줄만 나옵니다. 스스로 움직이는 연출은 없고, 실제 깜빡이에 맞춰 깜이의 깜빡이 램프만 켜집니다. |
| '훈수!'로 장면 찍기 | 완료 | `MarkTest`, `LookbackTest` | 누른 순간 앞 20초 안의 장면에 붙습니다. 끼어들기는 5분, 길 선택은 10분 뒤에 누른 훈수까지 붙습니다. |
| P단에서 오늘의 판독 후보 | 완료 | `Detector.kt` detectCandidates · `RecorderTest` | 주행 기록은 이번 주행만 남깁니다. P에서 출발하면 그 전 주행의 기록과 훈수는 지웁니다. |
| 훈수 고르기 → 판독 중 → 판정 | 완료 | `Screens.kt` ClaimPicker, Judging, VerdictCard | 판독 중은 1.5초이고 판정 기준 세 개가 차례로 켜집니다. |
| 판정 3종과 신뢰도 | 완료 | `Verdict.kt` · `VerdictTest` | 훈수 적중 / 훈수 기각 / 판독 불가 |
| 이의 있어요 · 둘 다 인정 | 완료 | `Record.kt` Excuse, agreed · `RecordTest` | 진 쪽이 사정 5개 중 하나를 고릅니다. |
| 같은 장면 다시 판독 | 완료 | `Record.kt` Records.withJudged · `RecordTest` | 같은 장면을 같은 훈수로 다시 판독하면 앞의 기록을 바꿉니다(한 번만 셈). |
| 공유 카드 | 완료 | `Verdict.kt` shareHeadline · `SoloShareTest` | 위치·시각·속도는 넣지 않습니다. 에뮬레이터에서는 이미지를 받는 앱이 인쇄 서비스 하나뿐입니다. |

## 3. 시즌 기록 · 혼자 모드

| 기능 | 상태 | 근거 | 메모 |
|---|---|---|---|
| 4주 시즌, 운전자 무죄율 · 훈수꾼 적중률 | 완료 | `Record.kt` · `RecordTest` | |
| 깜빡이 습관, 이번 주 명판정, 깜이의 규칙 노트 | 완료 | `Record.kt` · `RecordTest` | 규칙 노트는 판정이 아니라 기록입니다(회전 깜빡이 30 m, 과속, 비 올 때 전조등, 벨트). |
| 도로연수 졸업 리포트 (100일) | 완료 | `Record.kt` Graduation · `RecordTest` | |
| 혼자 모드 연습 판정 | 부분 | `Verdict.kt` · `VerdictTest`, `BrakeStopTest`, `LegalPointTest` | 회전 깜빡이, 급제동·브레이크, 비 올 때 전조등, D 정차는 에뮬레이터에서 확인했습니다. 과속·스쿨존은 NaviHelper 값이 필요해 시연 리플레이로만 확인했습니다. 벨트는 에뮬레이터가 늘 "미착용"을 보내서 앱이 "모름"으로 두므로 후보가 생기지 않습니다. |

## 4. 개인정보 · 안전

| 기능 | 상태 | 근거 | 메모 |
|---|---|---|---|
| 데이터는 기기 안에만 | 완료 | `MainActivity.kt` (SharedPreferences), `SignalRecorder`(메모리) | 서버로 보내지 않습니다. |
| 운전자 · 훈수꾼 동의 | 완료 | `Screens.kt` Consent · `MainActivity.kt` | 운전자 동의를 끄면 쌓인 주행 기록과 판정·시즌 기록을 바로 지웁니다. |
| 손상된 기록 | 완료 | `Record.kt` recordsFromJson · `RecordTest` | 저장된 기록을 읽지 못하면 앱이 멈추지 않고 빈 기록으로 시작합니다. |
| 주행 중 CPU · RAM | 완료 (측정) | README 측정표 | 측정은 11/9 수정 전 릴리즈 빌드로 했고, 수정 뒤에는 다시 재지 않았습니다. |

## 5. 실차에서 확인할 것

- `PRIVATE_CAR_SAFETY` 권한이 허용되는지, LCA_LEFT·LCA_RIGHT 텔테일이 실제로 오는지
- NaviHelper TBT 거리 단위(m로 가정)와 목록의 첫 항목이 다음 안내인지, `remainTime` 단위(초로 가정)
- 아주 느린 속도(시속 1~3 km)에서 차속 값이 0으로 떨어지는지
- 판정 기준값(`Thresholds`): 특히 차로 변경 시작으로 보는 핸들 3°
