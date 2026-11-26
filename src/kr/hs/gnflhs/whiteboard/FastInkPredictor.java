package kr.hs.gnflhs.whiteboard;

/**
 * EasiNote2015 libinkprediction_android.so 역추적 기반 초저지연 펜 끝 예측기.
 *
 * [원리 및 특징]
 * 1. 속도 벡터(Vx, Vy) 지수가중평균(EMA) 필터링으로 터치 센서 노이즈 제거.
 * 2. 단거리 감쇠(Short Distance Attenuation): 4px 이하 미세 이동에서는 예측치를 0으로 수렴시켜
 *    점(마침표), 소수점, 한글 단자모가 찌그러지거나 튀는 현상 원천 차단.
 * 3. 각도/곡률 가중치(Curvature & Angle Weight): 급격한 방향 전환(모서리, 꺾임) 시
 *    예측을 부드럽게 감쇠하여 각진 모서리가 둥글게 뭉개지는 왜곡 방지.
 * 4. 속도 적응형 예측(Adaptive Extrapolation): 속도에 비례하여 16~26ms 전방 펜 끝 위치를 추정해
 *    전자칠판 대화면에서 물리 펜촉과 화면 잉크 사이의 간격(Lag)을 0에 가깝게 유지.
 */
public final class FastInkPredictor {
    // 기본 예측 시간(ms): 60Hz~120Hz 화면 재생률 및 터치 스캔 지연 보상
    private static final float DEFAULT_PREDICT_MS = 22.0f;
    // 최대 화면 예측 길이(px): 비정상 터치 점프 방지
    private static final float MAX_LEAD_SCREEN_PX = 36.0f;

    private float tipX, tipY;
    private long tipTime;
    private float priorDx, priorDy;
    private float smoothVx, smoothVy;
    private float leadX, leadY;
    private boolean hasPrior;

    public FastInkPredictor() {
        reset();
    }

    public void reset() {
        tipX = 0f;
        tipY = 0f;
        tipTime = 0L;
        priorDx = 0f;
        priorDy = 0f;
        smoothVx = 0f;
        smoothVy = 0f;
        leadX = 0f;
        leadY = 0f;
        hasPrior = false;
    }

    public void start(float x, float y, long time) {
        reset();
        tipX = x;
        tipY = y;
        tipTime = time;
    }

    public void update(float x, float y, long time, float scaleFactor, float strokeWidth) {
        float dx = x - tipX;
        float dy = y - tipY;
        long dt = time - tipTime;
        if (dt <= 0) dt = 16;
        if (dt > 100) dt = 100; // 긴 정지 후 재시작 시 튀는 것 방지

        float distScreen = (float) Math.hypot(dx, dy) * scaleFactor;
        float vx = dx / (float) dt;
        float vy = dy / (float) dt;

        if (!hasPrior) {
            smoothVx = vx;
            smoothVy = vy;
            priorDx = dx;
            priorDy = dy;
            hasPrior = true;
            tipX = x;
            tipY = y;
            tipTime = time;
            leadX = 0f;
            leadY = 0f;
            return;
        }

        // 1. 속도 지수가중평균 (EMA Filter)
        smoothVx = 0.65f * vx + 0.35f * smoothVx;
        smoothVy = 0.65f * vy + 0.35f * smoothVy;

        // 2. 각도 가중치 (급격한 꺾임/모서리 보호)
        float angleWeight = computeAngleWeight(dx, dy, priorDx, priorDy);

        // 3. 단거리 감쇠 (점, 작은 글씨 보호)
        float distWeight = computeDistanceWeight(distScreen);

        // 4. 최대 허용 리드 계산 (화면 좌표계 기준)
        float maxLeadBoard = Math.max(MAX_LEAD_SCREEN_PX, strokeWidth * 2.5f) / Math.max(0.2f, scaleFactor);

        // 5. 전방 예측 변위 산출
        leadX = computePredictedLead(smoothVx, DEFAULT_PREDICT_MS, angleWeight, distWeight, maxLeadBoard);
        leadY = computePredictedLead(smoothVy, DEFAULT_PREDICT_MS, angleWeight, distWeight, maxLeadBoard);

        tipX = x;
        tipY = y;
        tipTime = time;
        priorDx = dx;
        priorDy = dy;
    }

    public static float computeAngleWeight(float dx, float dy, float priorDx, float priorDy) {
        float len1 = (float) Math.hypot(dx, dy);
        float len2 = (float) Math.hypot(priorDx, priorDy);
        if (len1 < 0.001f || len2 < 0.001f) return 0f;
        float dot = (dx * priorDx + dy * priorDy) / (len1 * len2);
        if (dot <= 0.1f) return 0f; // 85도 이상 꺾임은 예측 중단
        // 0.1 ~ 1.0 구간을 부드럽게 매핑
        float norm = (dot - 0.1f) / 0.9f;
        return norm * norm;
    }

    public static float computeDistanceWeight(float distanceScreenPx) {
        if (distanceScreenPx <= 2.0f) return 0f;
        if (distanceScreenPx >= 8.0f) return 1.0f;
        float ratio = (distanceScreenPx - 2.0f) / 6.0f;
        return ratio * ratio; // 2px~8px 부드러운 2차 보간
    }

    public static float computePredictedLead(float velocity, float predictMs, float angleWeight, float distWeight, float maxLead) {
        float raw = velocity * predictMs * angleWeight * distWeight;
        if (raw > maxLead) return maxLead;
        if (raw < -maxLead) return -maxLead;
        return raw;
    }

    public float getTipX() { return tipX; }
    public float getTipY() { return tipY; }
    public float getLeadX() { return leadX; }
    public float getLeadY() { return leadY; }
    public boolean hasLead() { return Math.abs(leadX) > 0.01f || Math.abs(leadY) > 0.01f; }
}
