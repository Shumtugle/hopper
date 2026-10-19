#version 300 es
// Flat shapes over the scene, already in clip space.
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 aCol;
out vec4 vCol;
void main() {
    vCol = aCol;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
