#version 300 es
// One triangle that covers the screen.
layout(location = 0) in vec2 aPos;
out vec2 vClip;
void main() {
    vClip = aPos;
    gl_Position = vec4(aPos, 0.999, 1.0);
}
