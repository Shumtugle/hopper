#version 300 es
// A line of text drawn once into a texture, shown on a quad.
layout(location = 0) in vec2 aCorner;   // -1..1
uniform vec4 uRect;                     // centre x, y and half width, height, clip space
out vec2 vUv;
void main() {
    vUv = vec2(aCorner.x * 0.5 + 0.5, 0.5 - aCorner.y * 0.5);
    gl_Position = vec4(uRect.xy + aCorner * uRect.zw, 0.0, 1.0);
}
