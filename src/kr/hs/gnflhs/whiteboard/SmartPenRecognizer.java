package kr.hs.gnflhs.whiteboard;

/**
 * v2.9.0: 스마트 빠른펜의 '멈춤 시' 분류기. Android 의존성 없음.
 *
 * 중요: 이 계산은 터치 MOVE나 프레임 렌더링이 아니라 pause Runnable에서만 호출한다.
 * 기본값 OFF에서 기존 빠른펜의 64-segment bitmap/tip-prediction 경로를 변경하지 않는다.
 */
final class SmartPenRecognizer {
    enum Action { NONE, LINE, CLOSED, CURVE }

    private SmartPenRecognizer() { }

    static int clampPause(int millis) {
        return Math.max(300, Math.min(600, ((millis + 50) / 100) * 100));
    }

    static Action classify(float[] xy, int count, float zoom) {
        if (xy == null || count < 4 || xy.length < count * 2 || zoom <= 0f)
            return Action.NONE;
        // 닫힌 도형 판단은 직선/곡선보다 먼저 한다. 글씨 O/0에는
        // InkShapeRecognizer의 최소 크기 및 둘레 필터가 적용된다.
        if (InkShapeRecognizer.detectClosed(xy, count, zoom) != null)
            return Action.CLOSED;
        if (InkShapeRecognizer.looksStraight(xy, count, zoom))
            return Action.LINE;
        // 작은 글씨·숫자의 획은 정지해도 원본을 건드리지 않는다.
        if (count < 9) return Action.NONE;
        float x0=xy[0],y0=xy[1];
        float minX=x0,maxX=x0,minY=y0,maxY=y0;
        float length=0f;
        float previousX=x0,previousY=y0;
        for (int i=1;i<count;i++) {
            float x=xy[i*2],y=xy[i*2+1];
            minX=Math.min(minX,x); maxX=Math.max(maxX,x);
            minY=Math.min(minY,y); maxY=Math.max(maxY,y);
            length+=(float)Math.hypot(x-previousX,y-previousY);
            previousX=x; previousY=y;
        }
        float span=(float)Math.hypot(maxX-minX,maxY-minY)*zoom;
        return span >= 76f && length*zoom >= 105f ? Action.CURVE : Action.NONE;
    }

    /** 직선 전체 평행 이동: 두 끝점을 동일 벡터만큼 이동시켜 기울기/길이를 유지한다. */
    static float[] translateLine(float ax, float ay, float bx, float by,
                                 float dx, float dy) {
        return new float[]{ax+dx,ay+dy,bx+dx,by+dy};
    }
}
