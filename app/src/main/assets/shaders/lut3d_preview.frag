#version 310 es
precision highp float;
precision highp sampler2D;
precision highp sampler3D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outDisplayColor;

uniform sampler2D uLogTexture; // 10-bit Log texture from Render Pass 1
uniform sampler3D uLut3D;      // 33x33x33 3D Look-Up Table

const float LUT_SIZE = 33.0;
const float LUT_SCALE = (LUT_SIZE - 1.0) / LUT_SIZE; // 32.0 / 33.0
const float LUT_OFFSET = 0.5 / LUT_SIZE;            // 0.5 / 33.0

void main() {
    // Sample 10-bit Log input texture
    vec3 logColor = texture(uLogTexture, vTexCoord).rgb;

    // Half-texel offset compensation ensures exact grid boundary interpolation
    vec3 lutCoord = clamp(logColor, 0.0, 1.0) * LUT_SCALE + LUT_OFFSET;

    // Trilinear filtering through 3D LUT converts Log to calibrated Rec.709 display color
    vec3 gradedRgb = texture(uLut3D, lutCoord).rgb;

    outDisplayColor = vec4(gradedRgb, 1.0);
}
