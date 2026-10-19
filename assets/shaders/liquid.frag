#version 300 es
precision highp float;
#include common
// The black liquid: a mirror that shows nothing but the lights, and its own ripples.
in vec3 vW;
out vec4 o;
uniform sampler2D uField;   // r, g: slope; b: height; all offset by half
uniform vec3 uGrid;         // where the ripple grid lies: x0, z0, size
uniform vec3 uEye;
uniform float uFilm;

void main() {
    vec2 uv = (vW.xz - uGrid.xy) / uGrid.z;
    vec2 s = vec2(0.0);
    float h = 0.0;
    // past the grid the surface lies still; near its edge the ripples fade out
    float inside = smoothstep(0.0, 0.08, min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y)));
    if (inside > 0.0) {
        vec4 t = texture(uField, uv);
        s = (t.xy - 0.50196) * (255.0 / 300.0) * inside;
        h = (t.z - 0.50196) * (255.0 / 60.0) * inside;
    }
    vec3 n = normalize(vec3(-s.x * 1.6, 1.0, -s.y * 1.6));
    vec3 v = normalize(vW - uEye);
    vec3 r = reflect(v, n);
    r.y = abs(r.y);
    float c = clamp(dot(n, -v), 0.0, 1.0);
    float fres = 0.04 + 0.96 * pow(1.0 - c, 5.0);
    vec3 sheen = film(260.0 + h * 140.0 + uFilm, max(c, 0.2));
    vec3 col = (env(r) * 0.3 + haze(r) * 0.35) * fres * mix(vec3(1.0), sheen, clamp(length(s) * 6.0, 0.0, 0.75));
    o = vec4(tonemap(col * fog(length(vW - uEye))), 1.0);
}
