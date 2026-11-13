package com.nyro.companion.ui.components

/**
 * AGSL fragment shader for the liquid-drop mascot. The body is a deformed sphere: its silhouette is a teardrop whose
 * surface wobbles, and each pixel inside gets a sphere-like normal, so the lighting reads as 3D without ray marching.
 * Shading layers: color absorbed through the liquid, a caustic where light focuses at the bottom, diffuse light,
 * Fresnel rim, a darker refraction band, specular highlights, and a window reflection. The face sits on the surface
 * and moves with gaze and tilt. Units: p and q are in body radii, y up.
 */
internal const val MASCOT_SHADER = """
uniform float2 uCenter;
uniform float uRadius;
uniform float2 uScale;
uniform float uTilt;
uniform float uTime;
uniform float uWobble;
uniform float uWobbleSpeed;
uniform float uTip;
uniform float2 uLook;
uniform float uEyeOpen;
uniform float uEyeCurve;
uniform float uEyeAsym;
uniform float uMouthOpen;
uniform float uMouthCurve;
uniform float uBlush;
uniform float uGlow;
uniform float uBubbles;
uniform float2 uFloor;
uniform float3 uTint;
uniform float3 uDeep;

float sat(float x) { return clamp(x, 0.0, 1.0); }

float radiusAt(float2 p) {
    float a = atan(p.y, p.x);
    float s = sin(a);
    float up = max(s, 0.0);
    float down = max(-s, 0.0);
    float r = 1.0 + uTip * 0.5 * pow(up, 7.0) + 0.05 * down;
    float w = uTime * uWobbleSpeed;
    r += uWobble * (0.6 * sin(3.0 * a + w) + 0.4 * sin(5.0 * a - 1.3 * w) + 0.25 * sin(2.0 * a + 0.7 * w));
    return r;
}

float ellipseD(float2 d, float2 r) { return (length(d / r) - 1.0) * min(r.x, r.y); }

float arcD(float2 d, float w, float bend, float th) {
    float x = clamp(d.x, -w, w);
    float k = x / w;
    float y = bend * (1.0 - k * k);
    return length(float2(d.x - x, d.y - y)) - th;
}

float eyeMask(float2 f, float2 c, float open, float curve, float aa) {
    float2 d = f - c;
    float h = 0.17 * open;
    float mEll = 1.0 - smoothstep(-aa, aa, ellipseD(d, float2(0.1, max(h, 0.012))));
    float mArc = 1.0 - smoothstep(-aa, aa, arcD(d, 0.105, 0.06 * curve, 0.024));
    return mix(mArc, mEll, smoothstep(0.12, 0.35, open));
}

float catchlight(float2 f, float2 c, float open, float aa) {
    float2 d = f - c - float2(-0.035, 0.06 * open);
    float m = 1.0 - smoothstep(-aa, aa, length(d) - 0.032 * open);
    float2 d2 = f - c - float2(0.03, -0.05 * open);
    float m2 = 1.0 - smoothstep(-aa, aa, length(d2) - 0.014 * open);
    return (m + 0.6 * m2) * smoothstep(0.3, 0.6, open);
}

half4 main(float2 fc) {
    float2 p = (fc - uCenter) / uRadius;
    p.y = -p.y;
    float cs = cos(uTilt);
    float sn = sin(uTilt);
    p = float2(cs * p.x - sn * p.y, sn * p.x + cs * p.y);
    p = p / uScale;

    float rad = radiusAt(p);
    float len = length(p);
    float d = len - rad;
    float aa = 1.3 / uRadius;

    // Floor shadow, then a soft glow around the body.
    float2 sp = (fc - float2(uCenter.x, uFloor.x)) / (uRadius * float2(0.9, 0.17) * uFloor.y);
    float aShadow = 0.5 * exp(-dot(sp, sp) * 1.6) * uFloor.y;
    float4 outc = float4(float3(0.0, 0.01, 0.04) * aShadow, aShadow);
    float aGlow = uGlow * 0.32 * exp(-max(d, 0.0) * 3.5) * step(0.0, d) * (1.0 - smoothstep(0.25, 0.65, d));
    outc.rgb = uTint * aGlow + outc.rgb * (1.0 - aGlow);
    outc.a = aGlow + outc.a * (1.0 - aGlow);

    float aBody = 1.0 - smoothstep(-aa, aa, d);
    if (aBody <= 0.0) { return half4(outc); }

    float2 q = p / rad;
    float qq = dot(q, q);
    float z = sqrt(sat(1.0 - qq));
    float3 n = normalize(float3(q.x, q.y, z + 0.0001));
    float3 L = normalize(float3(-0.5, 0.7, 0.55));
    float ndl = sat(dot(n, L));
    float fres = pow(1.0 - z, 3.0);
    float lq = sqrt(qq);

    // Liquid: deep, saturated color at the edges, luminous toward the thick center.
    float3 col = mix(uDeep, uTint, pow(z, 0.8));
    float2 cq = q - float2(0.28, -0.5);
    float caustic = exp(-dot(cq, cq) * 6.0);
    col += mix(uTint, float3(1.0), 0.45) * caustic * (0.3 + 0.55 * uGlow);
    col *= 0.7 + 0.42 * ndl;
    col = mix(col, mix(uTint, float3(1.0), 0.6), fres * 0.6);
    float band = smoothstep(0.72, 0.93, lq) * (1.0 - smoothstep(0.93, 1.0, lq));
    col *= 1.0 - 0.28 * band;
    col += uTint * smoothstep(0.45, 1.0, -q.y) * fres * 0.45;

    // Rising bubbles inside the liquid.
    float bub = 0.0;
    for (int i = 0; i < 4; i++) {
        if (uBubbles < 0.01) { break; }
        float fi = float(i);
        float by = fract(uTime * (0.11 + 0.05 * fi) + fi * 0.27) * 1.7 - 0.85;
        float bx = 0.42 * sin(fi * 2.3 + uTime * 0.6 + by * 2.0);
        float br = 0.032 + 0.022 * fract(fi * 0.618);
        float bd = length(q - float2(bx, by)) - br;
        float ring = 1.0 - smoothstep(0.0, 0.022, abs(bd));
        float fill = 1.0 - smoothstep(-0.01, 0.01, bd);
        bub += (0.6 * ring + 0.18 * fill) * (1.0 - smoothstep(0.55, 0.85, abs(by)));
    }
    col += float3(0.9, 0.97, 1.0) * bub * uBubbles * 0.55 * smoothstep(0.1, 0.5, z);

    // Face on the front of the surface.
    float2 f = q - uLook * 0.13;
    float faceVis = smoothstep(0.2, 0.55, z);
    float3 ink = float3(0.03, 0.06, 0.16);
    float openL = uEyeOpen * (1.0 - 0.35 * uEyeAsym);
    float openR = uEyeOpen * (1.0 + 0.15 * uEyeAsym);
    float2 eL = float2(-0.3, 0.07);
    float2 eR = float2(0.3, 0.07 + 0.04 * uEyeAsym);
    float eyes = max(eyeMask(f, eL, openL, uEyeCurve, aa), eyeMask(f, eR, openR, uEyeCurve, aa));

    float2 dm = f - float2(0.0, -0.21);
    float mSmile = 1.0 - smoothstep(-aa, aa, arcD(dm, 0.09, -0.055 * uMouthCurve, 0.022));
    float mh = 0.12 * uMouthOpen;
    float dOpen = ellipseD(dm - float2(0.0, -0.02), float2(0.07 + 0.025 * uMouthOpen, max(mh, 0.01)));
    float mOpen = 1.0 - smoothstep(-aa, aa, dOpen);
    float km = smoothstep(0.08, 0.25, uMouthOpen);
    float mouth = mix(mSmile, mOpen, km);
    float tongue = mOpen * km * (1.0 - smoothstep(-0.08, -0.02, dm.y)) * smoothstep(0.3, 0.6, uMouthOpen);

    float2 bL = (f - float2(-0.5, -0.1)) * float2(1.0, 1.6);
    float2 bR = (f - float2(0.5, -0.1)) * float2(1.0, 1.6);
    float blush = (exp(-dot(bL, bL) * 40.0) + exp(-dot(bR, bR) * 40.0)) * uBlush * faceVis;
    col = mix(col, float3(1.0, 0.55, 0.68), blush * 0.45);

    col = mix(col, ink * (0.8 + 0.3 * ndl), max(eyes, mouth) * faceVis);
    col = mix(col, float3(0.95, 0.42, 0.5), tongue * faceVis * 0.85);
    float shine = catchlight(f, eL, openL, aa) + catchlight(f, eR, openR, aa);
    col = mix(col, float3(1.0), sat(shine) * faceVis * 0.95);

    // Reflections on top of everything: they belong to the glossy surface.
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));
    float nh = sat(dot(n, H));
    col += float3(1.0) * (pow(nh, 36.0) * 0.3 + pow(nh, 420.0) * 1.1);
    float2 hq = (q - float2(-0.36, 0.5)) * float2(1.0, 1.75);
    col = mix(col, float3(1.0), (1.0 - smoothstep(0.1, 0.25, length(hq))) * 0.72);
    col = mix(col, float3(1.0), (1.0 - smoothstep(0.025, 0.055, length(q - float2(-0.1, 0.66)))) * 0.8);
    float3 R = reflect(float3(0.0, 0.0, -1.0), n);
    float win = smoothstep(0.35, 0.6, R.y) * (1.0 - smoothstep(0.75, 0.95, R.y)) * smoothstep(-0.1, 0.25, R.x) * (1.0 - smoothstep(0.45, 0.7, R.x));
    col += float3(0.85, 0.95, 1.0) * win * 0.18;

    outc.rgb = col * aBody + outc.rgb * (1.0 - aBody);
    outc.a = aBody + outc.a * (1.0 - aBody);
    return half4(outc);
}
"""
