#version 300 es
// The black surface: one wide square at the height of the liquid.
layout(location = 0) in vec2 aPos;   // world x, z
uniform mat4 uMvp;
uniform float uLevel;
out vec3 vW;
void main() {
    vW = vec3(aPos.x, uLevel, aPos.y);
    gl_Position = uMvp * vec4(vW, 1.0);
}
