#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp sampler2D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outLogColor;

// Raw Sensor Uniforms
uniform usampler2D uRawBayerTexture; // Raw 16-bit integer texture (GL_R16UI)
uniform sampler2D  uLensShadingMap;   // Bilinear 2D Lens Shading Map (GL_RGBA16F or GL_RGBA32F)
uniform ivec2 uSensorResolution;    // Sensor resolution e.g. (4080, 3064)
uniform int uBayerPattern;          // 0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR
uniform vec4 uBlackLevel;           // Dynamic black level [R, Gr, Gb, B] (~256.0)
uniform float uWhiteLevel;          // ADC white level (~4095.0)
uniform int uHasLensShading;        // 1 if lens shading map is available, 0 otherwise

// Color Science Uniforms (Phase 2 & Phase 3)
uniform vec3 uNeutralColorPoint;    // SENSOR_NEUTRAL_COLOR_POINT [Rn, Gn, Bn]
uniform mat3 uCompositeMatrix;      // Sensor -> Bradford -> Rec.2020 Exposed (column-major)
uniform int uLogCurveType;          // 0: Pixel-Log, 1: Sony S-Log3, 2: Apple Log

// Pixel-Log Analytical Curve Parameters (populated from log_params.json)
uniform float uLogYb;
uniform float uLogBeta;
uniform float uLogGamma;
uniform float uLogDelta;
uniform float uLogS;

// Returns CFA Channel Index: 0: R, 1: Gr, 2: Gb, 3: B
int getCfaChannel(ivec2 p, int pattern) {
    int px = p.x & 1;
    int py = p.y & 1;
    int idx = (py << 1) | px; // 0:(0,0), 1:(1,0), 2:(0,1), 3:(1,1)

    if (pattern == 0) {
        return (idx == 0) ? 0 : (idx == 1) ? 1 : (idx == 2) ? 2 : 3; // RGGB
    } else if (pattern == 1) {
        return (idx == 0) ? 1 : (idx == 1) ? 0 : (idx == 2) ? 3 : 2; // GRBG
    } else if (pattern == 2) {
        return (idx == 0) ? 2 : (idx == 1) ? 3 : (idx == 2) ? 0 : 1; // GBRG
    } else {
        return (idx == 0) ? 3 : (idx == 1) ? 2 : (idx == 2) ? 1 : 0; // BGGR
    }
}

// Fetch raw sample with per-channel black level subtraction.
// DO NOT clamp negatives to 0.0 (Phase 2.1) so dark noise is preserved.
float fetchLinearSample(ivec2 p, int pattern) {
    p = clamp(p, ivec2(0), uSensorResolution - ivec2(1));
    uint rawInt = texelFetch(uRawBayerTexture, p, 0).r;
    float raw = float(rawInt);

    // 2D Spatial quad index: 0:(0,0) TL, 1:(1,0) TR, 2:(0,1) BL, 3:(1,1) BR
    // Matches Android SENSOR_BLACK_LEVEL_PATTERN specification
    int quadIdx = ((p.y & 1) << 1) | (p.x & 1);
    float bl = uBlackLevel[quadIdx];
    float wl = uWhiteLevel;

    // Linear un-clamped normalization
    return (raw - bl) / max(wl - bl, 1e-6);
}

// Phase 3: Branchless C1 Log2 + Linear Toe Transfer Function
// x >= 0: y = gamma * log2(x + beta) + delta
// x < 0:  y = yb + s * x
vec3 applyPixelLogOETF(vec3 x) {
    vec3 logArg = max(x + vec3(uLogBeta), vec3(1e-8));
    vec3 logVal = vec3(uLogGamma) * (log(logArg) * 1.4426950408889634) + vec3(uLogDelta);
    vec3 toeVal = vec3(uLogYb) + vec3(uLogS) * x;
    vec3 isNonNeg = step(vec3(0.0), x);
    return mix(toeVal, logVal, isNonNeg);
}

// Sony S-Log3 OETF (Fallback)
float sLog3Single(float x) {
    if (x >= 0.01125) {
        return (420.0 + (log2((x + 0.01) / 0.19) / 3.32192809) * 261.5) / 1023.0;
    } else {
        return (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
    }
}
vec3 applySLog3OETF(vec3 R) {
    return vec3(sLog3Single(max(R.r, 0.0)), sLog3Single(max(R.g, 0.0)), sLog3Single(max(R.b, 0.0)));
}

// Apple Log OETF (Fallback)
float appleLogSingle(float R) {
    if (R >= 0.01) {
        return 0.185638 * log(5.367655 * R + 0.092809) + 0.677208;
    } else if (R >= -0.05641088) {
        float diff = R - (-0.05641088);
        return 47.28711236 * diff * diff;
    } else {
        return 0.0;
    }
}
vec3 applyAppleLogOETF(vec3 R) {
    return vec3(appleLogSingle(R.r), appleLogSingle(R.g), appleLogSingle(R.b));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int centerCfa = getCfaChannel(p, uBayerPattern);

    // 3x3 Texture Neighborhood Fetch
    float c00 = fetchLinearSample(p, uBayerPattern);
    float cN  = fetchLinearSample(p + ivec2( 0, -1), uBayerPattern);
    float cS  = fetchLinearSample(p + ivec2( 0,  1), uBayerPattern);
    float cW  = fetchLinearSample(p + ivec2(-1,  0), uBayerPattern);
    float cE  = fetchLinearSample(p + ivec2( 1,  0), uBayerPattern);
    float cNW = fetchLinearSample(p + ivec2(-1, -1), uBayerPattern);
    float cNE = fetchLinearSample(p + ivec2( 1, -1), uBayerPattern);
    float cSW = fetchLinearSample(p + ivec2(-1,  1), uBayerPattern);
    float cSE = fetchLinearSample(p + ivec2( 1,  1), uBayerPattern);

    vec3 debayeredRgb;
    if (centerCfa == 0) { // Center is RED
        debayeredRgb.r = c00;
        float dH = abs(cW - cE);
        float dV = abs(cN - cS);
        debayeredRgb.g = (dH < dV) ? 0.5 * (cW + cE) : (dV < dH) ? 0.5 * (cN + cS) : 0.25 * (cW + cE + cN + cS);
        debayeredRgb.b = 0.25 * (cNW + cNE + cSW + cSE);
    } else if (centerCfa == 3) { // Center is BLUE
        debayeredRgb.b = c00;
        float dH = abs(cW - cE);
        float dV = abs(cN - cS);
        debayeredRgb.g = (dH < dV) ? 0.5 * (cW + cE) : (dV < dH) ? 0.5 * (cN + cS) : 0.25 * (cW + cE + cN + cS);
        debayeredRgb.r = 0.25 * (cNW + cNE + cSW + cSE);
    } else if (centerCfa == 1) { // Center is Gr
        debayeredRgb.g = c00;
        debayeredRgb.r = 0.5 * (cW + cE);
        debayeredRgb.b = 0.5 * (cN + cS);
    } else { // Center is Gb
        debayeredRgb.g = c00;
        debayeredRgb.b = 0.5 * (cW + cE);
        debayeredRgb.r = 0.5 * (cN + cS);
    }

    // Step 2: Lens Shading Correction (Phase 2.2: Apply before white balance)
    if (uHasLensShading == 1) {
        vec2 normUv = vec2(p) / vec2(uSensorResolution);
        vec4 lsGains = texture(uLensShadingMap, normUv); // [R, Gr, Gb, B]
        debayeredRgb.r *= lsGains.r;
        debayeredRgb.g *= 0.5 * (lsGains.g + lsGains.b);
        debayeredRgb.b *= lsGains.a;
    }

    // Step 3: White Balance & Post-WB Highlight Clamping (Phase 2.3)
    // Divide by SENSOR_NEUTRAL_COLOR_POINT [Rn, Gn, Bn]
    vec3 neutral = max(uNeutralColorPoint, vec3(1e-4));
    vec3 wbRgb = debayeredRgb / neutral;

    // Highlight Clamp: Ensure clipped highlights stay neutral white rather than magenta
    wbRgb = min(wbRgb, vec3(1.0));

    // Step 4 & 5 & 6 & 7: Camera -> XYZ(D50) -> Bradford D65 -> Rec.2020 Linear + Exposure Gain
    // Evaluated via pre-composed composite matrix
    vec3 linearWorking = uCompositeMatrix * wbRgb;

    // Step 8: Apply Selected Log OETF
    vec3 logOutput;
    if (uLogCurveType == 1) {
        logOutput = applySLog3OETF(linearWorking);
    } else if (uLogCurveType == 2) {
        logOutput = applyAppleLogOETF(linearWorking);
    } else {
        logOutput = applyPixelLogOETF(linearWorking);
    }

    // Final 10-bit output clamp [0.0, 1.0]
    outLogColor = vec4(clamp(logOutput, 0.0, 1.0), 1.0);
}
