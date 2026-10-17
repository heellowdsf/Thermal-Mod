#version 150

// Thermal Pass 2: T-7 style IR bloom + scope + sensor noise

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

// Integer-based hash ，no sin, no GPU precision artifacts
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 px = 1.0 / InSize;

    vec3 c = texture(DiffuseSampler, texCoord).rgb;

    // IR bloom: inner (4px) + outer (8px)
    float rad1 = 4.0;
    float rad2 = 8.0;
    float b1 = texture(DiffuseSampler, texCoord + vec2( rad1*px.x, 0.0)).r;
    float b2 = texture(DiffuseSampler, texCoord + vec2(-rad1*px.x, 0.0)).r;
    float b3 = texture(DiffuseSampler, texCoord + vec2(0.0,  rad1*px.y)).r;
    float b4 = texture(DiffuseSampler, texCoord + vec2(0.0, -rad1*px.y)).r;
    float inner = max(b1-0.45,0.0) + max(b2-0.45,0.0) + max(b3-0.45,0.0) + max(b4-0.45,0.0);

    float b5 = texture(DiffuseSampler, texCoord + vec2( rad2*px.x, 0.0)).r;
    float b6 = texture(DiffuseSampler, texCoord + vec2(-rad2*px.x, 0.0)).r;
    float b7 = texture(DiffuseSampler, texCoord + vec2(0.0,  rad2*px.y)).r;
    float b8 = texture(DiffuseSampler, texCoord + vec2(0.0, -rad2*px.y)).r;
    float outer = max(b5-0.45,0.0) + max(b6-0.45,0.0) + max(b7-0.45,0.0) + max(b8-0.45,0.0);

    vec3 result = c + (inner * 0.35 + outer * 0.15);

    // Dynamic sensor noise (microbolometer characteristic)
    // Real thermal noise is NOT fine per-pixel static.
    // It's chunky grain (sensor pixels are large) that flickers temporally.
    // Mix of coarse (2x2 block) and medium (per-pixel) for natural look.

    // Coarse grain: 2x2 pixel blocks share same noise → visible chunky grain
    vec2 coarse = floor(texCoord * InSize * 0.5) + Time * 97.0;
    float nc = hash(coarse);

    // Medium grain: per-pixel temporal flicker
    vec2 fine = texCoord * InSize + Time * 173.0;
    float nf = hash(fine);

    // Blend: 60% coarse + 40% fine, centered around 0
    float noise = (nc * 0.6 + nf * 0.4) - 0.5;

    // Dark areas: more noise (low signal = bad SNR on real sensors)
    float noiseMask = 1.0 - result.r * 0.5;
    result += noise * 0.20 * noiseMask;

    // Scope vignette
    vec2 center = texCoord - 0.5;
    center.x *= InSize.x / InSize.y;
    float d = (center.x * center.x) / 0.38 + (center.y * center.y) / 0.21;
    float scope = (1.0 - smoothstep(0.60, 1.0, d)) * (1.0 - d * 0.12);
    result *= scope;

    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
