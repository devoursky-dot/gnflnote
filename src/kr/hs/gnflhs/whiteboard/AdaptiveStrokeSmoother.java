package kr.hs.gnflhs.whiteboard;

/**
 * GNFLHS v2.7.0 — 획 완료 시에만 실행하는 8단계 거리 기반 곡선 보정기.
 *
 * 앱의 빠른펜 및 이동 중 렌더링 경로와 완전히 분리되어 있다.
 * 입력 점들의 간격이 일정하지 않은 전자칠판 터치 센서에 대응하기 위해
 * 점 번호가 아닌 필기 궤적의 화면상 호 길이(px)를 기준으로 평균한다.
 *
 * 1단계: 원본 유지. 2단계: 종전 v2.6.0 4단계와 유사한 약한 정리.
 * 5~8단계: 더 넓은 구간의 손떨림을 제거하며, 급한 전환점과 시작/끝은 보호.
 */
public final class AdaptiveStrokeSmoother {
    private AdaptiveStrokeSmoother() { }

    // 반경은 모두 화면 픽셀이며 확대/축소 배율과 독립적으로 느껴지게 보정한다.
    // 기존 4단계(3회 1:2:1 이동 평균)를 새 2단계의 기준으로 재배치한다.
    // 3~8단계는 그 위에 점 간격에 둔감한 공간 기반 보정을 추가한다.
    private static final float[] RADII = {0f, 0f, 0f, 9f, 15f, 24f, 36f, 52f, 74f};
    private static final float[] BLEND = {0f, 0f, 0f, .80f, .88f, .94f, 1f, 1f, 1f};

    public static float[] smooth(float[] raw, int count, float zoom, int level) {
        if (raw == null || count <= 0) return new float[0];
        int n = Math.min(count, raw.length / 2);
        float[] input = new float[n * 2];
        System.arraycopy(raw, 0, input, 0, n * 2);
        if (n < 3 || level <= 1) return input;
        int strength = Math.max(1, Math.min(8, level));
        float scale = Float.isNaN(zoom) || Float.isInfinite(zoom) || zoom < .01f ? 1f : zoom;

        // v2.6.0의 가장 강한 보정 필터를 똑같이 3회 수행한다.
        // 이로써 새 2단계는 기존 최고 4단계와 비교 가능한 기준이 된다.
        float[] baseline = input;
        for (int pass=0;pass<3;pass++) {
            float[] next = baseline.clone();
            for (int i=1;i<n-1;i++) {
                int k=i*2;
                next[k] = (baseline[k-2]+2f*baseline[k]+baseline[k+2])*.25f;
                next[k+1] = (baseline[k-1]+2f*baseline[k+1]+baseline[k+3])*.25f;
            }
            baseline = next;
        }
        if (strength == 2) return baseline;

        // 전체 호 길이는 이미 잔떨림이 줄어든 좌표로 측정한다.
        float[] arc = new float[n];
        for (int i = 1; i < n; i++) {
            float dx = (baseline[2*i] - baseline[2*i-2]) * scale;
            float dy = (baseline[2*i+1] - baseline[2*i-1]) * scale;
            arc[i] = arc[i-1] + (float) Math.hypot(dx,dy);
        }
        float whole = arc[n-1];
        if (whole <= 2f) return baseline;
        // 작은 글씨 한 획이 지나치게 일직선으로 변하지 않도록 획 길이에 따라 제한.
        float radius = Math.min(RADII[strength], Math.max(3f, whole * .17f));
        if (radius < 2f) return baseline;

        float[] current = baseline;
        int passes = strength >= 6 ? 2 : 1;
        for (int pass = 0; pass < passes; pass++) {
            float[] next = new float[n*2];
            next[0] = input[0];
            next[1] = input[1];
            next[2*n-2] = input[2*n-2];
            next[2*n-1] = input[2*n-1];
            int left = 0, right = 0;
            for (int i = 1; i < n-1; i++) {
                while (left < i && arc[i] - arc[left] > radius) left++;
                if (right < i) right = i;
                while (right+1 < n && arc[right+1] - arc[i] <= radius) right++;

                // 5~8단계: 국소 2차 다항식 회귀로 곡률(극대/극소)을 보존하며 강하게 필터링.
                // 3~4단계: 가벼운 거리 가중 평균.
                double m0=0,m1=0,m2=0,m3=0,m4=0;
                double x0=0,x1=0,x2=0,y0=0,y1=0,y2=0;
                for (int j=left; j<=right; j++) {
                    double u = (arc[j]-arc[i]) / radius;
                    double w = 1.0-u*u;
                    if (w <= 0) continue;
                    double u2=u*u;
                    m0+=w; m1+=u*w; m2+=u2*w; m3+=u2*u*w; m4+=u2*u2*w;
                    x0+=current[2*j]*w; x1+=current[2*j]*u*w; x2+=current[2*j]*u2*w;
                    y0+=current[2*j+1]*w; y1+=current[2*j+1]*u*w;
                    y2+=current[2*j+1]*u2*w;
                }
                float fx = m0 > 0 ? (float)(x0/m0) : current[2*i];
                float fy = m0 > 0 ? (float)(y0/m0) : current[2*i+1];
                if (strength >= 5) {
                    double det = m0*(m2*m4-m3*m3) - m1*(m1*m4-m2*m3)
                               + m2*(m1*m3-m2*m2);
                    if (Math.abs(det)>1.e-7) {
                        double q0=m2*m4-m3*m3;
                        double q1=m2*m3-m1*m4;
                        double q2=m1*m3-m2*m2;
                        fx=(float)((x0*q0+x1*q1+x2*q2)/det);
                        fy=(float)((y0*q0+y1*q1+y2*q2)/det);
                    }
                }
                float mix = BLEND[strength];
                // 뚜렷한 방향 전환은 도형의 의미를 가진 꼭짓점일 수 있다.
                // 국소 잡음을 꼭짓점으로 오인하지 않도록 약 15~25 화면px 먼 두 점으로 판정.
                if (strength >= 4) mix *= cornerPreservation(baseline, arc, n, i, scale, radius);
                next[2*i] = current[2*i] + (fx-current[2*i])*mix;
                next[2*i+1] = current[2*i+1] + (fy-current[2*i+1])*mix;
            }
            current = next;
        }
        return current;
    }

    /** 급하게 꺾이는 지점에서는 0.5~1배의 혼합률로 원본 형태를 더 보존한다. */
    private static float cornerPreservation(float[] raw, float[] arc, int n,
                                            int i, float scale, float radius) {
        float reach = Math.max(14f, Math.min(28f, radius));
        int before = i, after = i;
        while (before > 0 && arc[i] - arc[before] < reach) before--;
        while (after < n-1 && arc[after] - arc[i] < reach) after++;
        if (before == i || after == i) return 1f;
        float ax = raw[2*i] - raw[2*before], ay = raw[2*i+1] - raw[2*before+1];
        float bx = raw[2*after] - raw[2*i], by = raw[2*after+1] - raw[2*i+1];
        float m1 = (float)Math.hypot(ax,ay), m2 = (float)Math.hypot(bx,by);
        if (m1 * scale < 5f || m2 * scale < 5f) return 1f;
        float cosine = (ax*bx + ay*by)/(m1*m2);
        if (cosine < .2f) return .48f;  // 78도 이상 급회전
        if (cosine < .57f) return .72f; // 55도 이상 굽음
        return 1f;
    }
}
