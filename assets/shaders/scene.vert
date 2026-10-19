#version 300 es
// Islands and everything on them, already in world space.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec2 aTag;   // x: film thickness or hue, y: shade or material
uniform mat4 uMvp;
out vec3 vN;
out vec3 vW;
out vec2 vTag;
void main() {
    vW = aPos;
    vN = aNor;
    vTag = aTag;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
