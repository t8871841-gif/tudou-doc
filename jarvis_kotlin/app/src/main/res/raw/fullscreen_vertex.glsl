#version 300 es
out vec2 v_uv;
void main() {
    // 利用 gl_VertexID 绘制一个全屏三角形，无需 VBO
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    v_uv = uv;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
