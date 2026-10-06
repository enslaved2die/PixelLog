#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp sampler2D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outLogColor;
layout(location = 1) out vec4 outHistoryColor;

// Raw Sensor Uniforms
uniform usampler2D uRawBayerTexture; // Raw 16-bit integer texture (GL_R16UI)
uniform sampler2D  uLensShadingMap;   // Bilinear 2D Lens Shading Map (GL_RGBA16F or GL_RGBA32F)
uniform ivec2 uSensorResolution;    // Sensor resolution e.g. (4080, 3064)
uniform int uBayerPattern;          // 0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR
uniform vec4 uBlackLevel;           // Dynamic black level [R, Gr, Gb, B] (~256.0)
uniform float uWhiteLevel;          // ADC white level (~4095.0)
uniform int uHasLensShading;        // 1 if lens shading map is available, 0 otherwise

// Color Science Uniforms
uniform vec3 uNeutralColorPoint;    // SENSOR_NEUTRAL_COLOR_POINT [Rn, Gn, Bn]
uniform mat3 uCompositeMatrix;      // Sensor -> Bradford -> Rec.2020 Exposed (column-major)

// Pixel-Log Analytical Curve Parameters
uniform float uLogYb;
uniform float uLogBeta;
uniform float uLogGamma;
uniform float uLogDelta;
uniform float uLogS;

// EDR (Expanded Dynamic Range) Uniforms
uniform sampler2D uHistoryTexture;  // Ping-pong temporal history buffer (Pixel-Log space)
uniform int uTnrPrime;              // 1: Prime history without temporal blending, 0: Active TNR
uniform int uHlrEnabled;            // 1: Active CFA highlight reconstruction & specular roll-off, 0: Disabled
uniform int uTnrMode;               // 0: Disabled, 1: HANDHELD (TAA neighborhood clamp), 2: STATIC (Full bilateral)

// Returns CFA Channel Index: 0: R, 1: Gr, 2: Gb, 3: B
int getCfaChannel(ivec2 p, int pattern) {
    int idx = ((p.y & 1) << 1) | (p.x & 1);
    if (pattern == 0) {
        return idx;
    } else if (pattern == 1) {
        return (idx == 0) ? 1 : (idx == 1) ? 0 : (idx == 2) ? 3 : 2;
    } else if (pattern == 2) {
        return (idx == 0) ? 2 : (idx == 1) ? 3 : (idx == 2) ? 0 : 1;
    } else {
        return 3 - idx;
    }
}

// Fetch raw sample with per-channel black level subtraction.
float fetchLinearSample(ivec2 p, int pattern) {
    p = clamp(p, ivec2(0), uSensorResolution - ivec2(1));
    uint rawInt = texelFetch(uRawBayerTexture, p, 0).r;
    float raw = float(rawInt);

    int quadIdx = ((p.y & 1) << 1) | (p.x & 1);
    float bl = uBlackLevel[quadIdx];
    float wl = uWhiteLevel;

    return (raw - bl) / max(wl - bl, 1e-6);
}

// Pixel-Log Forward OETF
vec3 applyPixelLogOETF(vec3 x) {
    vec3 logArg = max(x + vec3(uLogBeta), vec3(1e-8));
    vec3 logVal = vec3(uLogGamma) * (log(logArg) * 1.4426950408889634) + vec3(uLogDelta);
    vec3 toeVal = vec3(uLogYb) + vec3(uLogS) * x;
    vec3 isNonNeg = step(vec3(0.0), x);
    return mix(toeVal, logVal, isNonNeg);
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

    // 3x3 Directional Bilinear Debayer
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

    // Lens Shading Correction
    if (uHasLensShading == 1) {
        vec2 normUv = vec2(p) / vec2(uSensorResolution);
        vec4 lsGains = texture(uLensShadingMap, normUv);
        debayeredRgb.r *= lsGains.r;
        debayeredRgb.g *= 0.5 * (lsGains.g + lsGains.b);
        debayeredRgb.b *= lsGains.a;
    }

    vec3 neutral = max(uNeutralColorPoint, vec3(1e-4));

    // =========================================================================
    // HIGHLIGHT RECOVERY (HLR)
    // =========================================================================
    // 1. Channel reconstruction for partially saturated highlights:
    // If 1 or 2 channels saturate near ADC white level ceiling (0.98), extrapolate
    // using the unclipped channel's luminance ratio relative to the neutral point.
    const float clipCeiling = 0.98;
    vec3 rawClipped = step(vec3(clipCeiling), debayeredRgb);
    float numClipped = rawClipped.r + rawClipped.g + rawClipped.b;

    vec3 wbRgb;
    if (uHlrEnabled == 1) {
        // Highlight Recovery (HLR): reconstruct saturated channels using unclipped channel ratios
        const float clipCeiling = 0.98;
        vec3 rawClipped = step(vec3(clipCeiling), debayeredRgb);
        float numClipped = rawClipped.r + rawClipped.g + rawClipped.b;

        if (numClipped >= 1.0 && numClipped < 3.0) {
            float unclippedRef = 0.0;
            float neutralRef = 0.0;
            if (rawClipped.g < 0.5) {
                unclippedRef = debayeredRgb.g;
                neutralRef = neutral.g;
            } else if (rawClipped.r < 0.5) {
                unclippedRef = debayeredRgb.r;
                neutralRef = neutral.r;
            } else {
                unclippedRef = debayeredRgb.b;
                neutralRef = neutral.b;
            }

            if (unclippedRef > 0.0 && neutralRef > 0.0) {
                float estimatedLuma = unclippedRef / neutralRef;
                if (rawClipped.r > 0.5) {
                    debayeredRgb.r = max(debayeredRgb.r, estimatedLuma * neutral.r);
                }
                if (rawClipped.g > 0.5) {
                    debayeredRgb.g = max(debayeredRgb.g, estimatedLuma * neutral.g);
                }
                if (rawClipped.b > 0.5) {
                    debayeredRgb.b = max(debayeredRgb.b, estimatedLuma * neutral.b);
                }
            }
        }

        // White Balance
        wbRgb = debayeredRgb / neutral;

        // 2. Highlight roll-off & specular neutral desaturation:
        // Avoid harsh magenta/cyan discoloration in specular highlights
        float maxWb = max(wbRgb.r, max(wbRgb.g, wbRgb.b));
        if (maxWb > 1.0) {
            float excess = maxWb - 1.0;
            float desatFactor = clamp(excess / 2.0, 0.0, 1.0);
            wbRgb = mix(wbRgb, vec3(maxWb), desatFactor * 0.7);
        }
    } else {
        wbRgb = debayeredRgb / neutral;
        wbRgb = min(wbRgb, vec3(1.0));
    }

    // Sensor RGB -> Linear Working Gamut (BT.2020) via Composite Matrix
    vec3 linearWorking = uCompositeMatrix * wbRgb;

    // Apply Pixel-Log OETF to current frame
    vec3 logCurrent = applyPixelLogOETF(linearWorking);
    logCurrent = clamp(logCurrent, 0.0, 1.0);

    // =========================================================================
    // TEMPORAL NOISE REDUCTION (TNR) / SHADOW RECOVERY
    // =========================================================================
    vec3 finalLogColor;
    if (uTnrMode > 0) {
        if (uTnrPrime == 1) {
            // First frame prime: zero history blending to prevent strobe flashes
            finalLogColor = logCurrent;
        } else if (uTnrMode == 1) {
            // Mode 1: HANDHELD (TAA-style neighborhood color clamping + motion gating)
            vec3 logPrev = texelFetch(uHistoryTexture, p, 0).rgb;
            vec3 boxMin = max(logCurrent - vec3(0.05), vec3(0.0));
            vec3 boxMax = min(logCurrent + vec3(0.05), vec3(1.0));
            vec3 clampedPrev = clamp(logPrev, boxMin, boxMax);

            vec3 diff3 = abs(logCurrent - clampedPrev);
            float diffLuma = dot(diff3, vec3(0.2627, 0.6780, 0.0593));
            float maxDiff = max(diffLuma, max(diff3.r, max(diff3.g, diff3.b)) * 0.75);

            float avgLuma = dot(logCurrent, vec3(0.2627, 0.6780, 0.0593));
            float shadowWeight = clamp(1.0 - (avgLuma - 0.09) / 0.35, 0.0, 1.0);
            float motionFactor = smoothstep(0.03, 0.14, maxDiff);

            float maxHistoryAlpha = mix(0.10, 0.55, shadowWeight);
            float historyAlpha = maxHistoryAlpha * (1.0 - motionFactor);

            finalLogColor = mix(logCurrent, clampedPrev, historyAlpha);
        } else {
            // Mode 2: STATIC (Full bilateral temporal denoising for tripod / locked-off shots)
            vec3 logPrev = texelFetch(uHistoryTexture, p, 0).rgb;
            vec3 diff3 = abs(logCurrent - logPrev);
            float diffLuma = dot(diff3, vec3(0.2627, 0.6780, 0.0593));
            float maxDiff = max(diffLuma, max(diff3.r, max(diff3.g, diff3.b)) * 0.75);

            float avgLuma = dot(logCurrent, vec3(0.2627, 0.6780, 0.0593));
            float shadowWeight = clamp(1.0 - (avgLuma - 0.09) / 0.35, 0.0, 1.0);
            float motionFactor = smoothstep(0.05, 0.22, maxDiff);

            float maxHistoryAlpha = mix(0.15, 0.85, shadowWeight);
            float historyAlpha = maxHistoryAlpha * (1.0 - motionFactor);

            finalLogColor = mix(logCurrent, logPrev, historyAlpha);
        }
    } else {
        finalLogColor = logCurrent;
    }

    vec4 outColor = vec4(clamp(finalLogColor, 0.0, 1.0), 1.0);
    outLogColor = outColor;
    outHistoryColor = outColor;
}
