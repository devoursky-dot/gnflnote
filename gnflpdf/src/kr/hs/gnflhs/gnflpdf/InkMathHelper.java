package kr.hs.gnflhs.gnflpdf;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.SparseIntArray;
import java.util.List;

public final class InkMathHelper {
    private InkMathHelper() {}

    public static Path buildVolumePreservingSpline(float[] raw, int count, List<PointF> outPoints) {
        Path output = new Path();
        if (count <= 0 || raw == null) return output;
        if (count <= 2) {
            output.moveTo(raw[0], raw[1]);
            outPoints.add(new PointF(raw[0], raw[1]));
            for (int i = 1; i < count; i++) {
                output.lineTo(raw[i * 2], raw[i * 2 + 1]);
                outPoints.add(new PointF(raw[i * 2], raw[i * 2 + 1]));
            }
            return output;
        }

        // 1단계: 전체 아크 길이 계산
        float[] cumLen = new float[count];
        cumLen[0] = 0f;
        for (int i = 1; i < count; i++) {
            float dx = raw[i * 2] - raw[(i - 1) * 2];
            float dy = raw[i * 2 + 1] - raw[(i - 1) * 2 + 1];
            cumLen[i] = cumLen[i - 1] + (float) Math.hypot(dx, dy);
        }
        float totalLen = cumLen[count - 1];
        if (totalLen < 10.0f) {
            output.moveTo(raw[0], raw[1]);
            output.lineTo(raw[(count - 1) * 2], raw[(count - 1) * 2 + 1]);
            outPoints.add(new PointF(raw[0], raw[1]));
            outPoints.add(new PointF(raw[(count - 1) * 2], raw[(count - 1) * 2 + 1]));
            return output;
        }

        // 핵심 제어점 리샘플링 (6 ~ 18개, 간격 약 38px)
        int numCtrl = Math.max(6, Math.min(18, (int) (totalLen / 38.0f) + 1));
        float[] cx = new float[numCtrl];
        float[] cy = new float[numCtrl];

        cx[0] = raw[0];
        cy[0] = raw[1];
        cx[numCtrl - 1] = raw[(count - 1) * 2];
        cy[numCtrl - 1] = raw[(count - 1) * 2 + 1];

        int seg = 0;
        for (int k = 1; k < numCtrl - 1; k++) {
            float target = totalLen * k / (numCtrl - 1);
            while (seg < count - 2 && cumLen[seg + 1] < target) {
                seg++;
            }
            float segLen = cumLen[seg + 1] - cumLen[seg];
            float t = (segLen > 1e-4f) ? (target - cumLen[seg]) / segLen : 0f;
            cx[k] = raw[seg * 2] + (raw[(seg + 1) * 2] - raw[seg * 2]) * t;
            cy[k] = raw[seg * 2 + 1] + (raw[(seg + 1) * 2 + 1] - raw[seg * 2 + 1]) * t;
        }

        // 2단계: Taubin λ|μ 수축 방지 스무딩 (10회 반복)
        // λ=+0.45로 잔떨림 제거 후 μ=-0.47로 진폭(볼록함) 복원 -> 궤적이 절대 줄어들지 않음!
        final float lambda = 0.45f;
        final float mu = -0.47f;
        float[] tx = new float[numCtrl];
        float[] ty = new float[numCtrl];

        for (int pass = 0; pass < 10; pass++) {
            // Pass A: λ 스무딩
            tx[0] = cx[0]; ty[0] = cy[0];
            tx[numCtrl - 1] = cx[numCtrl - 1]; ty[numCtrl - 1] = cy[numCtrl - 1];
            for (int k = 1; k < numCtrl - 1; k++) {
                float avgX = 0.5f * (cx[k - 1] + cx[k + 1]);
                float avgY = 0.5f * (cy[k - 1] + cy[k + 1]);
                tx[k] = cx[k] + lambda * (avgX - cx[k]);
                ty[k] = cy[k] + lambda * (avgY - cy[k]);
            }
            // Pass B: μ 팽창 복원
            cx[0] = tx[0]; cy[0] = ty[0];
            cx[numCtrl - 1] = tx[numCtrl - 1]; cy[numCtrl - 1] = ty[numCtrl - 1];
            for (int k = 1; k < numCtrl - 1; k++) {
                float avgX = 0.5f * (tx[k - 1] + tx[k + 1]);
                float avgY = 0.5f * (ty[k - 1] + ty[k + 1]);
                cx[k] = tx[k] + mu * (avgX - tx[k]);
                cy[k] = ty[k] + mu * (avgY - ty[k]);
            }
        }

        // 3단계: Catmull-Rom 큐빅 스플라인 평가 (제어점을 매끄럽게 통과하여 궤적 크기 보존!)
        output.moveTo(cx[0], cy[0]);
        outPoints.add(new PointF(cx[0], cy[0]));

        final int stepsPerSeg = 8;
        for (int i = 0; i < numCtrl - 1; i++) {
            int i0 = Math.max(0, i - 1);
            int i1 = i;
            int i2 = i + 1;
            int i3 = Math.min(numCtrl - 1, i + 2);

            float p0x = cx[i0], p0y = cy[i0];
            float p1x = cx[i1], p1y = cy[i1];
            float p2x = cx[i2], p2y = cy[i2];
            float p3x = cx[i3], p3y = cy[i3];

            for (int s = 1; s <= stepsPerSeg; s++) {
                float t = (float) s / stepsPerSeg;
                float t2 = t * t;
                float t3 = t2 * t;

                float evalX = 0.5f * (
                        (2.0f * p1x) +
                        (-p0x + p2x) * t +
                        (2.0f * p0x - 5.0f * p1x + 4.0f * p2x - p3x) * t2 +
                        (-p0x + 3.0f * p1x - 3.0f * p2x + p3x) * t3
                );
                float evalY = 0.5f * (
                        (2.0f * p1y) +
                        (-p0y + p2y) * t +
                        (2.0f * p0y - 5.0f * p1y + 4.0f * p2y - p3y) * t2 +
                        (-p0y + 3.0f * p1y - 3.0f * p2y + p3y) * t3
                );
                output.lineTo(evalX, evalY);
                outPoints.add(new PointF(evalX, evalY));
            }
        }
        return output;
    }

    public static int extractDominantColor(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        SparseIntArray counts = new SparseIntArray();
        int step = Math.max(1, (int) Math.sqrt((w * h) / 25000.0)); // 최대 ~2.5만 픽셀 고속 샘플링 (0B 추가 할당)

        int bestKey = -1;
        int bestCount = 0;

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = bmp.getPixel(x, y);
                if ((c >>> 24) < 200) continue; // 올가미 외부 투명 픽셀 제외

                int r5 = (Color.red(c) >> 3) & 0x1F;
                int g5 = (Color.green(c) >> 3) & 0x1F;
                int b5 = (Color.blue(c) >> 3) & 0x1F;
                int key = (r5 << 10) | (g5 << 5) | b5;

                int cnt = counts.get(key, 0) + 1;
                counts.put(key, cnt);
                if (cnt > bestCount) {
                    bestCount = cnt;
                    bestKey = key;
                }
            }
        }

        if (bestKey < 0) return Color.WHITE;

        // 최빈 버킷에 속하는 픽셀들의 정확한 평균 RGB 계산 (오차 없는 자연스러운 배경색)
        long sumR = 0, sumG = 0, sumB = 0;
        int matchCount = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = bmp.getPixel(x, y);
                if ((c >>> 24) < 200) continue;
                int r5 = (Color.red(c) >> 3) & 0x1F;
                int g5 = (Color.green(c) >> 3) & 0x1F;
                int b5 = (Color.blue(c) >> 3) & 0x1F;
                int key = (r5 << 10) | (g5 << 5) | b5;
                if (key == bestKey) {
                    sumR += Color.red(c);
                    sumG += Color.green(c);
                    sumB += Color.blue(c);
                    matchCount++;
                }
            }
        }

        if (matchCount <= 0) return Color.WHITE;
        return Color.rgb((int) (sumR / matchCount), (int) (sumG / matchCount), (int) (sumB / matchCount));
    }
}
