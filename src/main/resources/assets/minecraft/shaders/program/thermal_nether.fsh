#version 150

// Nether: thermal sensor overloaded by extreme ambient heat.
// Everything is ~1000C, sensor can't distinguish anything = static snow.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

float noise(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    // Use scene content as frame-varying seed (camera/entities always shift slightly)
    vec4 scene = texture(DiffuseSampler, texCoord);
    float sceneSeed = dot(scene.rgb, vec3(0.3, 0.5, 0.2));

    // Static noise pattern, perturbed by scene content each frame
    vec2 noiseCoord = texCoord * InSize * 0.5;
    float n1 = noise(noiseCoord + sceneSeed * 137.0);
    float n2 = noise(noiseCoord * 1.7 + sceneSeed * 241.0);

    // Scanline flicker
    float scanline = 0.92 + 0.08 * sin(texCoord.y * InSize.y * 1.5 + sceneSeed * 500.0);

    // Heavy static with faint scene ghost
    float result = mix(n1 * 0.7 + n2 * 0.3, sceneSeed * 0.3, 0.1) * scanline;

    // Greenish-gray tint (overheated sensor)
    fragColor = vec4(result * 0.85, result * 0.95, result * 0.80, 1.0);
}
