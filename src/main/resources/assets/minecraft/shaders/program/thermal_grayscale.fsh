#version 150

// 热成像后处理着色器 / Thermal vision post-process shader
//
// 原理：热成像探测长波红外辐射（8-14μm），看的是温度与温差，而非可见光。
// Principle: thermal detects long-wave IR (8-14um); it sees temperature
// and thermal CONTRAST, not visible light.
//
// 成像模型 / Imaging model:
//   背景 = 受光照/群系温度调制的灰度场 / background = lit grayscale field
//   实体 = 自发光纯绿标记，恒定温差，浮于背景之上
//   environment uniforms 让画面随天气/群系真实变化：
//     - 雪地：冷背景压暗 + 高对比，实体锐利突出
//     - 雨天：信号散射，对比度下降，叠加动态噪点
//
// 实体检测判据（R≈0 且 B≈0）绝不改动，防止植物误判。
// Entity detection (R≈0 and B≈0) is never altered — prevents foliage false-positives.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;
uniform float RainStrength;  // 降雨强度 0~1 / rain intensity
uniform float Snowy;         // 寒冷度 0~1 / coldness

in vec2 texCoord;
out vec4 fragColor;

// 哈希噪声 / Hash noise
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec4 color = texture(DiffuseSampler, texCoord);
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    float maxC = max(max(color.r, color.g), color.b);

    // ============ 实体标记检测 / Entity marker detection ============
    // 热标记顶点色强制 (0, G, 0)，最终像素 = 顶点色 × 纹理色，
    // 所以 R、B 恒为精确 0，只有 G 被纹理调制（深色像素 G 很低但 >0）。
    // 之前要求 G>0.06，导致 YSM 深色像素（暗部/深色衣服）漏白。
    // 现在把 G 阈值降到 0.02：因为标记的 R、B 是精确 0，只要 G 略大于 0 就是标记；
    // 而背景纯黑(0,0,0)/暗灰(R≈G≈B) 的 G 要么为 0、要么 R/B 也非 0 → 仍被排除。
    // Marker color forced to (0, G, 0); final = vertex × texture, so R,B stay EXACTLY 0,
    // only G is texture-modulated (dark texels → low G but >0). The old G>0.06 made dark YSM
    // pixels leak. Lowering the floor to 0.02 works because markers have R,B EXACTLY 0:
    // any faint G is a marker, while pure-black/dark-gray backgrounds have G=0 or nonzero
    // R/B → still rejected. Catches dark YSM pixels without false-positiving the background.
    float entity = (1.0 - step(0.012, color.r))   // R ≈ 0
                 * (1.0 - step(0.012, color.b))   // B ≈ 0
                 * step(0.02, color.g);           // G 有微弱信号即可 / even faint G counts

    // ============ 环境背景：丢掉明度(光照)、保留色相(物体) / Discard value, keep hue ============
    // 你的需求很简单：光照和阴影都是同一个亮度。
    // 关键洞察：Minecraft 画面里，光照信息主要在「明度(亮度)」通道，而物体本身的特征
    // 在「色相」。一面墙不管被照亮还是在阴影里，它的色相一样（都是石头的灰褐），只是
    // 明度不同（光照造成）。所以：丢掉明度(=丢掉光照明暗)，用色相/饱和度决定灰度。
    //   - 同一面墙（同色相）→ 不管明暗都是同一个灰 → 光照/阴影消失
    //   - 不同材质（不同色相）→ 不同的灰 → 能分清材质
    //
    // Your need is simple: lit and shadowed areas should be the SAME brightness.
    // Insight: in MC, lighting lives mainly in the VALUE (brightness) channel, while a
    // material's identity is in its HUE. A wall lit or shadowed has the same hue (stone's
    // gray-brown), only its value differs (from lighting). So: DISCARD value (= discard
    // lighting), derive gray from hue/saturation instead.
    //   - same wall (same hue) → same gray regardless of light → shadows/glare gone
    //   - different material (different hue) → different gray → materials distinguishable

    // RGB → 色相 H、饱和度 S、明度 V
    // RGB → hue H, saturation S, value V
    float cmax = max(max(color.r, color.g), color.b);
    float cmin = min(min(color.r, color.g), color.b);
    float delta = cmax - cmin;
    float hue = 0.0;
    if (delta > 0.0001) {
        if (cmax == color.r)      hue = mod((color.g - color.b) / delta, 6.0);
        else if (cmax == color.g) hue = (color.b - color.r) / delta + 2.0;
        else                      hue = (color.r - color.g) / delta + 4.0;
        hue /= 6.0;   // 0~1
    }
    float sat = cmax > 0.0001 ? delta / cmax : 0.0;

    // 灰度映射：石头/原石是低饱和+中低明度，几乎只靠基准灰+明度辅助，所以基准要够高、
    // 明度辅助要够强，否则它们发黑。把基准抬到 0.34，明度辅助加强到 0.18 且带下限，
    // 让石头/原石呈清晰中灰而不是黑。彩色物体靠 sat/hue 在此之上再区分。
    // Gray mapping: stone/cobblestone are low-saturation + mid-dark, relying almost entirely
    // on base + value hint, so the base must be high enough and the value hint strong enough
    // or they go black. Base raised to 0.34, value hint up to 0.18 with a floor → stone reads
    // as a clear mid-gray, not black. Colored things differ further via sat/hue on top.
    float matGray = 0.34 + (hue * 0.12) * sat + sat * 0.08;
    float valueHint = pow(clamp(cmax, 0.0, 1.0), 0.55) * 0.18;

    float blockThermal = matGray + valueHint;

    // 水偏冷：水是蓝色，热成像里水应比周围略暗（冷）。对「蓝明显占主导」的像素做
    // 温和压暗（×0.78，不是压成黑），让水看着凉一点。即便蓝花也被轻微压暗，但只是稍暗
    // 不会变黑，可接受。这与之前那个会把东西压成黑的天空检测不同（那个是 mix 到 0.06）。
    // Water cooler: water is blue and should read slightly darker (cold) in thermal. Mildly
    // dim strongly blue-dominant pixels (×0.78, NOT to black) so water looks cool. Blue
    // flowers dim slightly too but stay visible — acceptable. Unlike the old sky detection
    // that forced things to 0.06 (black), this is just a gentle multiply.
    // 水偏冷：水是热汇，热成像里应比周围略暗。
    //
    // 【已修正】原判据 step(r+0.05, b) * step(g+0.03, b) 太松了：石头/安山岩等纹理里
    // 大量「偏冷灰」的像素只要蓝比红高 0.05 就被当成水，整片 ×0.78 压暗 →
    // 就是方块表面那些黑色像素点。（实测：黑点处 b-r≈0.05、饱和度仅 0.11~0.23，
    // 而真正的水饱和度约 0.69 —— 用饱和度可以干净地把两者分开。）
    //
    // 现在要求三个条件同时成立：蓝比红高 0.12、蓝比绿高 0.08、且饱和度 ≥ 0.35。
    // 前两条是「蓝必须明显占主导」，第三条是「必须真的是蓝色而不是冷灰」。
    // 饱和度与画面明暗无关（是比值），所以这条判据不受满亮度/光照影响，很稳。
    //
    // [FIXED] The old test (b > r+0.05 and b > g+0.03) was far too loose: the many
    // COOL-GRAY texels in stone/andesite textures pass it and get dimmed ×0.78 — those are
    // the black speckles on block surfaces. (Measured: at the dots b-r ≈ 0.05 with
    // saturation only 0.11–0.23, while real water sits near 0.69 — saturation separates
    // them cleanly.) Now all three must hold: blue clearly above red (0.12) and green
    // (0.08), AND saturation ≥ 0.35 ("actually blue", not "cool gray"). Saturation is a
    // ratio, so this test is unaffected by lighting/fullbright — it stays stable.
    float blueDom = step(color.r + 0.12, color.b)
                  * step(color.g + 0.08, color.b)
                  * step(0.35, sat);
    blockThermal *= mix(1.0, 0.78, blueDom);

    // 注：不再做基于颜色的"天空检测"——它会把任何偏冷色调的东西（蓝花、紫/蓝村民衣服、
    // 冷色调原石）误判成天空压成黑。天空就让它走正常映射（呈浅灰），比到处变黑好得多。
    // Note: NO color-based sky detection — it caught any cool-toned thing (blue flowers,
    // purple/blue villager robes, cool stone) and forced them black. Let the sky go through
    // normal mapping (reads light gray); far better than black bugs everywhere.

    // 注：沙漠/群系温度(BiomeTemp)相关的「热背景」变换已按需求整体移除，沙漠等群系走正常映射。
    // 仅保留雪地(Snowy)的冷背景处理。
    // Note: the desert / biome-temperature "hot background" transform has been removed by
    // request; desert and other biomes use normal mapping. Only the snow (Snowy) cold-background
    // handling remains.

    // --- 雪地 / Snow ---
    // 现实：极冷背景与温血生物温差极大 → 背景更暗 + 对比拉高。
    // Reality: very cold background vs warm-blooded entities = huge contrast.
    // 背景整体压暗（冷得均匀）/ darken background (uniformly cold).
    if (Snowy > 0.01) {
        blockThermal *= (1.0 - Snowy * 0.40);
    }

    // --- 雨天 / Rain ---
    // 现实：雨幕散射 + 湿度吸收 → 信号衰减、对比度下降。
    // 视觉应是「画面偏暗、柔和、轻微噪点」，可见度下降但仍能看清场景结构，
    // 绝不是把画面提亮成均匀灰雾（那是错误的"白内障"效果）。
    // Reality: scatter + humidity absorption → signal loss, lower contrast.
    // Visual = slightly darker, softer, faint noise; scene still readable.
    // NOT brightening into a uniform gray fog (the wrong "cataract" look).
    if (RainStrength > 0.01) {
        // 整体压暗 + 轻微降低对比（向自身均值轻拉，而非拉向亮灰）
        // dim overall + slightly reduce contrast (pull toward its own mean, not bright gray)
        blockThermal *= (1.0 - RainStrength * 0.30);

        // 轻微动态噪点（传感器受潮）/ subtle dynamic noise (damp sensor)
        vec2 np = texCoord * InSize * 0.4;
        float n = hash(np + floor(Time * 50.0) * 1.3) - 0.5;
        blockThermal += n * RainStrength * 0.04;
    }

    // --- 火源不再靠颜色猜测 ---
    // 之前用"红高蓝低"判据从屏幕颜色反推火源，但后处理阶段分不清
    // 自发光的火和被染色/照亮的红橙黄物体 → 夕阳、红橙黄羊毛全被误判。
    // 现在火源（岩浆/火/篝火/岩浆块/火把等）由 HeatRenderer 用绿色标记主动渲染，
    // 走下方实体相同的识别路径（R≈0,B≈0），可靠且无误判。此处不做任何颜色判断。
    //
    // Fire sources are NO LONGER guessed from color. Post-processing can't tell
    // self-emissive fire from lit/dyed red-orange-yellow objects, so sunset and
    // colored wool got false-flagged. Fire (lava/fire/campfire/magma/torch) is now
    // actively marked green by HeatRenderer and detected via the same entity path
    // (R≈0, B≈0) below — reliable, no false positives. No color test here.

    // ============ 实体亮度 / Entity brightness ============
    // 之前的"发光感"来自固定高亮基底：整个实体被拉到接近纯白的亮度，
    // 抹平表面明暗，看起来像通体发光的热源，而非保留阴影的死板模型。
    // 同时要保留距离衰减：远处(Java 把 color.g 整体压低)才变暗。
    //
    // 做法：用陡峭的 smoothstep 把"近处信号"迅速饱和到亮白窄区间(0.82~0.96)，
    // 抹掉实体表面的明暗差异（发光感）；只有信号很弱（远处衰减）时亮度才下降，
    // 极远淡入背景。这样既有发光感，又保留距离衰减。
    //
    // The "glow" came from a high flat brightness: the whole entity is pushed to
    // near-white, erasing surface shading so it reads as a glowing heat source,
    // not a shaded model. Distance dimming is preserved: only when Java has pulled
    // color.g down (far away) does it darken.
    // Method: a steep smoothstep saturates near-range signal into a tight bright
    // band (0.82~0.96), flattening surface shading (the glow); brightness only
    // drops when signal is weak (far), fading into background at extreme range.
    float sig = clamp(color.g, 0.0, 1.0);
    // 陡峭饱和：sig≥0.30 即拉到接近满亮（更彻底抹平表面阴影，通体发光）
    // steep saturation: sig≥0.30 → near full bright (flatten shading harder → uniform glow)
    float glow = smoothstep(0.05, 0.30, sig);
    float entityBright = 0.82 + glow * 0.14;        // 亮白窄区间 0.82~0.96 / tight bright band
    // 信号弱（远处）时整体淡向背景 / weak signal (far) fades toward background
    float entityThermal = mix(blockThermal, entityBright, glow);

    // 雪地：实体更锐利突出（温差更大）/ Snow: entities pop harder
    entityThermal += Snowy * 0.04 * glow;

    // 雨天：实体表面被雨水轻微冷却，温差略减，但仍清晰明显（只是稍微暗一点点）。
    // Rain: surface slightly cooled, contrast a touch lower, but stays clearly visible.
    entityThermal -= RainStrength * 0.05 * glow;

    // ============ 合成 / Composite ============
    float thermal = mix(blockThermal, entityThermal, entity);

    // 【已移除】原来这里有 thermal *= step(0.003, maxC);
    // 它把「最大通道 < 0.003」的像素整个归零成纯黑。夜里没有光照时，方块表面大量纹理像素
    // 会掉到这个阈值以下 → 屏幕上出现成片黑色麻点（就是你截图里那些黑点）。
    // 它本意是让虚空保持黑，但代价是误伤真实方块。现在删掉：背景基准灰是 0.34，
    // 本来就不会变黑，虚空最多呈中灰，比满屏黑点好得多。
    // 这也是原作者删掉「颜色天空检测」时的同一个教训：任何「把像素强制压成黑」的判据，
    // 迟早会误伤真实物体。
    // [REMOVED] There used to be `thermal *= step(0.003, maxC);` here.
    // It zeroed any pixel whose max channel < 0.003. At night, with no light, many block
    // texels fall below that → clusters of BLACK SPECKLES (the dots in your screenshot).
    // It was meant to keep the void black, but it false-kills real blocks. Removed: the
    // background floor is 0.34 so nothing goes black anyway; the void just reads mid-gray,
    // far better than speckles. Same lesson the original author learned when deleting the
    // color-based sky test: ANY "force pixel to black" rule eventually hits real objects.

    // 常驻传感器颗粒噪点：真实热像仪始终有微弱噪声，让画面像"活的传感器"而非静态滤镜。
    // Always-on sensor grain: real thermal cameras always have faint noise (live sensor look).
    vec2 gp = texCoord * InSize * 0.5;
    float grain = hash(gp + floor(Time * 45.0) * 1.7) - 0.5;
    thermal += grain * 0.022;

    thermal = clamp(thermal, 0.0, 1.0);

    // T-7 绿灰色调 / T-7 green-gray palette
    // 整体调暗一点点（×0.82）/ dim overall slightly per request
    fragColor = vec4(thermal * 0.74, thermal * 0.80, thermal * 0.70, 1.0);
}
