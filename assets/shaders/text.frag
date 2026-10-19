#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 o;
uniform sampler2D uText;
uniform float uAlpha;
void main() {
    float a = texture(uText, vUv).a * uAlpha;
    o = vec4(vec3(0.92, 0.94, 1.0) * a, a);
}
