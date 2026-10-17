#version 150

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);

    // 只用纹理 alpha 裁形状（透明像素丢弃），完全无视纹理 RGB：
    //   - 形状正确：女巫帽子/鸡腿等 box 模型靠 alpha 裁出真实形状，不再是大方块
    //   - 颜色纯绿：不受皮肤颜色/动物花色影响（牛无花色、纯色皮肤也照常发热，不可被绕过）
    // Use ONLY texture alpha for shape cutout, ignore texture RGB entirely:
    //   - correct shape (box models cut by alpha, no block blob)
    //   - pure green (immune to skin color / animal markings; not bypassable)
    if (texel.a < 0.1) {
        discard;
    }

    // 输出顶点色（Java 端已设为 (0, G, 0)），满不透明
    // Output vertex color (Java set it to (0,G,0)), fully opaque
    fragColor = vec4(vertexColor.rgb, 1.0);
}
