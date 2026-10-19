#version 300 es
precision highp float;
#include common
// What lies above: black, but for the faint band of the horizon and a scatter of far lights.
in vec2 vClip;
out vec4 o;
uniform vec3 uRight;
uniform vec3 uUp;
uniform vec3 uAhead;
uniform vec2 uSpan;   // tangent of half the view angle, across and up

void main() {
    vec3 d = normalize(uAhead + uRight * vClip.x * uSpan.x + uUp * vClip.y * uSpan.y);
    vec3 col = haze(vec3(d.x, max(d.y, 0.0), d.z));
    // far lights: one cell in a few hundred of a fine grid on the sky holds a dim point
    vec2 g = vec2(atan(d.z, d.x) * 120.0, d.y * 160.0);
    vec2 cell = floor(g);
    float seed = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
    vec2 at = fract(g) - 0.5 - (vec2(fract(seed * 13.7), fract(seed * 71.3)) - 0.5) * 0.6;
    float star = step(0.985, seed) * (1.0 - smoothstep(0.0, 0.18, length(at))) * smoothstep(0.0, 0.15, d.y);
    col += vec3(0.7, 0.75, 0.9) * star * 0.25 * fract(seed * 997.0);
    o = vec4(tonemap(col), 1.0);
}
