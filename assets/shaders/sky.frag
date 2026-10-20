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
    col += stars(d);
    if (uGold > 0.0) {
        // the dawn: a rose dome over the gold band, and the sun on the horizon
        // a rose dome, deeper overhead, and the sun small and low with a warm glow round it
        col += uGold * mix(vec3(0.5, 0.12, 0.25), vec3(0.22, 0.05, 0.16), smoothstep(0.0, 1.0, d.y)) * 0.5 * smoothstep(-0.05, 0.15, d.y);
        float s = dot(d, normalize(SUN));
        col += uGold * (vec3(1.0, 0.8, 0.5) * 5.0 * smoothstep(0.9992, 0.9997, s) + vec3(1.0, 0.45, 0.12) * 0.7 * pow(max(s, 0.0), 120.0));
    }
    o = vec4(tonemap(col), 1.0);
}
