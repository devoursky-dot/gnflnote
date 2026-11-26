# GNFLNOTE (경남외국어고등학교 전자칠판 판서 앱 — V2.0)

4K(`3840×2160`) 교실 전자칠판 환경에 최적화한 맞춤형 화이트보드 확장 모듈 및 **GitHub 초경량 OTA 자동 업데이트** 파이프라인입니다.

> 🤖 **다른 AI 에이전트 / 개발자 인수인계 문서**: 전체 아키텍처, 순정 EasiNote 내부 클래스/필드 리버스 엔지니어링 맵, OTA 배포 가이드는 [ARCHITECTURE_AND_HANDOFF.md](./ARCHITECTURE_AND_HANDOFF.md)를 먼저 읽어주세요.

## 주요 기능 (V2.0)
- **GitHub 초경량 OTA 자동 업데이트 (`UpdateBootstrap`, `scripts/build_ota.ps1`)**
  - Base APK(280 MB) 1회 설치 후, 기능 수정은 **`ota/gnfl_patch.dex` (~193 KB)** 만 GitHub에서 자동 다운로드·SHA-256 검증 후 재실행 시 즉시 반영
  - Android 14+ read-only 보안 정책 준수, 오프라인/오류 시 내장 클래스 자동 폴백
- **PDF 수업 최적화 (`PdfPicker`, `PdfCacheManager`, `PdfRecentSubmenu`, `PdfLinkQrManager`)**
  - USB/내부 저장소 PDF 다중 페이지 선택(FHD/QHD/4K) 및 항상 새 다음 페이지에 삽입
  - `☐ 캐시에 저장` 옵션(기본 해제 = 일회성) + 최대 3 GB LRU 내부 캐시 + 페이지 위치 기억
  - 파일별 **개별 4자리 PIN 잠금/해제** (다른 파일 열기·앱 종료 시 자동 재잠금, 잠금 없는 파일은 길게 눌러 잠금/삭제 선택)
  - `선택` 툴 터치 시 PDF 내부 **하이퍼링크(`/URI`) 및 QR 코드** 자동 인식 연결
- **스마트 펜 & 3배 반투명 형광펜 (`SmartPenManager`, `InkShapeRecognizer`, `QuickColorBarManager`, `DietManager`)**
  - 손그림 직선·원·삼각형·사각형 자동 보정 (`±10°` 스냅 + 각진 모서리)
  - 배경색 자동 대비 **7계열 × 2단계(14색) 퀵 팔레트**
  - **가는펜(A) / 굵은펜(B) 독립 형광펜**: 1배 슬라이더 눈금 유지 + 3배 굵기·순정 반투명·가는 금 0%
- **도형 툴 & 교실 UI 최적화 (`DietManager`, `ToolbarFitHelper`, `LassoCaptureManager`)**
  - 도형 팝업 내 **펜 스타일 선 두께 슬라이더 + 실시간 원형 미리보기** (2D · 3D 입체도형 · 1번째 손모양 스마트 도형 전체 지원)
  - 전체화면에서 `도형` 버튼 자동 접힘 방지 (자유창에서는 `더 보기` 정상 동작)
  - 4K 벡터 **올가미 캡처**, 펜글씨만 삭제, 폭·쪽맞춤, **상단 중앙 터치 통과형 안내 배너**, 경남외고 책 모양 앱 아이콘

## 빌드 및 OTA 배포
- **전체 Base APK 빌드**: `.\build.ps1` → `C:\Work\GNFLNOTE-V2.0.apk`
- **초경량 OTA 패치 빌드 (~193 KB)**:
  ```powershell
  .\scripts\build_ota.ps1 -PatchVersion 202 -PatchName "2.0.2" -Notes "업데이트 설명"
  git add src/ ota/ && git commit -m "OTA v2.0.2" && git push origin main
  ```
