#version 330

// MetalBoi - separable Lanczos-2 resampler (16 taps).
//
// L(x) = sinc(x) * sinc(x/2) for |x| < 2, else 0. Sharper than Catmull-Rom at the
// cost of slight ringing on very high-contrast edges.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

const float METALBOI_PI = 3.141592653589793;

float metalboiSinc(float x) {
    if (abs(x) < 1.0e-4) {
        return 1.0;
    }
    float px = METALBOI_PI * x;
    return sin(px) / px;
}

float metalboiLanczos2(float x) {
    if (abs(x) >= 2.0) {
        return 0.0;
    }
    return metalboiSinc(x) * metalboiSinc(x * 0.5);
}

// Weights for the four taps at offsets -1, 0, +1, +2 relative to floor(coord).
// For frac in [0,1) all four distances fall within the |x| < 2 support window.
vec4 metalboiWeights(float frac) {
    vec4 w = vec4(
            metalboiLanczos2(-1.0 - frac),
            metalboiLanczos2(-frac),
            metalboiLanczos2(1.0 - frac),
            metalboiLanczos2(2.0 - frac));

    float sum = w.x + w.y + w.z + w.w;
    if (abs(sum) < 1.0e-4) {
        return vec4(0.0, 1.0, 0.0, 0.0);
    }
    return w / sum;
}

vec3 metalboiRow(float offsetY, vec4 wx, vec2 base, vec2 inv) {
    return wx.x * texture(InSampler, base + vec2(-inv.x, offsetY)).rgb
            + wx.y * texture(InSampler, base + vec2(0.0, offsetY)).rgb
            + wx.z * texture(InSampler, base + vec2(inv.x, offsetY)).rgb
            + wx.w * texture(InSampler, base + vec2(2.0 * inv.x, offsetY)).rgb;
}

void main() {
    vec2 inSize = vec2(textureSize(InSampler, 0));
    vec2 inv = 1.0 / inSize;

    vec2 coord = texCoord * inSize - 0.5;
    vec2 f = floor(coord);
    vec2 frac = coord - f;
    vec2 base = (f + 0.5) * inv;

    vec4 wx = metalboiWeights(frac.x);
    vec4 wy = metalboiWeights(frac.y);

    vec3 colour = wy.x * metalboiRow(-inv.y, wx, base, inv)
            + wy.y * metalboiRow(0.0, wx, base, inv)
            + wy.z * metalboiRow(inv.y, wx, base, inv)
            + wy.w * metalboiRow(2.0 * inv.y, wx, base, inv);

    // Lanczos overshoots at sharp edges; clamp so it cannot bloom out of range.
    fragColor = vec4(clamp(colour, 0.0, 1.0), 1.0);
}
