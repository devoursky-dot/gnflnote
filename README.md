# GNFLNOTE (경남외국어고등학교 전자칠판 판서 앱 — V1.7)

4K(`3840×2160`) 교실 전자칠판 환경에 최적화한 맞춤형 화이트보드 확장 모듈 및 빌드 파이프라인입니다.

## 주요 기능 (V1.7)
- **PDF 수업 최적화 (`PdfPicker`, `PdfCacheManager`, `PdfRecentSubmenu`, `PdfLinkQrManager`)**
  - USB/내부 저장소 PDF 다중 페이지 선택(FHD / QHD / 4K) 및 항상 새 다음 페이지에 삽입
  - `☐ 캐시에 저장` 옵션(기본 해제 = 일회성 사용) 및 최대 3 GB LRU 내부 캐시 보관
  - 최근 PDF 목록 파일별 **개별 4자리 PIN 잠금/해제** (다른 파일 열기·앱 종료 시 자동 재잠금, 잠금 없는 파일은 길게 눌러 잠금/삭제 선택)
  - 선택 툴(`선택`) 터치 시 PDF 내부 **하이퍼링크(`/URI`) 및 QR 코드 자동 인식** 후 연결 확인 팝업
- **스마트 펜 & 3배 반투명 형광펜 (`SmartPenManager`, `InkShapeRecognizer`, `QuickColorBarManager`, `DietManager`)**
  - 손그림 직선·원·삼각형·사각형 자동 보정 (`±10°` 수평/수직 스냅 + 각진 모서리)
  - 배경색(칠판/흰 PDF) 기반 고대비 **7계열 × 2단계(14색) 퀵 팔레트**
  - **가는펜(A) / 굵은펜(B) 독립 형광펜**: 1배 슬라이더 눈금 유지 + 실제 필기 3배 굵기·순정 반투명·이음새(가는 금) 0%
- **도형 툴 & UI 개선 (`DietManager`, `ToolbarFitHelper`, `LassoCaptureManager`)**
  - 도형 팝업 내 **펜 스타일 선 두께 슬라이더 + 실시간 원형 미리보기** (2D · 3D 입체도형 · 1번째 손모양 스마트 도형 전체 지원)
  - 4K 벡터 재렌더링 **올가미 캡처**, PDF/이미지 보존 **펜글씨만 삭제**, **폭맞춤/쪽맞춤**, **상단 중앙 터치 통과형 안내 배너**

## 디렉터리 구조
- `src/kr/hs/gnflhs/whiteboard/` — 커스텀 Java 소스 코드
- `scripts/` — DEX 동적 후크 패처(`patch_dex.py`), Manifest 버전 패처(`patch_version.py`), APK 패키징(`pack_apk.py`)
- `build.ps1` — 전체 컴파일·멀티DEX 병합·패치·서명 빌드 스크립트

> 참고: 원본 베이스 APK 리소스(`app_root/`), 빌드 산출물(`build_out/`, `*.apk`), 서명 키(`*.keystore`)는 `.gitignore`로 제외되어 있습니다.
