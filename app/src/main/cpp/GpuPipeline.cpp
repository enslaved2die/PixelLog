#include "GpuPipeline.h"
#include <unistd.h>

// Fullscreen Quad Vertices: Position (vec2) + TexCoord (vec2)
static const float QUAD_VERTICES[] = {
    // PosX, PosY,   TexU, TexV
    -1.0f, -1.0f,   0.0f, 0.0f,
     1.0f, -1.0f,   1.0f, 0.0f,
    -1.0f,  1.0f,   0.0f, 1.0f,
     1.0f,  1.0f,   1.0f, 1.0f,
};

// Display Quad Vertices: Position (vec2) + TexCoord (vec2)
// Invert TexV coordinates for display pass quad so that scene top (V=0) maps to screen top (PosY=+1).
static const float DISPLAY_QUAD_VERTICES[] = {
    // PosX, PosY,   TexU, TexV
    -1.0f, -1.0f,   0.0f, 1.0f,
     1.0f, -1.0f,   1.0f, 1.0f,
    -1.0f,  1.0f,   0.0f, 0.0f,
     1.0f,  1.0f,   1.0f, 0.0f,
};

// Embedded GLSL ES 3.10 Shaders
static const char* VERTEX_SHADER_SOURCE = R"glsl(#version 310 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)glsl";

static const char* DEBAYER_FRAGMENT_SHADER_SOURCE = R"glsl(#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp sampler2D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outLogColor;

// Raw Sensor Uniforms
uniform usampler2D uRawBayerTexture; // Raw 16-bit integer texture (GL_R16UI)
uniform sampler2D  uLensShadingMap;   // Bilinear 2D Lens Shading Map (GL_RGBA16F)
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

// Sony S-Log3
const float SL3_CUTOFF    = 0.01125000;
const float SL3_A         = 261.5 / 1023.0;
const float SL3_B         = 420.0 / 1023.0;
const float SL3_INV_019   = 1.0 / 0.19000000;
const float SL3_TOE_SCALE = 6.62194376;
const float SL3_TOE_OFF   = 95.0 / 1023.0;
const float LOG10_INV_E   = 0.4342944819;

// Apple Log
const float AL_GAMMA = 0.08550479;
const float AL_BETA  = 0.00964052;
const float AL_DELTA = 0.69336945;
const float AL_C     = 47.28711236;
const float AL_R0    = -0.05641088;
const float AL_RT    = 0.01000000;

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

    return (raw - bl) / max(wl - bl, 1e-6);
}

// Phase 3: Branchless C1 Log2 + Linear Toe Transfer Function
vec3 applyPixelLogOETF(vec3 x) {
    vec3 logArg = max(x + vec3(uLogBeta), vec3(1e-8));
    vec3 logVal = vec3(uLogGamma) * (log(logArg) * 1.4426950408889634) + vec3(uLogDelta);
    vec3 toeVal = vec3(uLogYb) + vec3(uLogS) * x;
    vec3 isNonNeg = step(vec3(0.0), x);
    return mix(toeVal, logVal, isNonNeg);
}

// Sony S-Log3 OETF
float sLog3Single(float x) {
    if (x >= SL3_CUTOFF) {
        float log10Val = log((x + 0.01) * SL3_INV_019) * LOG10_INV_E;
        return SL3_B + SL3_A * log10Val;
    } else {
        return x * SL3_TOE_SCALE + SL3_TOE_OFF;
    }
}
vec3 applySLog3OETF(vec3 R) {
    return vec3(sLog3Single(max(R.r, 0.0)), sLog3Single(max(R.g, 0.0)), sLog3Single(max(R.b, 0.0)));
}

// Apple Log OETF
float appleLogSingle(float R) {
    if (R >= AL_RT) {
        return AL_GAMMA * log2(R + AL_BETA) + AL_DELTA;
    } else if (R >= AL_R0) {
        float diff = R - AL_R0;
        return AL_C * diff * diff;
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

    // 3x3 Directional Bilinear Debayer
    vec3 debayeredRgb;
    if (centerCfa == 0) { // Center is Red
        debayeredRgb.r = c00;
        float dH = abs(cW - cE);
        float dV = abs(cN - cS);
        debayeredRgb.g = (dH < dV) ? 0.5 * (cW + cE) : (dV < dH) ? 0.5 * (cN + cS) : 0.25 * (cW + cE + cN + cS);
        debayeredRgb.b = 0.25 * (cNW + cNE + cSW + cSE);
    } else if (centerCfa == 3) { // Center is Blue
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

    // Step 2: Lens Shading Map (Phase 2.2: Apply before white balance)
    if (uHasLensShading == 1) {
        vec2 normUv = vec2(p) / vec2(uSensorResolution);
        vec4 lsGains = texture(uLensShadingMap, normUv); // [R, Gr, Gb, B]
        debayeredRgb.r *= lsGains.r;
        debayeredRgb.g *= 0.5 * (lsGains.g + lsGains.b);
        debayeredRgb.b *= lsGains.a;
    }

    // Step 3: White Balance using SENSOR_NEUTRAL_COLOR_POINT & Highlight Clamp (Phase 2.3)
    vec3 neutral = max(uNeutralColorPoint, vec3(1e-4));
    vec3 wbRgb = debayeredRgb / neutral;

    // Highlight Clamp: Ensure clipped highlights remain neutral (1.0) instead of magenta
    wbRgb = min(wbRgb, vec3(1.0));

    // Step 4-7: Sensor RGB -> Linear Working Gamut (BT.2020) via Composite Matrix
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

    outLogColor = vec4(clamp(logOutput, 0.0, 1.0), 1.0);
}
)glsl";

static const char* LUT_FRAGMENT_SHADER_SOURCE = R"glsl(#version 310 es
precision highp float;
precision highp sampler2D;
precision highp sampler3D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outDisplayColor;

uniform sampler2D uLogTexture;
uniform sampler3D uLut3D;

void main() {
    vec3 logColor = texture(uLogTexture, vTexCoord).rgb;
    float lutSize = float(textureSize(uLut3D, 0).x);
    float lutScale = (lutSize - 1.0) / lutSize;
    float lutOffset = 0.5 / lutSize;
    vec3 lutCoord = clamp(logColor, 0.0, 1.0) * lutScale + lutOffset;
    vec3 gradedRgb = texture(uLut3D, lutCoord).rgb;
    outDisplayColor = vec4(gradedRgb, 1.0);
}
)glsl";

static const char* PASSTHROUGH_FRAGMENT_SHADER_SOURCE = R"glsl(#version 310 es
precision highp float;
precision highp sampler2D;

in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

uniform sampler2D uTexture;

void main() {
    outColor = texture(uTexture, vTexCoord);
}
)glsl";

GpuPipeline::GpuPipeline()
    : mWidth(0),
      mHeight(0),
      mInitialized(false),
      mIsLutEnabled(true),
      mBakeLutToEncoder(false),
      mLogCurveType(0),
      mExposureGain(11.5200f),
      mEglDisplay(EGL_NO_DISPLAY),
      mEglConfig(nullptr),
      mEglContext(EGL_NO_CONTEXT),
      mEglEncoderSurface(EGL_NO_SURFACE),
      mEglDisplaySurface(EGL_NO_SURFACE),
      mEglPbufferSurface(EGL_NO_SURFACE),
      mHasPendingLut(false),
      mPendingLutClear(false),
      mOffscreenFbo(0),
      mOffscreenTexture(0),
      mLensShadingTexture(0),
      mLensShadingWidth(0),
      mLensShadingHeight(0),
      mDebayerProgram(0),
      mLutProgram(0),
      mPassthroughProgram(0),
      mQuadVbo(0),
      mDisplayQuadVbo(0),
      mEglCreateSyncKHR(nullptr),
      mEglDestroySyncKHR(nullptr),
      mEglWaitSyncKHR(nullptr),
      mEglDupNativeFenceFDANDROID(nullptr),
      mEglPresentationTimeANDROID(nullptr) {}

GpuPipeline::~GpuPipeline() {
    release();
}

bool GpuPipeline::initialize(int32_t width, int32_t height) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    mWidth = width;
    mHeight = height;

    if (!initEGL()) {
        LOGE("GpuPipeline: Failed to initialize EGL");
        return false;
    }

    if (!mImporter.initialize(mEglDisplay)) {
        LOGE("GpuPipeline: Failed to initialize ZeroCopyImporter");
        return false;
    }

    eglMakeCurrent(mEglDisplay, mEglPbufferSurface, mEglPbufferSurface, mEglContext);

    if (!initShaders()) {
        LOGE("GpuPipeline: Failed to compile and link shaders");
        eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return false;
    }

    if (!initFBO()) {
        LOGE("GpuPipeline: Failed to initialize offscreen FBO");
        eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return false;
    }

    // Initialize Fullscreen Quad VBO (Pass 1)
    glGenBuffers(1, &mQuadVbo);
    glBindBuffer(GL_ARRAY_BUFFER, mQuadVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_VERTICES), QUAD_VERTICES, GL_STATIC_DRAW);

    // Initialize Display Quad VBO with inverted V (Pass 3)
    glGenBuffers(1, &mDisplayQuadVbo);
    glBindBuffer(GL_ARRAY_BUFFER, mDisplayQuadVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(DISPLAY_QUAD_VERTICES), DISPLAY_QUAD_VERTICES, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    // CRITICAL: Unbind context from the initialization (UI) thread!
    // This allows mProcessingThread to claim mEglContext without EGL_BAD_ACCESS.
    eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    mInitialized = true;
    LOGI("GpuPipeline: Successfully initialized for %dx%d (unbound from init thread)", mWidth, mHeight);
    return true;
}

bool GpuPipeline::bindContextToCurrentThread() {
    if (mEglDisplay == EGL_NO_DISPLAY || mEglContext == EGL_NO_CONTEXT) return false;
    EGLBoolean ok = eglMakeCurrent(mEglDisplay, mEglPbufferSurface, mEglPbufferSurface, mEglContext);
    if (!ok) {
        LOGE("GpuPipeline: bindContextToCurrentThread failed with EGL error 0x%x", eglGetError());
        return false;
    }
    LOGI("GpuPipeline: Context successfully bound to processing thread (thread_id: %lu)", pthread_self());
    return true;
}

bool GpuPipeline::unbindContextFromCurrentThread() {
    if (mEglDisplay == EGL_NO_DISPLAY) return false;
    eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    LOGI("GpuPipeline: Context unbound from processing thread");
    return true;
}

bool GpuPipeline::initEGL() {
    mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL_NO_DISPLAY) {
        LOGE("GpuPipeline: eglGetDisplay failed");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(mEglDisplay, &major, &minor)) {
        LOGE("GpuPipeline: eglInitialize failed");
        return false;
    }

    // Unified EGLConfig: Prefer 10-Bit (RGBA_1010102) for encoder & display
    const EGLint configAttribs10Bit[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE,        10,
        EGL_GREEN_SIZE,      10,
        EGL_BLUE_SIZE,       10,
        EGL_ALPHA_SIZE,      2,
        EGL_DEPTH_SIZE,      0,
        EGL_STENCIL_SIZE,    0,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    if (!eglChooseConfig(mEglDisplay, configAttribs10Bit, &mEglConfig, 1, &numConfigs) || numConfigs == 0) {
        LOGW("GpuPipeline: 10-bit EGLConfig unavailable, falling back to 8-bit RGBA_8888");
        const EGLint configAttribs8Bit[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RED_SIZE,        8,
            EGL_GREEN_SIZE,      8,
            EGL_BLUE_SIZE,       8,
            EGL_ALPHA_SIZE,      8,
            EGL_DEPTH_SIZE,      0,
            EGL_STENCIL_SIZE,    0,
            EGL_NONE
        };
        eglChooseConfig(mEglDisplay, configAttribs8Bit, &mEglConfig, 1, &numConfigs);
    }

    // Unified Primary Context (GLES 3.1)
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    mEglContext = eglCreateContext(mEglDisplay, mEglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (mEglContext == EGL_NO_CONTEXT) {
        LOGE("GpuPipeline: Failed to create EGLContext (error 0x%x)", eglGetError());
        return false;
    }

    // 1x1 Pbuffer Surface for headless state
    const EGLint pbufferAttribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    mEglPbufferSurface = eglCreatePbufferSurface(mEglDisplay, mEglConfig, pbufferAttribs);
    if (mEglPbufferSurface == EGL_NO_SURFACE) {
        LOGE("GpuPipeline: Failed to create Pbuffer surface (error 0x%x)", eglGetError());
        return false;
    }

    // Hardware Sync & Presentation Extensions
    mEglCreateSyncKHR = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    mEglDestroySyncKHR = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
    mEglWaitSyncKHR = (PFNEGLWAITSYNCKHRPROC)eglGetProcAddress("eglWaitSyncKHR");
    mEglDupNativeFenceFDANDROID = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
    mEglPresentationTimeANDROID = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");

    return true;
}

GLuint GpuPipeline::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("GpuPipeline: Error compiling shader (%s): %s",
                 type == GL_VERTEX_SHADER ? "VERT" : "FRAG", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint GpuPipeline::createProgram(const char* vertSource, const char* fragSource) {
    GLuint vertShader = compileShader(GL_VERTEX_SHADER, vertSource);
    GLuint fragShader = compileShader(GL_FRAGMENT_SHADER, fragSource);
    if (!vertShader || !fragShader) return 0;

    GLuint program = glCreateProgram();
    glAttachShader(program, vertShader);
    glAttachShader(program, fragShader);
    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog.data());
            LOGE("GpuPipeline: Error linking program: %s", infoLog.data());
        }
        glDeleteProgram(program);
        program = 0;
    }

    glDeleteShader(vertShader);
    glDeleteShader(fragShader);
    return program;
}

bool GpuPipeline::initShaders() {
    mDebayerProgram = createProgram(VERTEX_SHADER_SOURCE, DEBAYER_FRAGMENT_SHADER_SOURCE);
    mLutProgram = createProgram(VERTEX_SHADER_SOURCE, LUT_FRAGMENT_SHADER_SOURCE);
    mPassthroughProgram = createProgram(VERTEX_SHADER_SOURCE, PASSTHROUGH_FRAGMENT_SHADER_SOURCE);
    return (mDebayerProgram != 0 && mLutProgram != 0 && mPassthroughProgram != 0);
}

bool GpuPipeline::initFBO() {
    glGenFramebuffers(1, &mOffscreenFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, mOffscreenFbo);

    glGenTextures(1, &mOffscreenTexture);
    glBindTexture(GL_TEXTURE_2D, mOffscreenTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // 10-bit RGBA texture allocation
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGB10_A2, mWidth, mHeight);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mOffscreenTexture, 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("GpuPipeline: Framebuffer incomplete: 0x%x", status);
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

bool GpuPipeline::setEncoderSurface(ANativeWindow* encoderWindow) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    releaseEncoderSurface();

    if (!encoderWindow || mEglDisplay == EGL_NO_DISPLAY) return false;

    // Synchronize native window buffer format with chosen EGLConfig
    EGLint nativeVisualId = 0;
    eglGetConfigAttrib(mEglDisplay, mEglConfig, EGL_NATIVE_VISUAL_ID, &nativeVisualId);
    if (nativeVisualId > 0) {
        int32_t ret = ANativeWindow_setBuffersGeometry(encoderWindow, 0, 0, nativeVisualId);
        LOGI("GpuPipeline: ANativeWindow_setBuffersGeometry on encoderWindow returned %d (nativeVisualId: %d)",
             ret, nativeVisualId);
    }

    const EGLint surfaceAttribs[] = { EGL_NONE };
    mEglEncoderSurface = eglCreateWindowSurface(mEglDisplay, mEglConfig, encoderWindow, surfaceAttribs);
    if (mEglEncoderSurface == EGL_NO_SURFACE) {
        LOGE("GpuPipeline: Failed to create encoder window surface (error 0x%x)", eglGetError());
        return false;
    }
    LOGI("GpuPipeline: Created encoder window surface %p", mEglEncoderSurface);
    return true;
}

bool GpuPipeline::setDisplaySurface(ANativeWindow* displayWindow) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    releaseDisplaySurface();

    if (!displayWindow || mEglDisplay == EGL_NO_DISPLAY) return false;

    // Synchronize native window buffer format with chosen EGLConfig
    EGLint nativeVisualId = 0;
    eglGetConfigAttrib(mEglDisplay, mEglConfig, EGL_NATIVE_VISUAL_ID, &nativeVisualId);
    if (nativeVisualId > 0) {
        ANativeWindow_setBuffersGeometry(displayWindow, 0, 0, nativeVisualId);
    }

    const EGLint surfaceAttribs[] = { EGL_NONE };
    mEglDisplaySurface = eglCreateWindowSurface(mEglDisplay, mEglConfig, displayWindow, surfaceAttribs);
    if (mEglDisplaySurface == EGL_NO_SURFACE) {
        LOGE("GpuPipeline: Failed to create display window surface (error 0x%x)", eglGetError());
        return false;
    }
    LOGI("GpuPipeline: Successfully created display window surface %p (nativeVisualId: %d)",
         mEglDisplaySurface, nativeVisualId);
    return true;
}

void GpuPipeline::releaseEncoderSurface() {
    if (mEglEncoderSurface != EGL_NO_SURFACE) {
        eglDestroySurface(mEglDisplay, mEglEncoderSurface);
        mEglEncoderSurface = EGL_NO_SURFACE;
    }
}

void GpuPipeline::releaseDisplaySurface() {
    if (mEglDisplaySurface != EGL_NO_SURFACE) {
        eglDestroySurface(mEglDisplay, mEglDisplaySurface);
        mEglDisplaySurface = EGL_NO_SURFACE;
    }
}

void GpuPipeline::setLutEnabled(bool enabled) {
    mIsLutEnabled.store(enabled);
}

void GpuPipeline::setLogCurveType(int32_t type) {
    mLogCurveType.store(type);
}

void GpuPipeline::setExposureGain(float gain) {
    mExposureGain.store(gain);
}

bool GpuPipeline::isLutEnabled() const {
    return mIsLutEnabled.load();
}

int32_t GpuPipeline::getLogCurveType() const {
    return mLogCurveType.load();
}

float GpuPipeline::getExposureGain() const {
    return mExposureGain.load();
}

bool GpuPipeline::loadDisplayLut(const char* cubeData, size_t dataSize) {
    std::lock_guard<std::mutex> lock(mLutMutex);
    if (!cubeData || dataSize == 0) {
        mPendingLutData.clear();
        mPendingLutClear = true;
        mHasPendingLut = true;
        LOGI("GpuPipeline: Queued 3D LUT clear request for processing thread");
        return true;
    }
    mPendingLutData.assign(cubeData, cubeData + dataSize);
    mPendingLutClear = false;
    mHasPendingLut = true;
    LOGI("GpuPipeline: Queued %zu bytes of 3D LUT data for processing thread upload", dataSize);
    return true;
}

void GpuPipeline::processFrame(AHardwareBuffer* rawBuffer,
                              int32_t stride,
                              const SensorFrameMetadata& metadata,
                              int acquireFenceFd,
                              int* outReleaseFenceFd) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    if (!mInitialized || !rawBuffer) return;

    // Check for pending 3D LUT to upload or clear on this active rendering thread
    if (mHasPendingLut) {
        std::vector<char> lutCopy;
        bool shouldClear = false;
        {
            std::lock_guard<std::mutex> lutLock(mLutMutex);
            lutCopy = std::move(mPendingLutData);
            shouldClear = mPendingLutClear;
            mHasPendingLut = false;
            mPendingLutClear = false;
        }
        if (shouldClear) {
            mLutManager.release();
            LOGI("GpuPipeline: Released 3D LUT on processing thread");
        } else if (!lutCopy.empty()) {
            mLutManager.loadCubeLutFromMemory(lutCopy.data(), lutCopy.size());
            LOGI("GpuPipeline: Successfully loaded 3D LUT on processing thread (Texture ID: %u)",
                 mLutManager.getLutTextureId());
        }
    }

    // 1. Hardware Wait on Camera Acquire Fence
    if (acquireFenceFd >= 0 && mEglCreateSyncKHR && mEglWaitSyncKHR) {
        EGLint syncAttribs[] = {
            EGL_SYNC_NATIVE_FENCE_FD_ANDROID, acquireFenceFd,
            EGL_NONE
        };
        EGLSyncKHR acquireSync = mEglCreateSyncKHR(mEglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, syncAttribs);
        if (acquireSync != EGL_NO_SYNC_KHR) {
            mEglWaitSyncKHR(mEglDisplay, acquireSync, 0);
            mEglDestroySyncKHR(mEglDisplay, acquireSync);
        }
        close(acquireFenceFd);
    }

    // 2. Import AHardwareBuffer to raw integer texture (executing on processing thread with valid context!)
    GLuint rawTexture = mImporter.importHardwareBufferToTexture(rawBuffer, mWidth, mHeight, stride);
    if (rawTexture == 0) {
        LOGE("GpuPipeline: Failed to import raw hardware buffer to texture");
        return;
    }

    // =========================================================================
    // PASS 1: Debayer + Color Matrix + Pixel-Log OETF -> Offscreen FBO
    // =========================================================================
    glBindFramebuffer(GL_FRAMEBUFFER, mOffscreenFbo);
    glViewport(0, 0, mWidth, mHeight);

    glUseProgram(mDebayerProgram);

    // Bind uniforms
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, rawTexture);
    glUniform1i(glGetUniformLocation(mDebayerProgram, "uRawBayerTexture"), 0);

    glUniform2i(glGetUniformLocation(mDebayerProgram, "uSensorResolution"), mWidth, mHeight);
    glUniform1i(glGetUniformLocation(mDebayerProgram, "uBayerPattern"), static_cast<int32_t>(metadata.bayerPattern));
    glUniform4f(glGetUniformLocation(mDebayerProgram, "uBlackLevel"),
               metadata.dynamicBlackLevel.r,
               metadata.dynamicBlackLevel.gr,
               metadata.dynamicBlackLevel.gb,
               metadata.dynamicBlackLevel.b);
    glUniform1f(glGetUniformLocation(mDebayerProgram, "uWhiteLevel"), metadata.whiteLevel);

    // Color Science Uniforms (Phase 2 & Phase 3)
    // 1. Lens Shading Map (Phase 2.2)
    if (metadata.hasShadingMap && metadata.shadingMapWidth > 0 && metadata.shadingMapHeight > 0 && !metadata.shadingMapData.empty()) {
        glActiveTexture(GL_TEXTURE1);
        if (mLensShadingTexture == 0 || mLensShadingWidth != metadata.shadingMapWidth || mLensShadingHeight != metadata.shadingMapHeight) {
            if (mLensShadingTexture != 0) glDeleteTextures(1, &mLensShadingTexture);
            glGenTextures(1, &mLensShadingTexture);
            glBindTexture(GL_TEXTURE_2D, mLensShadingTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, metadata.shadingMapWidth, metadata.shadingMapHeight, 0, GL_RGBA, GL_FLOAT, metadata.shadingMapData.data());
            mLensShadingWidth = metadata.shadingMapWidth;
            mLensShadingHeight = metadata.shadingMapHeight;
        } else {
            glBindTexture(GL_TEXTURE_2D, mLensShadingTexture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, metadata.shadingMapWidth, metadata.shadingMapHeight, GL_RGBA, GL_FLOAT, metadata.shadingMapData.data());
        }
        glUniform1i(glGetUniformLocation(mDebayerProgram, "uLensShadingMap"), 1);
        glUniform1i(glGetUniformLocation(mDebayerProgram, "uHasLensShading"), 1);
        glActiveTexture(GL_TEXTURE0);
    } else {
        glUniform1i(glGetUniformLocation(mDebayerProgram, "uHasLensShading"), 0);
    }

    // 2. White Balance: SENSOR_NEUTRAL_COLOR_POINT [Rn, Gn, Bn]
    float nR = metadata.neutralColorPoint[0] > 0.0f ? metadata.neutralColorPoint[0] : 0.55f;
    float nG = metadata.neutralColorPoint[1] > 0.0f ? metadata.neutralColorPoint[1] : 1.0f;
    float nB = metadata.neutralColorPoint[2] > 0.0f ? metadata.neutralColorPoint[2] : 0.70f;
    glUniform3f(glGetUniformLocation(mDebayerProgram, "uNeutralColorPoint"), nR, nG, nB);

    // 3. Composite Matrix (Sensor -> Bradford -> Rec.2020 Linear + Exposure Gain)
    glUniformMatrix3fv(glGetUniformLocation(mDebayerProgram, "uCompositeMatrix"), 1, GL_FALSE, metadata.compositeMatrix);

    // 4. Selectable Log OETF & Analytical Curve Parameters
    glUniform1i(glGetUniformLocation(mDebayerProgram, "uLogCurveType"), mLogCurveType.load());
    glUniform1f(glGetUniformLocation(mDebayerProgram, "uLogYb"), pixellog::LOG_YB);
    glUniform1f(glGetUniformLocation(mDebayerProgram, "uLogBeta"), pixellog::LOG_BETA);
    glUniform1f(glGetUniformLocation(mDebayerProgram, "uLogGamma"), pixellog::LOG_GAMMA);
    glUniform1f(glGetUniformLocation(mDebayerProgram, "uLogDelta"), pixellog::LOG_DELTA);
    glUniform1f(glGetUniformLocation(mDebayerProgram, "uLogS"), pixellog::LOG_S);

    // Draw fullscreen quad
    glBindBuffer(GL_ARRAY_BUFFER, mQuadVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    // Cleanup raw input texture
    mImporter.destroyTexture(rawTexture);

    // =========================================================================
    // =========================================================================
    // PASS 2: Render to MediaCodec Input Surface (Bake LUT or clean 10-bit Log)
    // =========================================================================
    if (mEglEncoderSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(mEglDisplay, mEglEncoderSurface, mEglEncoderSurface, mEglContext);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        EGLint encWidth = mWidth;
        EGLint encHeight = mHeight;
        eglQuerySurface(mEglDisplay, mEglEncoderSurface, EGL_WIDTH, &encWidth);
        eglQuerySurface(mEglDisplay, mEglEncoderSurface, EGL_HEIGHT, &encHeight);
        glViewport(0, 0, encWidth, encHeight);

        bool bakeLut = mBakeLutToEncoder.load() && mLutManager.isLoaded();
        if (bakeLut && mLutProgram != 0) {
            glUseProgram(mLutProgram);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mOffscreenTexture);
            glUniform1i(glGetUniformLocation(mLutProgram, "uLogTexture"), 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, mLutManager.getLutTextureId());
            glUniform1i(glGetUniformLocation(mLutProgram, "uLut3D"), 1);
        } else if (mPassthroughProgram != 0) {
            glUseProgram(mPassthroughProgram);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mOffscreenTexture);
            glUniform1i(glGetUniformLocation(mPassthroughProgram, "uTexture"), 0);
        }

        glBindBuffer(GL_ARRAY_BUFFER, mDisplayQuadVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        if (mEglPresentationTimeANDROID) {
            mEglPresentationTimeANDROID(mEglDisplay, mEglEncoderSurface, metadata.timestampNs);
        }
        eglSwapBuffers(mEglDisplay, mEglEncoderSurface);

        static uint64_t encFrameCount = 0;
        if (encFrameCount++ % 30 == 0) {
            LOGI("GpuPipeline: Pass 2 rendered %s to encoder surface (viewport %dx%d, pts %lld ns)",
                 bakeLut ? "BAKED 3D LUT" : "clean Log",
                 encWidth, encHeight, (long long)metadata.timestampNs);
        }
    }

    // =========================================================================
    // PASS 3: Viewfinder Display Pass (3D LUT graded preview or clean Log passthrough)
    // =========================================================================
    if (mEglDisplaySurface != EGL_NO_SURFACE) {
        eglMakeCurrent(mEglDisplay, mEglDisplaySurface, mEglDisplaySurface, mEglContext);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        EGLint displayWidth = mWidth;
        EGLint displayHeight = mHeight;
        eglQuerySurface(mEglDisplay, mEglDisplaySurface, EGL_WIDTH, &displayWidth);
        eglQuerySurface(mEglDisplay, mEglDisplaySurface, EGL_HEIGHT, &displayHeight);
        glViewport(0, 0, displayWidth, displayHeight);

        if (mIsLutEnabled.load() && mLutManager.isLoaded()) {
            glUseProgram(mLutProgram);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mOffscreenTexture);
            glUniform1i(glGetUniformLocation(mLutProgram, "uLogTexture"), 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, mLutManager.getLutTextureId());
            glUniform1i(glGetUniformLocation(mLutProgram, "uLut3D"), 1);
        } else if (mPassthroughProgram != 0) {
            glUseProgram(mPassthroughProgram);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mOffscreenTexture);
            glUniform1i(glGetUniformLocation(mPassthroughProgram, "uTexture"), 0);
        }

        glBindBuffer(GL_ARRAY_BUFFER, mDisplayQuadVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        eglSwapBuffers(mEglDisplay, mEglDisplaySurface);
    } else {
        static uint64_t noSurfaceLog = 0;
        if (noSurfaceLog++ % 60 == 0) {
            LOGW("GpuPipeline: Pass 3 display pass skipped - mEglDisplaySurface is EGL_NO_SURFACE");
        }
    }

    // Restore Pbuffer surface for intermediate FBO / sync operations
    eglMakeCurrent(mEglDisplay, mEglPbufferSurface, mEglPbufferSurface, mEglContext);

    // =========================================================================
    // Export GPU Release Fence for Camera HAL
    // =========================================================================
    if (outReleaseFenceFd && mEglCreateSyncKHR && mEglDupNativeFenceFDANDROID) {
        EGLSyncKHR releaseSync = mEglCreateSyncKHR(mEglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
        glFlush();
        *outReleaseFenceFd = mEglDupNativeFenceFDANDROID(mEglDisplay, releaseSync);
        mEglDestroySyncKHR(mEglDisplay, releaseSync);
    }
}

void GpuPipeline::release() {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    if (!mInitialized) return;

    eglMakeCurrent(mEglDisplay, mEglPbufferSurface, mEglPbufferSurface, mEglContext);

    if (mOffscreenFbo != 0) {
        glDeleteFramebuffers(1, &mOffscreenFbo);
        mOffscreenFbo = 0;
    }
    if (mOffscreenTexture != 0) {
        glDeleteTextures(1, &mOffscreenTexture);
        mOffscreenTexture = 0;
    }
    if (mDebayerProgram != 0) {
        glDeleteProgram(mDebayerProgram);
        mDebayerProgram = 0;
    }
    if (mLutProgram != 0) {
        glDeleteProgram(mLutProgram);
        mLutProgram = 0;
    }
    if (mPassthroughProgram != 0) {
        glDeleteProgram(mPassthroughProgram);
        mPassthroughProgram = 0;
    }
    if (mQuadVbo != 0) {
        glDeleteBuffers(1, &mQuadVbo);
        mQuadVbo = 0;
    }
    if (mDisplayQuadVbo != 0) {
        glDeleteBuffers(1, &mDisplayQuadVbo);
        mDisplayQuadVbo = 0;
    }
    if (mLensShadingTexture != 0) {
        glDeleteTextures(1, &mLensShadingTexture);
        mLensShadingTexture = 0;
    }

    mLutManager.release();
    mImporter.release();

    releaseEncoderSurface();
    releaseDisplaySurface();

    if (mEglPbufferSurface != EGL_NO_SURFACE) {
        eglDestroySurface(mEglDisplay, mEglPbufferSurface);
        mEglPbufferSurface = EGL_NO_SURFACE;
    }

    if (mEglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(mEglDisplay, mEglContext);
        mEglContext = EGL_NO_CONTEXT;
    }

    if (mEglDisplay != EGL_NO_DISPLAY) {
        eglTerminate(mEglDisplay);
        mEglDisplay = EGL_NO_DISPLAY;
    }

    mInitialized = false;
    LOGI("GpuPipeline: Released all graphics resources");
}
