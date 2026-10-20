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
    o = vec4(tonemap(col), 1.0);
}
