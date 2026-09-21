#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outLogColor;

// Uniforms
uniform usampler2D uRawBayerTexture; // Raw 16-bit integer texture (GL_R16UI)
uniform ivec2 uSensorResolution;    // e.g. ivec2(4080, 3064)
uniform int uBayerPattern;          // 0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR
uniform vec4 uBlackLevel;           // [BL_R, BL_Gr, BL_Gb, BL_B] ADC black level (~256.0)
uniform float uWhiteLevel;          // ADC white level (~4095.0)
uniform mat3 uColorMatrix;          // Sensor-to-Linear-BT.2020 matrix (column-major)
uniform vec3 uColorGains;            // White balance channel gains
uniform float uExposureGain;         // Exposure normalization factor (maps 18% grey to 0.1800)
uniform int uLogCurveType;           // 0: Pixel-Log, 1: Sony S-Log3, 2: Apple Log

// Pixel-Log Analytical Constants
const float PIXEL_LOG_ALPHA = 0.09499002;
const float PIXEL_LOG_BETA  = -0.21565232;
const float PIXEL_LOG_INV_S = 222.22222222; // 1.0 / 0.00450000
const float PIXEL_LOG_R0    = -0.02100000;

// Returns CFA Channel Index: 0: R, 1: Gr, 2: Gb, 3: B
int getCfaChannel(ivec2 p, int pattern) {
    int px = p.x & 1;
    int py = p.y & 1;
    int idx = py * 2 + px; // 0:(0,0), 1:(1,0), 2:(0,1), 3:(1,1)

    // Lookup for Bayer pattern ordering matching CameraCharacteristics
    // 0: RGGB -> [(0,0)=R,  (1,0)=Gr, (0,1)=Gb, (1,1)=B ]
    // 1: GRBG -> [(0,0)=Gr, (1,0)=R,  (0,1)=B,  (1,1)=Gb]
    // 2: GBRG -> [(0,0)=Gb, (1,0)=B,  (0,1)=R,  (1,1)=Gr]
    // 3: BGGR -> [(0,0)=B,  (1,0)=Gb, (0,1)=Gr, (1,1)=R ]
    if (pattern == 0) {
        return (idx == 0) ? 0 : (idx == 1) ? 1 : (idx == 2) ? 2 : 3;
    } else if (pattern == 1) {
        return (idx == 0) ? 1 : (idx == 1) ? 0 : (idx == 2) ? 3 : 2;
    } else if (pattern == 2) {
        return (idx == 0) ? 2 : (idx == 1) ? 3 : (idx == 2) ? 0 : 1;
    } else {
        return (idx == 0) ? 3 : (idx == 1) ? 2 : (idx == 2) ? 1 : 0;
    }
}

// Fetch raw sample with hardware clamp and dynamic black level subtraction
float fetchLinearSample(ivec2 p, int pattern) {
    p = clamp(p, ivec2(0), uSensorResolution - ivec2(1));
    uint rawInt = texelFetch(uRawBayerTexture, p, 0).r;
    float raw = float(rawInt);
    int cfa = getCfaChannel(p, pattern);
    float bl = uBlackLevel[cfa];
    float wl = uWhiteLevel;

    return clamp((raw - bl) / max(wl - bl, 1e-6), 0.0, 1.0);
}

// Branchless Pixel-Log OETF: Evaluates in 4 FLOPs + 1 SQRT + 1 LOG2 per channel
vec3 applyPixelLogOETF(vec3 linearRgb) {
    vec3 x = (linearRgb - vec3(PIXEL_LOG_R0)) * PIXEL_LOG_INV_S;
    vec3 r = x + sqrt(x * x + vec3(1.0));
    return vec3(PIXEL_LOG_ALPHA) * log2(r) + vec3(PIXEL_LOG_BETA);
}

// Sony S-Log3 OETF
vec3 applySLog3OETF(vec3 linearRgb) {
    vec3 outLog;
    for (int i = 0; i < 3; ++i) {
        float x = max(linearRgb[i], 0.0);
        if (x >= 0.01125) {
            outLog[i] = (420.0 + (log2((x + 0.01) / 0.19) / 3.32192809) * 261.5) / 1023.0;
        } else {
            outLog[i] = (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
        }
    }
    return outLog;
}

// Apple Log OETF
vec3 applyAppleLogOETF(vec3 linearRgb) {
    vec3 outLog;
    for (int i = 0; i < 3; ++i) {
        float x = max(linearRgb[i], 0.0);
        if (x >= 0.01) {
            outLog[i] = 0.185638 * log(5.367655 * x + 0.092809) + 0.677208;
        } else {
            outLog[i] = 17.536006 * x + 0.042857;
        }
    }
    return outLog;
}

vec3 applyLogOETF(vec3 linearRgb, int curveType) {
    if (curveType == 1) {
        return applySLog3OETF(linearRgb);
    } else if (curveType == 2) {
        return applyAppleLogOETF(linearRgb);
    } else {
        return applyPixelLogOETF(linearRgb);
    }
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int centerCfa = getCfaChannel(p, uBayerPattern);

    // 3x3 Texture Neighborhood Fetch (Zero-overhead L1 cache hits, <=24 registers)
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

    } else if (centerCfa == 1) { // Center is Green on Red row (Gr)
        debayeredRgb.g = c00;
        debayeredRgb.r = 0.5 * (cW + cE);
        debayeredRgb.b = 0.5 * (cN + cS);

    } else { // Center is Green on Blue row (Gb)
        debayeredRgb.g = c00;
        debayeredRgb.b = 0.5 * (cW + cE);
        debayeredRgb.r = 0.5 * (cN + cS);
    }

    // Color Space Transformation: White balance -> Sensor RGB to Linear BT.2020 -> Exposure Gain
    vec3 wbRgb = debayeredRgb * uColorGains;
    vec3 linearWorking = uColorMatrix * wbRgb;
    vec3 linearExposed = max(linearWorking * uExposureGain, vec3(0.0));

    // Transfer Function: Dynamic Log Curve (0: Pixel-Log, 1: Sony S-Log3, 2: Apple Log)
    vec3 logOutput = clamp(applyLogOETF(linearExposed, uLogCurveType), 0.0, 1.0);

    // Output clean 10-bit Log
    outLogColor = vec4(logOutput, 1.0);
}
