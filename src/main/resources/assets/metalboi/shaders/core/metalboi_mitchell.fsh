#version 330

// MetalBoi - separable Mitchell-Netravali bicubic resampler (16 taps).
//
// METALBOI_C is injected by MetalBoi at pipeline-build time:
//   C = 0.5  -> Catmull-Rom, the neutral default (no ringing)
//   C > 0.5  -> a deeper negative lobe, i.e. adaptive edge sharpening
// B is fixed at 0, which is the standard choice for resampling.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

float metalboiKernel(float t) {
    t = abs(t);
    if (t < 1.0) {
        return ((12.0 - 6.0 * METALBOI_C) * t * t * t
                + (-18.0 + 6.0 * METALBOI_C) * t * t
                + 6.0) / 6.0;
    } else if (t < 2.0) {
        return ((-6.0 * METALBOI_C) * t * t * t
                + (30.0 * METALBOI_C) * t * t
                + (-48.0 * METALBOI_C) * t
                + (24.0 * METALBOI_C)) / 6.0;
    }
    return 0.0;
}

// Weights for the four taps at offsets -1, 0, +1, +2 relative to floor(coord).
vec4 metalboiWeights(float frac) {
    return vec4(
            metalboiKernel(frac + 1.0),
            metalboiKernel(frac),
            metalboiKernel(1.0 - frac),
            metalboiKernel(2.0 - frac));
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

    // Move into texel-centre space: texel N covers [N-0.5, N+0.5).
    vec2 coord = texCoord * inSize - 0.5;
    vec2 f = floor(coord);
    vec2 frac = coord - f;

    // Centre of texel f, the tap the kernel is anchored on.
    vec2 base = (f + 0.5) * inv;

    vec4 wx = metalboiWeights(frac.x);
    vec4 wy = metalboiWeights(frac.y);

    vec3 colour = wy.x * metalboiRow(-inv.y, wx, base, inv)
            + wy.y * metalboiRow(0.0, wx, base, inv)
            + wy.z * metalboiRow(inv.y, wx, base, inv)
            + wy.w * metalboiRow(2.0 * inv.y, wx, base, inv);

    // C > 0.5 produces overshoot by design; clamp it back into range.
    fragColor = vec4(clamp(colour, 0.0, 1.0), 1.0);
}
