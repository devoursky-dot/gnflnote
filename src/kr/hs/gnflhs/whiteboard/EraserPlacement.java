package kr.hs.gnflhs.whiteboard;

/**
 * [v2.8.0] 좌표 계산 전용. Android UI/그림 엔진과 독립되어 단위 테스트 가능.
 * MotionEvent.getTouchMajor()는 타원 접촉의 장축 길이이며 '정확한 상단 좌표'가 아니다.
 * 넓은 접촉에 한해 접촉 중심으로부터 상단을 근사한다.
 */
final class EraserPlacement {
    private EraserPlacement() { }

    static float offsetY(boolean palmTopEnabled, boolean broadContact,
                         float touchMajorPx, float touchSize,
                         float eraserDiameterPx, float screenShortSidePx) {
        if (!palmTopEnabled || !broadContact) return 0f;
        float diameter = touchMajorPx;
        if (Float.isNaN(diameter) || Float.isInfinite(diameter) || diameter < 35f) {
            // 일부 IR 드라이버는 Size만 알려 준다. 실측 손바닥 접촉 폭 수준으로 대체.
            diameter = Math.max(35f, screenShortSidePx * 0.064f);
        }
        // 터치 센서의 비정상적으로 큰 추정치를 잘라 갑작스러운 장거리 삭제를 막는다.
        float maxPalm = Math.max(35f, Math.min(140f, screenShortSidePx * 0.14f));
        diameter = Math.max(35f, Math.min(maxPalm, diameter));
        float margin = Math.max(6f, screenShortSidePx * 0.008f);
        return -(diameter * 0.5f + eraserDiameterPx * 0.5f + margin);
    }

    static float eraserY(float touchY, float offsetY, float eraserDiameterPx, float viewHeightPx) {
        if (offsetY >= 0f || viewHeightPx <= 0f) return touchY;
        float radius = eraserDiameterPx * 0.5f;
        if (radius * 2f >= viewHeightPx) return viewHeightPx * 0.5f;
        // 화면 위쪽 가장자리에서 지우개 전체가 보이도록 클램프.
        return Math.max(radius, Math.min(viewHeightPx - radius, touchY + offsetY));
    }
}
