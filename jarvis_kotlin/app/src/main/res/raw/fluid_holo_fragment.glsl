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
    float d1 = sdFlatTorus(p1, vec2(0.55 + 0.05 * sin(u_time), 0.035), 2.5);
    if (d1 < d) { d = d1; id = 1.0; }

    // --- 丝带 2：暖橙 ---
    vec3 p2 = p;
    p2.xy = rot(1.57) * p2.xy;
    p2.xz = rot(-0.5) * p2.xz;
    p2 = twist(p2, 2.5 + 0.4 * cos(u_time * 0.6));
    float d2 = sdFlatTorus(p2, vec2(0.65 + 0.05 * cos(u_time * 1.2), 0.04), 2.2);
    if (d2 < d) { d = d2; id = 2.0; }

    // --- 丝带 3：浅蓝 ---
    vec3 p3 = p;
    p3.yz = rot(0.6) * p3.yz;
    p3 = twist(p3, 3.0 - 0.3 * sin(u_time * 0.9));
    float d3 = sdFlatTorus(p3, vec2(0.45, 0.03), 2.8);
    if (d3 < d) { d = d3; id = 3.0; }

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

            // --- 核心：将距离场转为密度 ---
            float density = smoothstep(0.04, 0.0, abs(d));

            // 1. 数据流纹理 (Data Stream)
            // 利用空间位置和时间生成沿丝带流动的高频条纹
            float dataFlow = sin(p.y * 50.0 - u_time * 10.0) * 0.5 + 0.5;
            dataFlow = smoothstep(0.3, 1.0, dataFlow);

            // 2. 全息网格 (Hologram Grid) - 叠加在丝带表面
            float grid = max(
                smoothstep(0.9, 1.0, sin(p.x * 40.0)),
                smoothstep(0.9, 1.0, sin(p.y * 40.0))
            );

            // 3. 扫描光幕增强
            float scanEffect = smoothstep(0.05, 0.0, abs(p.y - scanPos)) * 2.0;

            // 修复原稿作用域问题：两色提到 if 外，丝带段与粒子段共用
            // （原稿在 if 内定义，粒子段引用会编译失败）
            vec3 cCyan   = vec3(0.0, 0.8, 1.0);
            vec3 cOrange = vec3(1.0, 0.6, 0.2);

            if (density > 0.001) {
                // 经典贾维斯配色
                vec3 baseCol = cCyan;
                if (id == 2.0) baseCol = cOrange;
                if (id == 3.0) baseCol = vec3(0.4, 0.9, 1.0);

                // 边缘高光 (类似菲涅尔)
                float edgeGlow = smoothstep(0.01, 0.04, abs(d)) * (1.0 - smoothstep(0.04, 0.08, abs(d)));

                // 组合最终颜色
                vec3 ribbonColor = baseCol * (1.0 + dataFlow * 1.5 + grid * 2.0);
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
                vec3 pCol = mix(cCyan, cOrange, pNoise);
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
