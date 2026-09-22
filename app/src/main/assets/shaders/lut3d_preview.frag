#version 310 es
precision highp float;
precision highp sampler2D;
precision highp sampler3D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outDisplayColor;

uniform sampler2D uLogTexture; // 10-bit Log texture from Render Pass 1
uniform sampler3D uLut3D;      // 3D Look-Up Table (33x33x33, 65x65x65, or custom)

void main() {
    // Sample 10-bit Log input texture
    vec3 logColor = texture(uLogTexture, vTexCoord).rgb;

    // Dynamically query LUT dimension to ensure exact half-texel offset for any size
    float lutSize = float(textureSize(uLut3D, 0).x);
    float lutScale = (lutSize - 1.0) / lutSize;
    float lutOffset = 0.5 / lutSize;

    // Half-texel offset compensation ensures exact grid boundary interpolation
    vec3 lutCoord = clamp(logColor, 0.0, 1.0) * lutScale + lutOffset;

    // Trilinear filtering through 3D LUT converts Log to calibrated display color
    vec3 gradedRgb = texture(uLut3D, lutCoord).rgb;

    outDisplayColor = vec4(gradedRgb, 1.0);
}
