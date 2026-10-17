#version 150

// ============================================================
// Gen 3 Image Intensifier Tube — Pass 1
// Photocathode + MCP Amplification + Auto-Gating + P43 Phosphor
// ============================================================
//
// AUTO-GATING (Gen 3+ feature):
//   Modern NVG tubes (e.g. PVS-31A, GPNVG-18) have automatic gain control.
//   The tube rapidly adjusts electron gain based on ambient light level:
//     - Dark scene  → maximum gain (~50,000x) → deep gamma lift
//     - Bright scene → reduced gain (~500x)   → mild gamma lift
//   This prevents total white-out in daytime and allows 24-hour operation.
//
//   Implementation: sample 9 points across the scene to estimate average
//   brightness, then interpolate the gamma exponent accordingly.
//   These 9 samples are at fixed UV positions → GPU texture cache makes
//   them essentially free after the first fragment.
//
// MCP SATURATION:
//   Even with auto-gating, local bright sources (torches, lava, sun
//   reflections) still saturate the MCP channels near those sources.
//   The saturation threshold adapts with the gain setting.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(DiffuseSampler, texCoord);
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));

    // ======= Auto-Gating: Scene Brightness Estimation =======
    //
    // 9-point sparse grid sampling (3×3).
    // All fragments read the same 9 texels → texture cache hit after
    // first wavefront → negligible performance cost.
    //
    float avg = 0.0;
    avg += dot(texture(DiffuseSampler, vec2(0.2, 0.2)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.5, 0.2)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.8, 0.2)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.2, 0.5)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.5, 0.5)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.8, 0.5)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.2, 0.8)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.5, 0.8)).rgb, vec3(0.299, 0.587, 0.114));
    avg += dot(texture(DiffuseSampler, vec2(0.8, 0.8)).rgb, vec3(0.299, 0.587, 0.114));
    avg /= 9.0;

    // ======= Adaptive Gain (Gamma Exponent) =======
    //
    // Scene avg → gamma exponent mapping:
    //   avg=0.005 (starlight)  → gamma=0.20  (extreme lift: 0.01→0.40)
    //   avg=0.03  (moonlight)  → gamma=0.25  (strong lift:  0.01→0.35)
    //   avg=0.10  (dusk/dawn)  → gamma=0.35  (moderate lift)
    //   avg=0.30  (overcast)   → gamma=0.50  (mild lift)
    //   avg=0.50+ (daylight)   → gamma=0.62  (subtle lift, still green tint)
    //
    float gamma = mix(0.20, 0.62, smoothstep(0.005, 0.45, avg));

    float amplified = pow(max(lum, 0.002), gamma);

    // ======= MCP Saturation (Adaptive Threshold) =======
    //
    // Saturation threshold rises with scene brightness:
    //   Dark scene:  starts saturating at lum=0.25 (torches pop immediately)
    //   Bright scene: starts saturating at lum=0.65 (only direct sun/fire)
    //
    float satStart = mix(0.25, 0.65, smoothstep(0.005, 0.45, avg));
    float saturation = smoothstep(satStart, satStart + 0.35, lum);
    saturation *= saturation;
    amplified = mix(amplified, 1.0, saturation);

    // Extreme sources always clip regardless of gating
    float extreme = smoothstep(0.85, 0.98, lum);
    amplified += extreme * 0.10;

    // ======= P43 Phosphor Screen =======
    // Peak 545nm, warm military green
    fragColor = vec4(
        amplified * 0.70,
        amplified * 1.00,
        amplified * 0.50,
        1.0
    );
}
