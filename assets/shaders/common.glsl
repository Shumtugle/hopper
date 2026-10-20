// Shared by every pass: the oxide film and the invisible studio around the scene.
// Directions here are y-up.

// Colour of light reflected by a thin transparent film on a mirror.
// d is the thickness in nanometres, c the cosine of the angle inside the film.
vec3 film(float d, float c) {
    float k = 12.566 * 2.0 * (d + 80.0) * c;
    float i450 = 0.5 + 0.5 * cos(k / 450.0);
    float i500 = 0.5 + 0.5 * cos(k / 500.0);
    float i550 = 0.5 + 0.5 * cos(k / 550.0);
    float i600 = 0.5 + 0.5 * cos(k / 600.0);
    float i650 = 0.5 + 0.5 * cos(k / 650.0);
    vec3 f = vec3(0.45 * i600 + 0.55 * i650, 0.55 * i550 + 0.45 * i500, 0.8 * i450 + 0.2 * i500);
    f = pow(f, vec3(1.7)) * 1.45;
    return mix(vec3(0.55), f, 0.94);
}

// Three soft lights nobody sees directly: a big panel overhead, a thin strip at the side,
// a dim wash behind. Everything below the horizon is black.
vec3 env(vec3 r) {
    vec3 c = vec3(0.0);
    float key = dot(r, normalize(vec3(-0.30, 1.0, -0.22)));
    c += vec3(1.0, 0.95, 0.88) * 6.0 * smoothstep(0.966, 0.978, key);
    vec3 sc = normalize(vec3(0.62, 0.78, 0.05));
    vec3 sn = normalize(cross(vec3(0.0, 0.2, 1.0), sc));
    float across = abs(dot(r, sn));
    c += vec3(0.75, 0.88, 1.0) * 4.0 * (1.0 - smoothstep(0.018, 0.032, across)) * smoothstep(0.80, 0.86, dot(r, sc));
    float wash = dot(r, normalize(vec3(0.1, 0.8, -0.62)));
    c += vec3(0.55, 0.62, 0.8) * 0.35 * smoothstep(0.86, 0.97, wash);
    return c;
}

vec3 tonemap(vec3 c) {
    return pow(c / (1.0 + c), vec3(1.0 / 2.2));
}

// A faint band of light all round the horizon, so the black surface has an edge to it
// and the islands a rim. Only the first-person view needs it.
vec3 haze(vec3 r) {
    float k = exp(-r.y * r.y * 220.0);
    return vec3(0.32, 0.36, 0.5) * 0.045 * k;
}

// Everything far away melts into the black.
float fog(float dist) {
    return exp(-max(0.0, dist - 2.5) * 0.05);
}

// Far lights: one cell in a few hundred of a fine grid on the sky holds a dim point.
vec3 stars(vec3 d) {
    vec2 g = vec2(atan(d.z, d.x) * 120.0, d.y * 160.0);
    vec2 cell = floor(g);
    float seed = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
    vec2 at = fract(g) - 0.5 - (vec2(fract(seed * 13.7), fract(seed * 71.3)) - 0.5) * 0.6;
    float star = step(0.985, seed) * (1.0 - smoothstep(0.0, 0.18, length(at))) * smoothstep(0.0, 0.15, d.y);
    return vec3(0.7, 0.75, 0.9) * star * 0.25 * fract(seed * 997.0);
}
