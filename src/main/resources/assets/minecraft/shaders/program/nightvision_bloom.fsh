#version 150

// ============================================================
// Gen 3 NVG — Pass 2: Bloom + Scintillation + Wide Vignette
// ============================================================
//
// PERFORMANCE NOTES:
//   Bloom: 12 texture samples (4 inner cardinal + 4 mid diagonal + 4 outer cardinal)
//   Noise: 3 hash() calls (integer-lattice, no sin/cos/sqrt)
//   Vignette: pure arithmetic (dot, smoothstep)
//   Total texture reads per fragment: 13 (1 center + 12 bloom)
//
// VIGNETTE DESIGN:
//   NVG has wider FOV than thermal scope to compensate for
//   fewer tactical advantages (no fog penetration, no entity marking).
//   Modeled after GPNVG-18 wide panoramic field of view.
//   Edge darkening only at screen periphery.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 px = 1.0 / InSize;
    vec3 c = texture(DiffuseSampler, texCoord).rgb;

    // ======= MCP Bloom (12 samples, 3 rings) =======
    //
    // Ring layout for round halo with minimum samples:
    //   Inner  (r=7px):  4 cardinal    → sharp bright core
    //   Mid    (r=12px): 4 diagonal    → fills gaps, rounds the halo
    //   Outer  (r=22px): 4 cardinal    → wide soft glow
    //
    // Threshold on G channel (phosphor output from pass 1)

    float r1 = 7.0;
    float inner = 0.0;
    inner += max(texture(DiffuseSampler, texCoord + vec2( r1*px.x, 0.0)).g - 0.65, 0.0);
    inner += max(texture(DiffuseSampler, texCoord + vec2(-r1*px.x, 0.0)).g - 0.65, 0.0);
    inner += max(texture(DiffuseSampler, texCoord + vec2(0.0,  r1*px.y)).g - 0.65, 0.0);
    inner += max(texture(DiffuseSampler, texCoord + vec2(0.0, -r1*px.y)).g - 0.65, 0.0);

    float d2 = 12.0 * 0.707;
    float mid = 0.0;
    mid += max(texture(DiffuseSampler, texCoord + vec2( d2*px.x,  d2*px.y)).g - 0.65, 0.0);
    mid += max(texture(DiffuseSampler, texCoord + vec2(-d2*px.x,  d2*px.y)).g - 0.65, 0.0);
    mid += max(texture(DiffuseSampler, texCoord + vec2( d2*px.x, -d2*px.y)).g - 0.65, 0.0);
    mid += max(texture(DiffuseSampler, texCoord + vec2(-d2*px.x, -d2*px.y)).g - 0.65, 0.0);

    float r3 = 22.0;
    float outer = 0.0;
    outer += max(texture(DiffuseSampler, texCoord + vec2( r3*px.x, 0.0)).g - 0.70, 0.0);
    outer += max(texture(DiffuseSampler, texCoord + vec2(-r3*px.x, 0.0)).g - 0.70, 0.0);
    outer += max(texture(DiffuseSampler, texCoord + vec2(0.0,  r3*px.y)).g - 0.70, 0.0);
    outer += max(texture(DiffuseSampler, texCoord + vec2(0.0, -r3*px.y)).g - 0.70, 0.0);

    vec3 result = c;
    result += inner * 0.30;
    result += mid   * 0.18;
    result += outer * 0.08;

    // ======= Scintillation Noise =======

    vec2 fineCoord = texCoord * InSize + Time * 211.0;
    float nFine = hash(fineCoord);

    vec2 coarseCoord = floor(texCoord * InSize * 0.5) + Time * 137.0;
    float nCoarse = hash(coarseCoord);

    float grain = (nFine * 0.5 + nCoarse * 0.5) - 0.5;

    // Ion feedback scintillation (sparse bright flashes)
    float scint = max(hash(fineCoord * 1.37 + Time * 89.0) - 0.88, 0.0) * 4.0;

    float snrMask = 1.0 - result.g * 0.55;
    result += grain * 0.10 * snrMask;
    result += scint * 0.05;

    // ======= Wide FOV Vignette =======
    //
    // NVG has significantly wider viewport than thermal scope:
    //   - Thermal scope: tight elliptical cutoff (rifle scope feel)
    //   - NVG: wide panoramic view (GPNVG-18 style)
    //
    // Edge darkening starts at 70% from center, full black at 95%.
    // Most of the screen is fully visible — only peripheral corners darken.
    //
    // Compare thermal vignette (in thermal_bloom.fsh):
    //   d = x²/0.38 + y²/0.21;  cutoff at smoothstep(0.60, 1.0, d)
    //   → tight elliptical scope, significant screen area blacked out
    //
    // NVG vignette here:
    //   circular with cutoff starting at dist=0.65
    //   → wide open, only outer ring darkened

    vec2 center = texCoord - 0.5;
    center.x *= InSize.x / InSize.y;
    float dist = length(center);

    float vignette = 1.0 - smoothstep(0.65, 0.90, dist);
    // Gentle cos⁴-like optical falloff (barely noticeable until edges)
    vignette *= 1.0 - dist * dist * 0.15;

    result *= vignette;

    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
