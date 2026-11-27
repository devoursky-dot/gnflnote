package kr.hs.gnflhs.gnflpdf;

import java.util.ArrayList;
import java.util.List;

/**
 * GNFL NOTE V2.0 - 보정펜 공통 도형 판별기 (원 / 타원 / 삼각형 / 사각형 / 직선).
 * Android 의존성이 없는 순수 기하 연산.
 * - PCA 주축 피팅 기반 원/타원 자동 타협 보정 (종횡비 <= 1.25 원, > 1.25 타원, ±12° 수평/수직 스냅)
 * - 꼭짓점(꺾임) 검출 기반 삼각형(3꼭짓점) 및 사각형(4꼭짓점) 판별 지원
 * - 사각형은 직각에 가까우면 반듯한 직사각형(수평/수직 정렬 포함),
 *   사다리꼴/평행사변형처럼 의도적으로 꺾은 경우 4개 꼭짓점 그대로 유지
 */
final class InkShapeRecognizer {
    enum Kind { CIRCLE, ELLIPSE, TRIANGLE, RECTANGLE }

    private static final float CIRCLE_ELLIPSE_ASPECT_THRESHOLD = 1.25f;
    private static final float MAX_ELLIPSE_ASPECT = 5.5f;
    private static final double ELLIPSE_AXIS_SNAP_DEG = 12.0;

    static final class Shape {
        final Kind kind;
        final float centerX, centerY, radius;
        final float radiusX, radiusY, angleRad;
        final float[] corners; // 다각형 꼭짓점 [x0, y0, x1, y1, ...]
        final float confidence;

        private Shape(Kind type, float cx, float cy, float r, float[] vertices, float score) {
            kind = type;
            centerX = cx;
            centerY = cy;
            radius = r;
            radiusX = r;
            radiusY = r;
            angleRad = 0f;
            corners = vertices;
            confidence = score;
        }

        private Shape(Kind type, float cx, float cy, float rx, float ry, float angle, float score) {
            kind = type;
            centerX = cx;
            centerY = cy;
            radius = (rx + ry) * 0.5f;
            radiusX = rx;
            radiusY = ry;
            angleRad = angle;
            corners = null;
            confidence = score;
        }
    }

    private static final class EllipseFit {
        float cx, cy, rx, ry, angleRad;
        float aspect, normError, perimeterRatio;
        boolean valid;
    }

    private static final float PI = (float) Math.PI;
    private InkShapeRecognizer() { }

    static Shape detectClosed(float[] xy, int count, float scale) {
        if (xy == null || count < 9 || scale <= 0) return null;

        float sx = xy[0], sy = xy[1];
        float minX = sx, maxX = sx, minY = sy, maxY = sy;
        float length = 0f;
        int farthest = 0;
        float farthestSq = 0f;
        for (int i = 0; i < count; i++) {
            float x = xy[i * 2], y = xy[i * 2 + 1];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            float fromStart = (x - sx) * (x - sx) + (y - sy) * (y - sy);
            if (fromStart > farthestSq) { farthestSq = fromStart; farthest = i; }
            if (i > 0) length += (float) Math.hypot(x - xy[(i - 1) * 2], y - xy[(i - 1) * 2 + 1]);
        }

        float width = maxX - minX, height = maxY - minY;
        float shortSide = Math.min(width, height);
        float diag = (float) Math.hypot(width, height);
        float farthestDistance = (float) Math.sqrt(farthestSq);

        // 작은 숫자 0, 괄호, 첨자, 손가락 흔들림을 도형으로 오인하지 않는다.
        if (shortSide * scale < 42f || farthestDistance * scale < 64f || length * scale < 160f) return null;

        float ex = xy[(count - 1) * 2], ey = xy[(count - 1) * 2 + 1];
        float closure = (float) Math.hypot(ex - sx, ey - sy) * scale;
        float closureTolerance = Math.max(24f, Math.min(95f, shortSide * scale * 0.28f));
        if (closure > closureTolerance) return null;

        // 1. 원(Circle) / 타원(Ellipse) 후보 평가 (PCA 주축 피팅 + 원/타원 자동 타협)
        float fx = xy[farthest * 2], fy = xy[farthest * 2 + 1];
        float cx = (sx + fx) * 0.5f, cy = (sy + fy) * 0.5f;
        float radius = farthestDistance * 0.5f;
        float circleError = 0f;
        for (int i = 0; i < count; i++) {
            float x = xy[i * 2], y = xy[i * 2 + 1];
            circleError += (float) Math.abs(Math.hypot(x - cx, y - cy) - radius);
        }
        circleError = circleError / count / Math.max(1f, radius);
        float circlePerimeter = 2f * PI * radius;
        float circleRatio = length / Math.max(1f, circlePerimeter);

        EllipseFit efit = fitEllipseInternal(xy, count, length);
        boolean curveOk = false;
        boolean curveIsEllipse = false;
        float curveError = Float.POSITIVE_INFINITY;
        float curveCx = cx, curveCy = cy, curveR = radius;

        if (efit.valid && efit.ry * scale >= 18f && efit.aspect <= MAX_ELLIPSE_ASPECT
                && efit.perimeterRatio >= 0.73f && efit.perimeterRatio <= 1.45f) {
            if (efit.aspect <= CIRCLE_ELLIPSE_ASPECT_THRESHOLD) {
                // 장단축 비율이 1.25 이하: 사용자가 원을 의도한 것으로 타협 -> 정원(Circle)으로 보정
                boolean strictCircle = circleError <= 0.115f && circleRatio >= 0.73f && circleRatio <= 1.43f;
                if (strictCircle || efit.normError <= 0.125f) {
                    curveOk = true;
                    curveIsEllipse = false;
                    curveCx = efit.cx;
                    curveCy = efit.cy;
                    curveR = (efit.rx + efit.ry) * 0.5f;
                    curveError = Math.min(circleError, efit.normError);
                }
            } else if (efit.normError <= 0.125f) {
                // 장단축 비율이 1.25 초과 ~ 5.5 이하: 의도적 타원(Ellipse)으로 보정
                curveOk = true;
                curveIsEllipse = true;
                curveError = efit.normError;
            }
        } else if (circleError <= 0.115f && circleRatio >= 0.73f && circleRatio <= 1.43f
                && Math.max(width, height) / Math.max(1f, shortSide) <= CIRCLE_ELLIPSE_ASPECT_THRESHOLD) {
            curveOk = true;
            curveIsEllipse = false;
            curveError = circleError;
        }

        // 2. 꺾임(꼭짓점) 기반 삼각형 / 사각형 후보 검출
        float[] triCorners = null;
        float triError = Float.POSITIVE_INFINITY;
        float[] quadCorners = null;
        float quadError = Float.POSITIVE_INFINITY;

        float[] epsRatios = new float[] { 0.048f, 0.062f, 0.078f, 0.095f };
        for (float epsRatio : epsRatios) {
            float[] verts = extractClosedPolygonVertices(xy, count, diag * epsRatio, shortSide);
            if (verts == null) continue;
            int n = verts.length / 2;
            if (n == 3) {
                float err = evaluatePolygonFit(xy, count, verts, shortSide, length);
                if (err < triError && hasDistinctCorners(verts, 145f, 2)) {
                    triError = err;
                    triCorners = verts;
                }
            } else if (n == 4) {
                if (isConvexQuad(verts)) {
                    float err = evaluatePolygonFit(xy, count, verts, shortSide, length);
                    if (err < quadError && hasDistinctCorners(verts, 148f, 3)) {
                        quadError = err;
                        quadCorners = verts;
                    }
                }
            }
        }

        // 3. 기존 회전 직사각형 피팅 후보 (꼭짓점이 약간 둥글게 그려진 직사각형 보조)
        float rotRectError = Float.POSITIVE_INFINITY;
        float[] rotRectCorners = null;
        for (int a = 0; a < 18; a++) {
            double angle = a * Math.PI / 36.0;
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float lowU = Float.POSITIVE_INFINITY, highU = Float.NEGATIVE_INFINITY;
            float lowV = Float.POSITIVE_INFINITY, highV = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                float x = xy[i * 2], y = xy[i * 2 + 1];
                float u = x * cos + y * sin, v = -x * sin + y * cos;
                lowU = Math.min(lowU, u); highU = Math.max(highU, u);
                lowV = Math.min(lowV, v); highV = Math.max(highV, v);
            }
            float du = highU - lowU, dv = highV - lowV;
            if (Math.min(du, dv) * scale < 42f || Math.max(du, dv) / Math.max(1f, Math.min(du, dv)) > 7f) continue;
            float perimeter = 2f * (du + dv);
            if (length / perimeter < 0.75f || length / perimeter > 1.46f) continue;

            int stride = Math.max(3, count / 26);
            int aligned = 0, observed = 0;
            for (int i = stride; i < count - stride; i += Math.max(1, stride / 2)) {
                float tx = xy[(i + stride) * 2] - xy[(i - stride) * 2];
                float ty = xy[(i + stride) * 2 + 1] - xy[(i - stride) * 2 + 1];
                float tlen = (float) Math.hypot(tx, ty);
                if (tlen < 1f) continue;
                float uDir = (tx * cos + ty * sin) / tlen;
                float vDir = (-tx * sin + ty * cos) / tlen;
                if (Math.max(Math.abs(uDir), Math.abs(vDir)) >= 0.94f) aligned++;
                observed++;
            }
            if (observed < 8 || aligned < observed * 0.56f) continue;

            float error = 0f;
            int[] edges = new int[4];
            for (int i = 0; i < count; i++) {
                float x = xy[i * 2], y = xy[i * 2 + 1];
                float u = x * cos + y * sin, v = -x * sin + y * cos;
                float d0 = u - lowU, d1 = highU - u, d2 = v - lowV, d3 = highV - v;
                float d = Math.min(Math.min(d0, d1), Math.min(d2, d3));
                error += d;
                float tolerance = Math.min(du, dv) * 0.10f;
                if (d <= tolerance) {
                    if (d == d0) edges[0]++; else if (d == d1) edges[1]++;
                    else if (d == d2) edges[2]++; else edges[3]++;
                }
            }
            if (edges[0] < 3 || edges[1] < 3 || edges[2] < 3 || edges[3] < 3) continue;
            error /= count * Math.min(du, dv);
            if (error < rotRectError) {
                rotRectError = error;
                rotRectCorners = new float[8];
                float[] us = { lowU, highU, highU, lowU };
                float[] vs = { lowV, lowV, highV, highV };
                for (int j = 0; j < 4; j++) {
                    rotRectCorners[j * 2] = us[j] * cos - vs[j] * sin;
                    rotRectCorners[j * 2 + 1] = us[j] * sin + vs[j] * cos;
                }
            }
        }

        // 4. 사각형 최종 후보 정리:
        //    - 직각에 가까운 사각형이면 깔끔한 직사각형(수평/수직 정렬)으로 보정
        //    - 사다리꼴/평행사변형 등 의도적 비직각 사각형이면 4꼭짓점 그대로 유지
        float bestRectError = Float.POSITIVE_INFINITY;
        float[] bestRectCorners = null;
        if (quadCorners != null && quadError <= 0.082f) {
            if (isNearlyRightAngledQuad(quadCorners) && rotRectCorners != null && rotRectError <= 0.095f) {
                bestRectCorners = snapRectToAxisIfClose(rotRectCorners);
                bestRectError = Math.min(quadError, rotRectError);
            } else if (isNearlyRightAngledQuad(quadCorners)) {
                bestRectCorners = regularizeToRectangle(quadCorners);
                bestRectError = quadError;
            } else {
                bestRectCorners = alignPolygonBaselineIfClose(quadCorners, 10.0f);
                bestRectError = quadError;
            }
        } else if (rotRectCorners != null && rotRectError <= 0.082f) {
            bestRectCorners = snapRectToAxisIfClose(rotRectCorners);
            bestRectError = rotRectError;
        }

        boolean triOk = (triCorners != null && triError <= 0.082f);
        boolean rectOk = (bestRectCorners != null && bestRectError <= 0.085f);

        // 5. 원/타원 vs 삼각형 vs 사각형 경쟁 판정
        if (triOk && (!rectOk || triError <= bestRectError * 0.96f) && (!curveOk || triError <= curveError * 1.05f)) {
            return new Shape(Kind.TRIANGLE, 0, 0, 0, alignPolygonBaselineIfClose(triCorners, 10.0f), triError);
        }
        if (rectOk && (!curveOk || bestRectError <= curveError * 0.98f)) {
            return new Shape(Kind.RECTANGLE, 0, 0, 0, bestRectCorners, bestRectError);
        }
        if (curveOk) {
            if (curveIsEllipse) {
                return new Shape(Kind.ELLIPSE, efit.cx, efit.cy, efit.rx, efit.ry, efit.angleRad, curveError);
            }
            return new Shape(Kind.CIRCLE, curveCx, curveCy, curveR, null, curveError);
        }
        return null;
    }

    /**
     * 닫힌 궤적이 detectClosed() 임계값에 미세하게 못 미칠 때의 보조 원/타원 추정기.
     * PCA 주축 피팅으로 종횡비 <= 1.25는 원, 1.25 초과 ~ 5.5 이하는 타원으로 판별합니다.
     */
    static Shape fitFallbackCircleOrEllipse(float[] xy, int count) {
        if (xy == null || count < 8) return null;
        float length = 0f;
        for (int i = 1; i < count; i++) {
            length += (float) Math.hypot(xy[i * 2] - xy[(i - 1) * 2], xy[i * 2 + 1] - xy[(i - 1) * 2 + 1]);
        }
        EllipseFit efit = fitEllipseInternal(xy, count, length);
        if (!efit.valid || efit.ry < 15f) return null;
        if (efit.aspect > CIRCLE_ELLIPSE_ASPECT_THRESHOLD && efit.aspect <= MAX_ELLIPSE_ASPECT) {
            return new Shape(Kind.ELLIPSE, efit.cx, efit.cy, efit.rx, efit.ry, efit.angleRad, efit.normError);
        }
        float r = (efit.rx + efit.ry) * 0.5f;
        if (r < 15f) return null;
        return new Shape(Kind.CIRCLE, efit.cx, efit.cy, r, null, efit.normError);
    }

    /**
     * 선분 길이 가중 PCA(주성분 분석)로 타원의 중심, 장축/단축 반지름, 회전각을 피팅합니다.
     * - 멈춤 구간의 중복 점 뭉침에 영향을 받지 않도록 선분 길이 가중치를 적용
     * - 회전각이 수평(0°)/수직(90°)에서 ±12° 이내이면 반듯하게 스냅, 그 이상은 의도된 기울기 보존
     */
    private static EllipseFit fitEllipseInternal(float[] xy, int count, float length) {
        EllipseFit out = new EllipseFit();
        if (xy == null || count < 6) return out;

        double sumW = 0.0, mx = 0.0, my = 0.0;
        for (int i = 1; i < count; i++) {
            double x0 = xy[(i - 1) * 2], y0 = xy[(i - 1) * 2 + 1];
            double x1 = xy[i * 2], y1 = xy[i * 2 + 1];
            double w = Math.hypot(x1 - x0, y1 - y0);
            if (w < 1e-4) continue;
            mx += 0.5 * (x0 + x1) * w;
            my += 0.5 * (y0 + y1) * w;
            sumW += w;
        }
        if (sumW < 1.0) return out;
        mx /= sumW;
        my /= sumW;

        double sxx = 0.0, syy = 0.0, sxy = 0.0;
        for (int i = 1; i < count; i++) {
            double x0 = xy[(i - 1) * 2], y0 = xy[(i - 1) * 2 + 1];
            double x1 = xy[i * 2], y1 = xy[i * 2 + 1];
            double w = Math.hypot(x1 - x0, y1 - y0);
            if (w < 1e-4) continue;
            double dx = 0.5 * (x0 + x1) - mx;
            double dy = 0.5 * (y0 + y1) - my;
            sxx += w * dx * dx;
            syy += w * dy * dy;
            sxy += w * dx * dy;
        }

        double theta = 0.5 * Math.atan2(2.0 * sxy, sxx - syy);
        double deg = Math.toDegrees(theta);
        if (Math.abs(deg) <= ELLIPSE_AXIS_SNAP_DEG) {
            theta = 0.0;
        } else if (Math.abs(Math.abs(deg) - 90.0) <= ELLIPSE_AXIS_SNAP_DEG) {
            theta = (deg >= 0.0) ? (Math.PI * 0.5) : (-Math.PI * 0.5);
        }

        double cos = Math.cos(theta), sin = Math.sin(theta);
        double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double dx = xy[i * 2] - mx;
            double dy = xy[i * 2 + 1] - my;
            double u = dx * cos + dy * sin;
            double v = -dx * sin + dy * cos;
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
        }

        double cu = 0.5 * (minU + maxU);
        double cv = 0.5 * (minV + maxV);
        double ecx = mx + cu * cos - cv * sin;
        double ecy = my + cu * sin + cv * cos;
        double rx = 0.5 * (maxU - minU);
        double ry = 0.5 * (maxV - minV);
        if (rx < 1.0 || ry < 1.0) return out;

        if (ry > rx) {
            double tmp = rx;
            rx = ry;
            ry = tmp;
            theta += Math.PI * 0.5;
            if (theta > Math.PI * 0.5) theta -= Math.PI;
            if (theta <= -Math.PI * 0.5) theta += Math.PI;
            cos = Math.cos(theta);
            sin = Math.sin(theta);
        }

        double errSum = 0.0;
        for (int i = 1; i < count; i++) {
            double x0 = xy[(i - 1) * 2], y0 = xy[(i - 1) * 2 + 1];
            double x1 = xy[i * 2], y1 = xy[i * 2 + 1];
            double w = Math.hypot(x1 - x0, y1 - y0);
            if (w < 1e-4) continue;
            double dx = 0.5 * (x0 + x1) - ecx;
            double dy = 0.5 * (y0 + y1) - ecy;
            double u = (dx * cos + dy * sin) / rx;
            double v = (-dx * sin + dy * cos) / ry;
            double normR = Math.hypot(u, v);
            errSum += w * Math.abs(normR - 1.0);
        }

        double hSum = rx + ry;
        double ellPerim = Math.PI * (3.0 * hSum - Math.sqrt(Math.max(0.0, (3.0 * rx + ry) * (rx + 3.0 * ry))));
        out.cx = (float) ecx;
        out.cy = (float) ecy;
        out.rx = (float) rx;
        out.ry = (float) ry;
        out.angleRad = (float) theta;
        out.aspect = (float) (rx / Math.max(1.0, ry));
        out.normError = (float) (errSum / sumW);
        out.perimeterRatio = (float) (length / Math.max(1.0, ellPerim));
        out.valid = true;
        return out;
    }

    /**
     * 닫힌 궤적에서 시작점/끝점 위치(변 중간 시작 포함)에 무관하게
     * 진짜 꺾임 꼭짓점을 추출합니다.
     */
    private static float[] extractClosedPolygonVertices(float[] xy, int count, float epsilon, float shortSide) {
        if (count < 9) return null;

        // 1. 무게중심에서 가장 먼 점 idxA를 찾아 첫 분할 꼭짓점으로 설정
        float mx = 0f, my = 0f;
        for (int i = 0; i < count; i++) {
            mx += xy[i * 2];
            my += xy[i * 2 + 1];
        }
        mx /= count;
        my /= count;

        int idxA = 0;
        float maxD = -1f;
        for (int i = 0; i < count; i++) {
            float dx = xy[i * 2] - mx, dy = xy[i * 2 + 1] - my;
            float d = dx * dx + dy * dy;
            if (d > maxD) { maxD = d; idxA = i; }
        }

        // idxA에서 시작하도록 순환 배열 재정렬 (시작/끝 이음새를 꼭짓점에 고정)
        float[] ring = new float[(count + 1) * 2];
        for (int i = 0; i < count; i++) {
            int src = (idxA + i) % count;
            ring[i * 2] = xy[src * 2];
            ring[i * 2 + 1] = xy[src * 2 + 1];
        }
        ring[count * 2] = ring[0];
        ring[count * 2 + 1] = ring[1];

        // idxA에서 가장 먼 점 idxB를 찾아 두 구간(0..idxB, idxB..count)으로 나눠 RDP 적용
        int idxB = count / 2;
        maxD = -1f;
        for (int i = 1; i < count; i++) {
            float dx = ring[i * 2] - ring[0], dy = ring[i * 2 + 1] - ring[1];
            float d = dx * dx + dy * dy;
            if (d > maxD) { maxD = d; idxB = i; }
        }
        if (idxB <= 1 || idxB >= count - 1) return null;

        List<Integer> part1 = new ArrayList<Integer>();
        part1.add(0);
        rdpSub(ring, 0, idxB, epsilon, part1);
        part1.add(idxB);

        List<Integer> part2 = new ArrayList<Integer>();
        part2.add(idxB);
        rdpSub(ring, idxB, count, epsilon, part2);
        part2.add(count);

        List<float[]> verts = new ArrayList<float[]>();
        for (int i = 0; i < part1.size(); i++) {
            int idx = part1.get(i);
            verts.add(new float[] { ring[idx * 2], ring[idx * 2 + 1] });
        }
        // part2의 첫 점(idxB)과 끝 점(count==0)은 중복이므로 제외
        for (int i = 1; i < part2.size() - 1; i++) {
            int idx = part2.get(i);
            verts.add(new float[] { ring[idx * 2], ring[idx * 2 + 1] });
        }

        // 근접 중복 점 및 일직선에 가까운(내각 > 152도) 점 반복 제거
        float minEdge = Math.max(18f, shortSide * 0.16f);
        boolean changed = true;
        int guard = 0;
        while (changed && verts.size() >= 3 && guard++ < 16) {
            changed = false;
            int n = verts.size();
            for (int i = 0; i < n; i++) {
                float[] prev = verts.get((i - 1 + n) % n);
                float[] cur = verts.get(i);
                float[] next = verts.get((i + 1) % n);
                float dPrev = (float) Math.hypot(cur[0] - prev[0], cur[1] - prev[1]);
                float dNext = (float) Math.hypot(next[0] - cur[0], next[1] - cur[1]);
                if (dPrev < minEdge || dNext < minEdge) {
                    verts.remove(i);
                    changed = true;
                    break;
                }
                float angleDeg = interiorAngleDeg(prev, cur, next);
                if (angleDeg > 152f) {
                    verts.remove(i);
                    changed = true;
                    break;
                }
            }
        }

        if (verts.size() < 3 || verts.size() > 4) return null;
        float[] out = new float[verts.size() * 2];
        for (int i = 0; i < verts.size(); i++) {
            out[i * 2] = verts.get(i)[0];
            out[i * 2 + 1] = verts.get(i)[1];
        }
        return out;
    }

    private static void rdpSub(float[] pts, int start, int end, float epsilon, List<Integer> out) {
        if (end <= start + 1) return;
        float ax = pts[start * 2], ay = pts[start * 2 + 1];
        float bx = pts[end * 2], by = pts[end * 2 + 1];
        float maxDist = -1f;
        int maxIdx = -1;
        for (int i = start + 1; i < end; i++) {
            float d = pointToSegmentDist(pts[i * 2], pts[i * 2 + 1], ax, ay, bx, by);
            if (d > maxDist) {
                maxDist = d;
                maxIdx = i;
            }
        }
        if (maxIdx != -1 && maxDist > epsilon) {
            rdpSub(pts, start, maxIdx, epsilon, out);
            out.add(maxIdx);
            rdpSub(pts, maxIdx, end, epsilon, out);
        }
    }

    private static float evaluatePolygonFit(float[] xy, int count, float[] verts, float shortSide, float strokeLen) {
        int n = verts.length / 2;
        float polyPerim = 0f;
        for (int j = 0; j < n; j++) {
            int nxt = (j + 1) % n;
            float sideLen = (float) Math.hypot(verts[nxt * 2] - verts[j * 2], verts[nxt * 2 + 1] - verts[j * 2 + 1]);
            if (sideLen < shortSide * 0.22f) return Float.POSITIVE_INFINITY;
            polyPerim += sideLen;
        }
        float perimRatio = strokeLen / Math.max(1f, polyPerim);
        if (perimRatio < 0.76f || perimRatio > 1.42f) return Float.POSITIVE_INFINITY;

        int[] edgeHits = new int[n];
        float totalDist = 0f;
        float hitTol = Math.max(12f, shortSide * 0.12f);
        for (int i = 0; i < count; i++) {
            float px = xy[i * 2], py = xy[i * 2 + 1];
            float bestD = Float.POSITIVE_INFINITY;
            int bestEdge = 0;
            for (int j = 0; j < n; j++) {
                int nxt = (j + 1) % n;
                float d = pointToSegmentDist(px, py, verts[j * 2], verts[j * 2 + 1], verts[nxt * 2], verts[nxt * 2 + 1]);
                if (d < bestD) {
                    bestD = d;
                    bestEdge = j;
                }
            }
            totalDist += bestD;
            if (bestD <= hitTol) {
                edgeHits[bestEdge]++;
            }
        }
        for (int j = 0; j < n; j++) {
            if (edgeHits[j] < 2) return Float.POSITIVE_INFINITY;
        }
        return totalDist / (count * Math.max(1f, shortSide));
    }

    private static boolean hasDistinctCorners(float[] verts, float maxAngleDeg, int minSharpCount) {
        int n = verts.length / 2;
        int sharp = 0;
        for (int i = 0; i < n; i++) {
            float[] prev = new float[] { verts[((i - 1 + n) % n) * 2], verts[((i - 1 + n) % n) * 2 + 1] };
            float[] cur = new float[] { verts[i * 2], verts[i * 2 + 1] };
            float[] next = new float[] { verts[((i + 1) % n) * 2], verts[((i + 1) % n) * 2 + 1] };
            float ang = interiorAngleDeg(prev, cur, next);
            if (ang <= maxAngleDeg && ang >= 18f) sharp++;
        }
        return sharp >= minSharpCount;
    }

    private static boolean isConvexQuad(float[] v) {
        if (v == null || v.length != 8) return false;
        float sign = 0f;
        for (int i = 0; i < 4; i++) {
            int i1 = (i + 1) % 4;
            int i2 = (i + 2) % 4;
            float dx1 = v[i1 * 2] - v[i * 2], dy1 = v[i1 * 2 + 1] - v[i * 2 + 1];
            float dx2 = v[i2 * 2] - v[i1 * 2], dy2 = v[i2 * 2 + 1] - v[i1 * 2 + 1];
            float cross = dx1 * dy2 - dy1 * dx2;
            if (Math.abs(cross) < 1e-2f) return false;
            if (sign == 0f) sign = cross;
            else if (sign * cross < 0f) return false;
        }
        return true;
    }

    private static boolean isNearlyRightAngledQuad(float[] v) {
        if (v == null || v.length != 8) return false;
        for (int i = 0; i < 4; i++) {
            float[] prev = new float[] { v[((i + 3) % 4) * 2], v[((i + 3) % 4) * 2 + 1] };
            float[] cur = new float[] { v[i * 2], v[i * 2 + 1] };
            float[] next = new float[] { v[((i + 1) % 4) * 2], v[((i + 1) % 4) * 2 + 1] };
            float ang = interiorAngleDeg(prev, cur, next);
            if (ang < 68f || ang > 112f) return false;
        }
        return true;
    }

    private static float[] regularizeToRectangle(float[] v) {
        float dx = v[2] - v[0], dy = v[3] - v[1];
        double angle = Math.atan2(dy, dx);
        double deg = Math.toDegrees(angle);
        // 수평/수직 기준선과 10도 이내면 똑바로 세워 정렬, 10도 초과로 일부러 기울여 그리면 그 각도 유지
        double snappedDeg = Math.round(deg / 90.0) * 90.0;
        if (Math.abs(deg - snappedDeg) <= 10.0) {
            angle = Math.toRadians(snappedDeg);
        }
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = v[i * 2], y = v[i * 2 + 1];
            float u = x * cos + y * sin;
            float w = -x * sin + y * cos;
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            minV = Math.min(minV, w); maxV = Math.max(maxV, w);
        }
        float[] out = new float[8];
        float[] us = { minU, maxU, maxU, minU };
        float[] vs = { minV, minV, maxV, maxV };
        for (int i = 0; i < 4; i++) {
            out[i * 2] = us[i] * cos - vs[i] * sin;
            out[i * 2 + 1] = us[i] * sin + vs[i] * cos;
        }
        return out;
    }

    /**
     * 삼각형/일반 사각형의 기준변이 수평(0°) 또는 수직(90°)과 snapDeg(±10°) 이내로 가까우면
     * 무게중심 기준으로 회전해 똑바로 세워줍니다.
     * 모든 변이 기준선에서 10°를 넘게 일부러 비스듬히 그려진 경우 원래 각도를 그대로 유지합니다.
     */
    private static float[] alignPolygonBaselineIfClose(float[] v, float snapDeg) {
        if (v == null || v.length < 6) return v;
        int n = v.length / 2;
        double bestAbsDelta = Double.POSITIVE_INFINITY;
        double bestDeltaRad = 0.0;
        for (int i = 0; i < n; i++) {
            int nxt = (i + 1) % n;
            double dx = v[nxt * 2] - v[i * 2];
            double dy = v[nxt * 2 + 1] - v[i * 2 + 1];
            double deg = Math.toDegrees(Math.atan2(dy, dx));
            double nearestAxis = Math.round(deg / 90.0) * 90.0;
            double delta = nearestAxis - deg;
            double absDelta = Math.abs(delta);
            if (absDelta < bestAbsDelta) {
                bestAbsDelta = absDelta;
                bestDeltaRad = Math.toRadians(delta);
            }
        }
        if (bestAbsDelta > snapDeg) {
            return v; // 일부러 각지게 기울여 그린 도형 -> 원래 각도 보존
        }

        float cx = 0f, cy = 0f;
        for (int i = 0; i < n; i++) {
            cx += v[i * 2];
            cy += v[i * 2 + 1];
        }
        cx /= n;
        cy /= n;

        float cos = (float) Math.cos(bestDeltaRad);
        float sin = (float) Math.sin(bestDeltaRad);
        float[] out = new float[v.length];
        for (int i = 0; i < n; i++) {
            float rx = v[i * 2] - cx;
            float ry = v[i * 2 + 1] - cy;
            out[i * 2] = cx + rx * cos - ry * sin;
            out[i * 2 + 1] = cy + rx * sin + ry * cos;
        }
        return out;
    }

    private static float[] snapRectToAxisIfClose(float[] v) {
        return regularizeToRectangle(v);
    }

    private static float interiorAngleDeg(float[] prev, float[] cur, float[] next) {
        float ax = prev[0] - cur[0], ay = prev[1] - cur[1];
        float bx = next[0] - cur[0], by = next[1] - cur[1];
        float la = (float) Math.hypot(ax, ay);
        float lb = (float) Math.hypot(bx, by);
        if (la < 1e-3f || lb < 1e-3f) return 180f;
        float dot = (ax * bx + ay * by) / (la * lb);
        dot = Math.max(-1f, Math.min(1f, dot));
        return (float) Math.toDegrees(Math.acos(dot));
    }

    private static float pointToSegmentDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-4f) return (float) Math.hypot(px - ax, py - ay);
        float t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
        if (t < 0f) t = 0f;
        else if (t > 1f) t = 1f;
        float projX = ax + t * dx;
        float projY = ay + t * dy;
        return (float) Math.hypot(px - projX, py - projY);
    }

    /** 충분히 길고 구부러짐이 적은 획만 직선으로 판정한다. */
    static boolean looksStraight(float[] xy, int count, float scale) {
        if (xy == null || count < 4) return false;
        float x0 = xy[0], y0 = xy[1];
        float x1 = xy[(count - 1) * 2], y1 = xy[(count - 1) * 2 + 1];
        float dx = x1 - x0, dy = y1 - y0;
        float span = (float) Math.hypot(dx, dy);
        if (span * scale < 88f) return false;
        float pathLength = 0f, totalError = 0f, maxError = 0f;
        int stride = Math.max(2, count / 32), previous = 0;
        for (int i = 1; i < count; i++) {
            float x = xy[i * 2], y = xy[i * 2 + 1];
            if (i == count - 1 || i - previous >= stride) {
                pathLength += (float) Math.hypot(x - xy[previous * 2], y - xy[previous * 2 + 1]);
                previous = i;
            }
            float distance = Math.abs(dx * (y - y0) - dy * (x - x0)) / span;
            totalError += distance;
            maxError = Math.max(maxError, distance);
        }
        return pathLength / span <= 1.12f && maxError / span <= 0.085f
                && totalError / (count - 1) / span <= 0.035f;
    }
}
