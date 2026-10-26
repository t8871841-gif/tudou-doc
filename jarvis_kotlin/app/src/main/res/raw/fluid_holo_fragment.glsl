#version 300 es
precision highp float;

uniform float u_time;
uniform vec2  u_resolution;
uniform float u_glow;

out vec4 fragColor;

#define PI 3.14159265359

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

// 哈希噪声
float hash31(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

// 压扁环面 (丝带基础形状)
float sdFlatTorus(vec3 p, vec2 t, float flatness) {
    vec2 q = vec2(length(p.xz) - t.x, p.y * flatness);
    return length(q) - t.y;
}

// 3D 扭曲 (让丝带像麻花一样拧转)
vec3 twist(vec3 p, float k) {
    float c = cos(k * p.y);
    float s = sin(k * p.y);
    mat2 m = mat2(c, -s, s, c);
    return vec3(m * p.xy, p.z);
}

// 场景距离场
vec2 map(vec3 p) {
    // 全局缓慢旋转
    p.xz = rot(u_time * 0.15) * p.xz;
    p.xy = rot(u_time * 0.10) * p.xy;

    float d  = 1e5;
    float id = 0.0;

    // --- 丝带 1：青蓝 ---
    vec3 p1 = p;
    p1.xz = rot(0.8) * p1.xz;
    p1 = twist(p1, 2.0 + 0.3 * sin(u_time * 0.8));
    float d1 = sdFlatTorus(p1, vec2(0.55 + 0.05 * sin(u_time), 0.06), 2.5);
    if (d1 < d) { d = d1; id = 1.0; }

    // --- 丝带 2：蜜桃 ---
    vec3 p2 = p;
    p2.xy = rot(1.57) * p2.xy;
    p2.xz = rot(-0.5) * p2.xz;
    p2 = twist(p2, 2.5 + 0.4 * cos(u_time * 0.6));
    float d2 = sdFlatTorus(p2, vec2(0.65 + 0.05 * cos(u_time * 1.2), 0.065), 2.2);
    if (d2 < d) { d = d2; id = 2.0; }

    // --- 丝带 3：浅蓝 ---
    vec3 p3 = p;
    p3.yz = rot(0.6) * p3.yz;
    p3 = twist(p3, 3.0 - 0.3 * sin(u_time * 0.9));
    float d3 = sdFlatTorus(p3, vec2(0.45, 0.055), 2.8);
    if (d3 < d) { d = d3; id = 3.0; }

    // --- 丝带 4：青蓝（新增，补密度） ---
    vec3 p4 = p;
    p4.xz = rot(2.1) * p4.xz;
    p4.xy = rot(0.9) * p4.xy;
    p4 = twist(p4, 2.2 + 0.3 * cos(u_time * 0.7));
    float d4 = sdFlatTorus(p4, vec2(0.60 + 0.04 * sin(u_time * 1.1), 0.06), 2.4);
    if (d4 < d) { d = d4; id = 4.0; }

    // --- 丝带 5：蜜桃（新增，补密度） ---
    vec3 p5 = p;
    p5.yz = rot(1.9) * p5.yz;
    p5.xz = rot(1.2) * p5.xz;
    p5 = twist(p5, 2.8 + 0.3 * sin(u_time * 0.5));
    float d5 = sdFlatTorus(p5, vec2(0.50 + 0.04 * cos(u_time * 0.9), 0.06), 2.3);
    if (d5 < d) { d = d5; id = 5.0; }

    // --- 丝带 6：浅蓝（新增，补密度） ---
    vec3 p6 = p;
    p6.xy = rot(2.6) * p6.xy;
    p6.yz = rot(-0.8) * p6.yz;
    p6 = twist(p6, 2.4 + 0.4 * sin(u_time * 1.0));
    float d6 = sdFlatTorus(p6, vec2(0.70, 0.055), 2.6);
    if (d6 < d) { d = d6; id = 6.0; }

    return vec2(d, id);
}

vec2 sphereIntersect(vec3 ro, vec3 rd, float rad) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - rad * rad;
    float h = b * b - c;
    if (h < 0.0) return vec2(-1.0);
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

void main() {
    vec2 uv = (gl_FragCoord.xy * 2.0 - u_resolution.xy) / min(u_resolution.x, u_resolution.y);

    // 背景底色 (极暗的星空)
    vec3 bgColor = vec3(0.005, 0.01, 0.03);

    // 屏幕星点
    vec2 starUv = uv * 60.0;
    float star = hash31(vec3(floor(starUv), 0.0));
    bgColor += vec3(0.8, 0.85, 1.0) * smoothstep(0.997, 1.0, star) * 0.5;

    // 相机设置
    vec3 ro = vec3(0.0, 0.0, 2.2);
    vec3 rd = normalize(vec3(uv * 0.7, -1.0));

    vec2 t = sphereIntersect(ro, rd, 1.4);
    vec3 col = bgColor;

    if (t.y > 0.0) {
        float tmin = max(t.x, 0.0);
        float tmax = t.y;
        const int STEPS = 64;
        float dt = (tmax - tmin) / float(STEPS);

        float jitter = hash31(vec3(gl_FragCoord.xy, u_time));
        float tCurrent = tmin + dt * jitter;

        vec3  accumColor = vec3(0.0);
        float accumAlpha = 0.0;

        // J.A.R.V.I.S 扫描光幕位置 (上下扫描)
        float scanPos = sin(u_time * 1.5) * 0.8;

        for (int i = 0; i < STEPS; i++) {
            vec3 p = ro + rd * tCurrent;
            vec2 res = map(p);
            float d  = res.x;
            float id = res.y;

            // --- 核心：将距离场转为密度（辉光范围 0.04→0.06，带子更宽更软） ---
            float density = smoothstep(0.06, 0.0, abs(d));

            // 数据流改柔和亮度波（原高频条纹 + 全息网格都去掉，参考图里丝带是干净的）：
            // 低频正弦沿带缓慢起伏，只做亮度明暗，不出条纹
            float dataFlow = sin(p.y * 8.0 - u_time * 1.5) * 0.5 + 0.5;

            // 扫描光幕增强
            float scanEffect = smoothstep(0.05, 0.0, abs(p.y - scanPos)) * 2.0;

            // 修复原稿作用域问题：两色提到 if 外，丝带段与粒子段共用
            // （原稿在 if 内定义，粒子段引用会编译失败）
            vec3 cCyan   = vec3(0.0, 0.8, 1.0);
            vec3 cPeach  = vec3(1.0, 0.72, 0.5);

            if (density > 0.001) {
                // 经典贾维斯配色
                vec3 baseCol = cCyan;
                if (id == 2.0 || id == 5.0) baseCol = cPeach;
                if (id == 3.0 || id == 6.0) baseCol = vec3(0.4, 0.9, 1.0);

                // 边缘高光 (类似菲涅尔)
                float edgeGlow = smoothstep(0.01, 0.04, abs(d)) * (1.0 - smoothstep(0.04, 0.08, abs(d)));

                // 组合最终颜色
                vec3 ribbonColor = baseCol * (1.0 + dataFlow * 0.4);
                ribbonColor += cCyan * scanEffect; // 扫描时泛蓝光
                ribbonColor += baseCol * edgeGlow * 3.0; // 边缘高光

                float alpha = density * (0.15 + dataFlow * 0.1);
                accumColor += ribbonColor * alpha * (1.0 - accumAlpha);
                accumAlpha += alpha * (1.0 - accumAlpha);

                if (accumAlpha > 0.95) break;
            }

            // 4. 悬浮数据粒子 (Data Particles)
            // 在空间中生成离散的点云
            vec3 pGrid = floor(p * 15.0) / 15.0;
            float pNoise = hash31(pGrid);
            if (pNoise > 0.96) {
                float pDist = length(p - pGrid);
                float pDensity = smoothstep(0.02, 0.0, pDist);
                // 粒子闪烁
                float flicker = sin(u_time * 10.0 + pNoise * 100.0) * 0.5 + 0.5;
                vec3 pCol = mix(cCyan, cPeach, pNoise);
                accumColor += pCol * pDensity * flicker * 0.5 * (1.0 - accumAlpha);
                accumAlpha += pDensity * 0.2 * (1.0 - accumAlpha);
            }

            tCurrent += dt;
        }

        // u_glow 只推光带与粒子，不动星空底（与旧光带同一辉光公式，Kotlin 侧算）
        col = bgColor * (1.0 - accumAlpha) + accumColor * u_glow;
    }

    // 屏幕暗角与扫描线
    float vignette = 1.0 - smoothstep(1.0, 1.6, length(uv));
    float screenScan = sin(gl_FragCoord.y * 2.0 + u_time * 20.0) * 0.02 + 0.98; // 微弱显像管扫描线
    col *= vignette * screenScan;

    // 色调映射与 Gamma
    col = col / (col + vec3(1.0));
    col = pow(col, vec3(1.0 / 2.2));

    fragColor = vec4(col, 1.0);
}
