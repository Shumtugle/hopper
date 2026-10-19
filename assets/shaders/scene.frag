#version 300 es
precision highp float;
#include common
// Bismuth under a film of oxide, and the few things that are not bismuth.
in vec3 vN;
in vec3 vW;
in vec2 vTag;
out vec4 o;
uniform vec3 uEye;
uniform float uTime;
uniform float uFilm;

// A rainbow from red to violet, as the shards glow.
vec3 spectrum(float h) {
    float x = h * 0.8;
    vec3 c = clamp(abs(mod(x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return c * c * (3.0 - 2.0 * c);
}

void main() {
    vec3 n = normalize(vN);
    vec3 v = normalize(uEye - vW);
    float c = clamp(dot(n, v), 0.0, 1.0);
    vec3 r = reflect(-v, n);
    float dist = length(uEye - vW);
    vec3 room = vec3(0.15, 0.16, 0.20) * smoothstep(-0.3, 1.0, r.y);
    vec3 e = env(r) + haze(r) + room;
    vec3 col;
    float tag = vTag.y;
    if (tag > 6.5) {
        // glass: almost black, its edges caught by the light, a ghost of a film
        float fres = 0.04 + 0.96 * pow(1.0 - c, 5.0);
        vec3 tint = film(vTag.x + uFilm, c);
        col = e * fres * vec3(0.75, 0.92, 1.0) + tint * 0.035 + vec3(0.01, 0.02, 0.025);
    } else if (tag > 5.5) {
        // hot bismuth: the film burns off towards red, and the metal glows from inside
        float inside = sqrt(1.0 - (1.0 - c * c) / 4.0);
        vec3 tint = film(vTag.x + 60.0 * sin(dot(vW, vec3(1.3, 0.9, 1.1))), inside) * vec3(1.0, 0.55, 0.4);
        float fres = 0.62 + 0.38 * pow(1.0 - c, 5.0);
        col = tint * (0.012 + e) * fres;
        float beat = 0.6 + 0.4 * sin(uTime * 3.1 + vW.x * 2.0 + vW.z * 1.3);
        col += vec3(1.0, 0.24, 0.04) * (0.45 + 0.7 * max(n.y, 0.0)) * beat;
    } else if (tag > 4.5) {
        // quicksilver: a bright curved mirror
        float fres = 0.75 + 0.25 * pow(1.0 - c, 4.0);
        col = (0.04 + e * 1.3) * fres * vec3(0.92, 0.95, 1.0);
    } else if (tag > 2.5) {
        // light of its own, brighter towards the edges of each face
        // white light is the gate's, kept softer than the shards so it does not burn out
        vec3 hue = vTag.x > 1.5 ? vec3(0.62, 0.64, 0.7) : spectrum(vTag.x);
        float pulse = 0.5 + 0.5 * sin(uTime * 2.4);
        col = hue * (1.4 + 0.5 * pulse + 0.8 * pow(1.0 - c, 2.0)) * 1.6;
        // light does not fade with the fog as fast as metal does
        o = vec4(tonemap(col * mix(1.0, fog(dist), 0.5)), 1.0);
        return;
    } else {
        // bismuth: a mirror under a film whose colour turns with the angle of view
        float inside = sqrt(1.0 - (1.0 - c * c) / 4.0);
        float bands = 40.0 * sin(dot(vW, vec3(1.3, 0.9, 1.1)));
        vec3 tint = film(vTag.x + bands + uFilm, inside);
        float fres = 0.62 + 0.38 * pow(1.0 - c, 5.0);
        col = tint * (0.012 + e) * fres * tag;
    }
    o = vec4(tonemap(col * fog(dist)), 1.0);
}
