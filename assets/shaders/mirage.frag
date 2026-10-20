#version 300 es
precision highp float;
#include common
// A mirage: an upright sheet of quicksilver on an island, always turned to the drop. It shows
// the drop and next to nothing else, through a shimmer, the way hot air shows what is not there.
in vec3 vN;
in vec3 vW;
in vec2 vTag;   // x: across the sheet, -1 to 1; y: up it, 0 to 1
out vec4 o;
uniform vec3 uEye;
uniform vec4 uDrop;   // the drop's centre and radius
uniform float uTime;

void main() {
    vec3 n = normalize(vN);
    vec3 side = vec3(-n.z, 0.0, n.x);
    // the face wavers in slow crossing ripples, and what it shows wavers with it
    // thin bands sliding up, as over hot ground, on a slower sway
    float w1 = sin(vTag.y * 34.0 - uTime * 4.0 + sin(vTag.x * 2.0 + uTime * 0.7) * 1.2);
    float w2 = sin(vTag.y * 7.0 + vTag.x * 3.0 - uTime * 1.3);
    vec3 m = normalize(n + side * (0.009 * w1 + 0.012 * w2) + vec3(0.0, 0.008, 0.0) * w2);
    vec3 d = normalize(vW - uEye);
    vec3 r = reflect(d, m);
    // the drop, looked for along the reflected ray
    vec3 oc = vW - uDrop.xyz;
    float b = dot(oc, r), h = b * b - dot(oc, oc) + uDrop.w * uDrop.w;
    float t = -b - sqrt(max(h, 0.0));
    vec3 col;
    float a;
    if (h > 0.0 && t > 0.0) {
        // quicksilver seen in quicksilver
        vec3 nb = normalize(vW + r * t - uDrop.xyz);
        vec3 rr = reflect(r, nb);
        float c = clamp(dot(nb, -r), 0.0, 1.0);
        float fres = 0.75 + 0.25 * pow(1.0 - c, 4.0);
        vec3 room = vec3(0.15, 0.16, 0.20) * smoothstep(-0.3, 1.0, rr.y);
        col = (0.04 + (env(rr) + haze(rr) + room) * 1.3) * fres * vec3(0.92, 0.95, 1.0);
        a = 0.82;
    } else {
        // elsewhere the sheet is dark and thin: the far band of light and the stars, faintly
        col = vec3(0.01, 0.012, 0.016) + haze(r) * 3.0 + stars(r) * 1.5 + env(r) * 0.2;
        a = 0.4;
    }
    // a faint sheen across the face, stronger at a glance
    col += vec3(0.6, 0.65, 0.8) * 0.06 * pow(1.0 - abs(dot(n, d)), 3.0);
    // no frame: the edges fray and waver with the rest
    float edge = min(1.0 - abs(vTag.x), min(vTag.y, 1.0 - vTag.y) * 2.0) + 0.05 * w1;
    a *= smoothstep(0.0, 0.3, edge);
    o = vec4(tonemap(col * fog(length(uEye - vW))), a);
}
