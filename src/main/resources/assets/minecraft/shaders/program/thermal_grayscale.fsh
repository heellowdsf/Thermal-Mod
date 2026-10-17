#version 150

// 热成像后处理着色器
//
// 热成像探测长波红外辐射，看的是温度与温差，而非可见光。
//
// 成像模型:
//   背景 = 受光照/群系温度调制的灰度场
//   实体 = 自发光纯绿标记，恒定温差，浮于背景之上
//  让画面随天气/群系真实变化：
//     - 雪地：冷背景压暗 + 高对比，实体锐利突出
//     - 雨天：信号散射，对比度下降，叠加动态噪点
//
// 实体检测判据（R≈0 且 B≈0）绝不改动，防止植物误判。

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;
uniform float RainStrength;  // 降雨强度 0~1
uniform float Snowy;         // 寒冷度 0~1
in vec2 texCoord;
out vec4 fragColor;

// 哈希噪声
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec4 color = texture(DiffuseSampler, texCoord);
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    float maxC = max(max(color.r, color.g), color.b);

    // 实体标记检测
    // 热标记顶点色强制 (0, G, 0)，最终像素 = 顶点色 × 纹理色，
    // 所以 R、B 恒为精确 0，只有 G 被纹理调制（深色像素 G 很低但 >0）。
    // 之前要求 G>0.06，导致 YSM 深色像素（暗部/深色衣服）漏白。
    // 现在把 G 阈值降到 0.02：因为标记的 R、B 是精确 0，只要 G 略大于 0 就是标记；
    // 而背景纯黑(0,0,0)/暗灰(R≈G≈B) 的 G 要么为 0、要么 R/B 也非 0 → 仍被排除。
    float entity = (1.0 - step(0.012, color.r))   // R ≈ 0
                 * (1.0 - step(0.012, color.b))   // B ≈ 0
                 * step(0.02, color.g);           // G 有微弱信号即可

    // 环境背景：丢掉明度(光照)、保留色相
    // 光照和阴影都是同一个亮度。
    // Minecraft 画面里，光照信息主要在「明度(亮度)」通道，而物体本身的特征
    // 在「色相」。一面墙不管被照亮还是在阴影里，它的色相一样（都是石头的灰褐），只是
    // 明度不同（光照造成）。所以：丢掉明度(=丢掉光照明暗)，用色相/饱和度决定灰度。
    //   - 同一面墙（同色相）- 不管明暗都是同一个灰 - 光照/阴影消失
    //   - 不同材质（不同色相）- 不同的灰 - 能分清材质

    // RGB - 色相 H、饱和度 S、明度 V
    // RGB - hue H, saturation S, value V
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

    // 灰度映射：石头/原石是低饱和+中低明度，几乎只靠基准灰+明度辅助，基准要够高
    float matGray = 0.34 + (hue * 0.12) * sat + sat * 0.08;
    float valueHint = pow(clamp(cmax, 0.0, 1.0), 0.55) * 0.18;

    float blockThermal = matGray + valueHint;

    // 水偏冷：水是蓝色，热成像里水应比周围略暗（冷）。对蓝明显占主导的像素做
    // 温和压暗（×0.78，不是压成黑），让水看着凉一点。即便蓝花也被轻微压暗，但只是稍暗
    // 不会变黑，可接受。这与之前那个会把东西压成黑的天空检测不同（那个是 mix 到 0.06）。
    // 水偏冷：水是热汇，热成像里应比周围略暗。
    //
    // 原判据 step(r+0.05, b) * step(g+0.03, b) 太松了：石头/安山岩等纹理里
    // 大量「偏冷灰」的像素只要蓝比红高 0.05 就被当成水，整片 ×0.78 压暗 →
    // 就是方块表面那些黑色像素点。（实测：黑点处 b-r≈0.05、饱和度仅 0.11~0.23，
    // 而真正的水饱和度约 0.69 —— 用饱和度可以干净地把两者分开。）
    //
    // 现在要求三个条件同时成立：蓝比红高 0.12、蓝比绿高 0.08、且饱和度 ≥ 0.35。
    // 前两条是「蓝必须明显占主导」，真的是蓝色而不是冷灰
    // 饱和度与画面明暗无关（是比值），所以这条判据不受满亮度/光照影响，很稳。
    float blueDom = step(color.r + 0.12, color.b)
                  * step(color.g + 0.08, color.b)
                  * step(0.35, sat);
    blockThermal *= mix(1.0, 0.78, blueDom);

    // 不再做基于颜色的"天空检测"——它会把任何偏冷色调的东西蓝花、紫/蓝村民衣服、
    // 冷色调原石，误判成天空压成黑。天空就让它走正常映射（呈浅灰），比到处变黑好得多

    // 注沙漠/群系温度(BiomeTemp)相关的热背景变换已经移除了，沙漠等群系走正常映射
    // 仅保留雪地(Snowy)的冷背景处理。

    // 雪地
    // 极冷背景与温血生物温差极大 - 背景更暗 + 对比拉高。
    if (Snowy > 0.01) {
        blockThermal *= (1.0 - Snowy * 0.40);
    }

    // 雨天
    // 雨幕散射 - 湿度吸收 - 信号衰减、对比度下降
    // 视觉应是画面偏暗、柔和、轻微噪点，可见度下降但仍能看清场景结构
    // 绝不是把画面提亮成均匀灰雾，那是错误的"白内障"效果
    if (RainStrength > 0.01) {
        // 整体压暗 + 轻微降低对比（向自身均值轻拉，而非拉向亮灰）
        blockThermal *= (1.0 - RainStrength * 0.30);

        // 轻微动态噪点（传感器受潮）
        vec2 np = texCoord * InSize * 0.4;
        float n = hash(np + floor(Time * 50.0) * 1.3) - 0.5;
        blockThermal += n * RainStrength * 0.04;
    }

    // 火源
    // 之前用"红高蓝低"判据从屏幕颜色反推火源，但后处理阶段分不清
    // 自发光的火和被染色/照亮的红橙黄物体 - 夕阳、红橙黄羊毛全被误判。
    // 现在火由 HeatRenderer 用绿色标记主动渲染，
    // 走下方实体相同的识别路径（R≈0,B≈0），可靠且无误判。此处不做任何颜色判断。

    // 实体亮度
    // 之前的"发光感"来自固定高亮基底：整个实体被拉到接近纯白的亮度，
    // 抹平表面明暗，看起来像通体发光的热源，而非保留阴影的死板模型。
    // 同时要保留距离衰减：远处(Java 把 color.g 整体压低)才变暗。
    //
    // 做法：用陡峭的 smoothstep 把"近处信号"迅速饱和到亮白窄区间(0.82~0.96)，
    // 抹掉实体表面的明暗差异（发光感）；只有信号很弱（远处衰减）时亮度才下降，
    // 极远淡入背景。这样既有发光感，又保留距离衰减。
    float sig = clamp(color.g, 0.0, 1.0);
    // 陡峭饱和：sig≥0.30 即拉到接近满亮（更彻底抹平表面阴影，通体发光）
    float glow = smoothstep(0.05, 0.30, sig);
    float entityBright = 0.82 + glow * 0.14;        // 亮白窄区间 0.82~0.96 
    // 信号弱（远处）时整体淡向背景
    float entityThermal = mix(blockThermal, entityBright, glow);

    // 雪地：实体更锐利突出
    entityThermal += Snowy * 0.04 * glow;

    // 雨天：实体表面被雨水轻微冷却，温差略减，但仍清晰明显（只是稍微暗一点点）。
    // Rain: surface slightly cooled, contrast a touch lower, but stays clearly visible.
    entityThermal -= RainStrength * 0.05 * glow;

    // 合成
    float thermal = mix(blockThermal, entityThermal, entity);

    // 原来thermal *= step(0.003, maxC);
    // 把最大通道 < 0.003」的像素整个归零成纯黑。夜里没有光照时，方块表面大量纹理像素
    // 会掉到这个阈值以下 - 屏幕上出现成片黑色麻点
    // 它本意是让虚空保持黑，但代价是误伤真实方块。现在删掉：背景基准灰是 0.34
    // 本来就不会变黑，虚空最多呈中灰，比满屏黑点好得多
    // 这也是原作者删掉「颜色天空检测」时的同一个教训：任何「把像素强制压成黑」的判据
    // 迟早会误伤真实物体

    // 常驻传感器颗粒噪点：真实热像仪始终有微弱噪声，让画面像"活的传感器"而非静态滤镜。
    vec2 gp = texCoord * InSize * 0.5;
    float grain = hash(gp + floor(Time * 45.0) * 1.7) - 0.5;
    thermal += grain * 0.022;

    thermal = clamp(thermal, 0.0, 1.0);

    // T-7 绿灰色调
    // 整体调暗一点点×0.82
    fragColor = vec4(thermal * 0.74, thermal * 0.80, thermal * 0.70, 1.0);
}
