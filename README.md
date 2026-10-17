# Thermal Vision Forge

Minecraft 1.20.1 Forge mod

## 编译环境

| 依赖 | 版本要求 |
| --- | --- |
| JDK | 17 |
| Minecraft | 1.20.1 |
| Forge | **≥ 47.4.1**（对应 gradle.properties 中的 `forge_version`，需改为此版本及以上） |
| Gradle | 8.x.x |
| Embeddium | **必需依赖**：编译期使用本地 `libs/embeddium-0.3.31+mc1.20.1.jar`（compileOnly），运行时客户端也必须安装 Embeddium mod |

注意：项目未包含 Gradle wrapper，需使用系统安装的 Gradle 执行命令。

## 编译命令

```bash
# 编译打包（产物在 build/libs/thermal-vision-forge-1.0.0.jar）
gradle clean build
```

## 源码结构

### 主类与物品（`src/main/java/com/thermalvision/`）

| 文件 | 作用 |
| --- | --- |
| `ThermalVisionMod.java` | Mod 主类。注册三个物品（热成像仪、夜视仪、热成像模块）并加入创造模式标签 |
| `api/ThermalVisionAPI.java` | 公开 API，供其他 Mod / 整合包调用：模式切换、注册自定义热源方块、模式变化监听、护目镜门槛开关 |
| `item/ThermalGogglesItem.java` | 热成像仪物品 |
| `item/NightVisionGogglesItem.java` | 夜视仪物品 |

### 客户端核心（`src/main/java/com/thermalvision/client/`）

| 文件 | 作用 |
| --- | --- |
| `ThermalVisionClient.java` | 客户端核心：X 键绑定、模式状态机（OFF/THERMAL/NIGHTVISION）、开/关逻辑（热成像延迟关闭防绿色残留）、背包护目镜检测、附魔光效抑制、模式监听器、客户端事件处理 |
| `HeatMap.java` | 方块热力图：热源方块扫描（岩浆/火/营火/岩浆块/火把/灯笼/熔炉等）、热量扩散与衰减、实体站立传热、水中冷却、自定义热源覆盖 |
| `HeatRenderer.java` | 方块热量渲染：热源方块绿色标记立方体（按方块实际形状）+ 扩散余热发光四边形 |
| `EnvironmentState.java` | 环境状态采样：生物群系温度、降雨、下雪、水下，平滑插值；计算实体热信号的距离/天气/水衰减系数 |
| `EntityThermalContext.java` | 当前渲染实体的共享上下文（衰减系数 + 主纹理），供身体层与盔甲层保持一致 |
| `ItemRenderState.java` | 物品渲染深度计数器，判断当前是否在渲染手持物品（嵌套调用安全） |
| `ThermalBufferSource.java` | 包装 MultiBufferSource：顶点染绿、丢弃阴影与附魔 glint 层、按各渲染层自身纹理 alpha 裁出真实轮廓 |
| `ThermalFeatureRenderer.java` | 实体热成像渲染层（图层注册保留；实际染绿由 EntityRenderDispatcherMixin 统一处理） |
| `ThermalRenderType.java` | 热成像专用 RenderType：自发光、无剔除、视图空间深度偏移（防 Z-fighting 闪烁） |
| `ThermalShaders.java` | 注册自定义实体着色器 `thermal_entity`（只用纹理 alpha 裁形、输出纯绿） |
| `ThermalVertexConsumer.java` | 顶点消费者：强制顶点颜色为纯绿 (0,G,0)、满亮度 |
| `IrisCompat.java` | Oculus / Iris 光影兼容层：反射挂起/恢复光影管线（只换引用不 reload，避免 2 秒卡顿），含慢路径回退与显存泄漏防护 |
| `EmbeddiumHeatHook.java` | Embeddium 检测与集成入口（隔离对 `org.embeddedt.*` 的引用，未装时不会崩溃） |

### Embeddium 集成（`src/main/java/com/thermalvision/client/embeddium/`）

| 文件 | 作用 |
| --- | --- |
| `ThermalChunkMeshAppender.java` | Embeddium 区块网格集成：监听 ChunkMeshEvent，在 worker 线程把热源方块以真实形状（火把、岩浆液面等）染绿写入区块网格 |
| `GreenTintConsumer.java` | 方块顶点消费者：强制纯绿、满亮度 |

### Mixin（`src/main/java/com/thermalvision/client/mixin/`）

| 文件 | 目标类 | 作用 |
| --- | --- | --- |
| `GameRendererMixin.java` | GameRenderer | 加载并应用热成像/夜视后处理链（PostChain），传入时间/雨/雪 uniform，处理 resize 与 close |
| `FogMixin.java` | FogRenderer | 热成像时推远雾距离（红外穿透烟雾） |
| `LightTextureMixin.java` | LightTexture | 热成像时强制满亮度光照贴图，消除光照对热像的干扰 |
| `SkyMixin.java` | LevelRenderer | 热成像时不渲染天空穹顶（避免月亮/晚霞被误判为热源） |
| `HandMixin.java` | ItemInHandRenderer | 第一人称手臂强制满亮度 |
| `PlayerHandMixin.java` | PlayerRenderer | 第一人称左右手白热（手里物品不发热），兼容枪械类 Mod |
| `ParticleMixin.java` | ParticleEngine | 阻止粒子生成与渲染 |
| `ParticleEngineAccessor.java` | ParticleEngine | 访问器：调用 clearParticles() |
| `PostChainAccessor.java` | PostChain | 访问器：读取 passes 列表 |
| `ArmorLayerMixin.java` | HumanoidArmorLayer | 盔甲层染绿（heat 0.55，比裸露皮肤暗，可区分穿甲） |
| `EntityRenderDispatcherMixin.java` | EntityRenderDispatcher | 实体统一重渲染染绿（覆盖女巫帽子、鸡腿等额外渲染层），YSM 兼容 |
| `ItemRendererMixin.java` | ItemRenderer | 物品渲染前后标记深度计数器 |

## 资源文件（`src/main/resources/`）

| 文件 | 作用 |
| --- | --- |
| `META-INF/mods.toml` | Mod 元数据：modId、版本、Forge/Minecraft 版本依赖 |
| `pack.mcmeta` | 资源包描述 |
| `thermalvision.mixins.json` | Mixin 配置清单 |
| `assets/minecraft/shaders/program/` | 后处理着色器程序：thermal_grayscale（灰度+热源白热识别）、thermal_bloom（辉光）、thermal_nether（下界专用）、nightvision / nightvision_bloom（夜视） |
| `assets/thermalvision/shaders/core/thermal_entity.*` | 自定义实体着色器（alpha 裁形 + 纯绿输出） |
| `assets/thermalvision/shaders/post/*.json` | 后处理链定义（引用上面各 program） |
| `assets/thermalvision/lang/` | 中英文语言文件 |
| `assets/thermalvision/models/item/` | 物品模型定义 |
| `assets/thermalvision/textures/` | 贴图：heat_glow（余热发光）、white（白纹理）、三个物品贴图 |
| `data/thermalvision/recipes/` | 合成配方（热成像仪、热成像模块） |
| `libs/embeddium-0.3.31+mc1.20.1.jar` | 本地 Embeddium 依赖（compileOnly，勿删） |
