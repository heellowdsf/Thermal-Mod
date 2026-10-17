package com.thermalvision.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.*;

/**
 * 方块热力图：追踪热源方块的热量扩散和衰减
 *
 * 实体温度：水中冷却，出水后恢复
 * 站立传热：实体站立不动时向脚下方块传热
 */
public class HeatMap {

    private static final HashMap<Long, Float> heat = new HashMap<>();
    private static final HashMap<UUID, Float> entityCooling = new HashMap<>();
    private static final HashMap<UUID, Integer> stillTicks = new HashMap<>();
    private static int tick = 0;

    // 开发者/整合包自定义热源：优先于内置表。值>0 视为热源(0~1)，值<=0 表示强制不发热
    private static final java.util.concurrent.ConcurrentHashMap<Block, Float> customHeat =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 注册/覆盖一个方块的热量（0~1）*/
    public static void registerHeatSource(Block block, float heatValue) {
        if (block != null) customHeat.put(block, heatValue);
    }
    /** 取消自定义（恢复内置表行为） */
    public static void unregisterHeatSource(Block block) {
        if (block != null) customHeat.remove(block);
    }
    public static java.util.Map<Block, Float> getCustomHeatSources() {
        return java.util.Collections.unmodifiableMap(customHeat);
    }

    private static long[] snapKeys = new long[0];
    private static float[] snapVals = new float[0];
    private static int snapCount = 0;

    // 热源方块本身（位置→源强度），区别于扩散到周围固体的热量。
    // 用于直接把火源方块渲染成绿色白热标记，不靠着色器猜颜色。
    private static final HashMap<Long, Float> sources = new HashMap<>();
    private static long[] srcKeys = new long[0];
    private static float[] srcVals = new float[0];
    private static int srcCount = 0;

    public static void tick(ClientLevel level, Player player) {
        tick++;
        BlockPos pos = player.blockPosition();
        if (tick % 3 == 0) tickEntityHeat(level, pos);
        if (tick % 4 == 0) tickEntityTemp(level, pos);
        if (tick % 2 == 0) decay();
        if (tick % 30 == 0) scanBlocks(level, pos);
        if (tick % 6 == 0) snapshot();
    }

    public static int getSnapCount() { return snapCount; }
    public static long getSnapKey(int i) { return snapKeys[i]; }
    public static float getSnapVal(int i) { return snapVals[i]; }

    // 热源方块快照访问
    public static int getSourceCount() { return srcCount; }
    public static long getSourceKey(int i) { return srcKeys[i]; }
    public static float getSourceVal(int i) { return srcVals[i]; }

    public static float getEntityHeat(LivingEntity e) {
        Float c = entityCooling.get(e.getUUID());
        return c != null ? 1f - c : 1f;
    }
    public static void clear() {
        heat.clear(); entityCooling.clear(); stillTicks.clear();
        sources.clear();
        snapCount = 0; srcCount = 0; tick = 0;
    }

    private static void tickEntityHeat(ClientLevel level, BlockPos center) {
        AABB box = new AABB(center).inflate(24, 12, 24);
        Set<UUID> seen = new HashSet<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                en -> en.isAlive() && !(en instanceof ArmorStand))) {
            UUID id = e.getUUID();
            seen.add(id);
            if (!e.onGround()) { stillTicks.remove(id); continue; }
            if (e.getDeltaMovement().horizontalDistanceSqr() > 0.0001) { stillTicks.remove(id); continue; }

            int still = stillTicks.getOrDefault(id, 0) + 3;
            stillTicks.put(id, still);
            if (still < 60) continue;

            BlockPos ground = e.blockPosition().below();
            if (!level.getBlockState(ground).canOcclude()) continue;
            float rate = 0.008f + Math.min(still, 600) * 0.00001f;
            addHeat(ground.asLong(), rate, 0.50f);
        }
        if (tick % 200 == 0) stillTicks.keySet().retainAll(seen);
    }

    private static void tickEntityTemp(ClientLevel level, BlockPos center) {
        AABB box = new AABB(center).inflate(32, 16, 32);
        Set<UUID> seen = new HashSet<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                en -> en.isAlive() && !(en instanceof ArmorStand))) {
            UUID id = e.getUUID();
            seen.add(id);
            float cur = entityCooling.getOrDefault(id, 0f);
            // 只有"完全没入水中"(眼睛也在水下)才冷却到无热——红外出不了水面。
            // 仅脚泡在水里、上半身露出水面时，保持热量（露出的身体在发热）。
            // 现实里碰一下水不会让全身热量消失。
            if (e.isUnderWater()) {
                cur += (0.55f - cur) * 0.016f;
                entityCooling.put(id, Math.min(cur, 0.55f));
            } else if (cur > 0) {
                cur *= 0.988f;
                if (cur < 0.005f) entityCooling.remove(id);
                else entityCooling.put(id, cur);
            }
        }
        if (tick % 200 == 0) entityCooling.keySet().retainAll(seen);
    }

    private static void scanBlocks(ClientLevel level, BlockPos center) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos s = new BlockPos.MutableBlockPos();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        // 重建热源方块集合（火可能熄灭、岩浆可能流走
        sources.clear();
        for (int x = -10; x <= 10; x++)
            for (int z = -10; z <= 10; z++)
                for (int y = -6; y <= 6; y++) {
                    m.set(cx + x, cy + y, cz + z);
                    float h = blockHeat(level.getBlockState(m).getBlock());
                    if (h <= 0) continue;
                    // 记录热源方块本身（用于绿色白热标记）
                    sources.put(m.asLong(), h);
                    spreadToSolids(level, m, s, h);
                }
    }

    private static void spreadToSolids(ClientLevel level, BlockPos.MutableBlockPos src,
                                        BlockPos.MutableBlockPos m, float srcHeat) {
        int sx = src.getX(), sy = src.getY(), sz = src.getZ();
        int r = (srcHeat >= 0.7f) ? 4 : 3;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -r; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    float dist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                    if (dist > r) continue;
                    m.set(sx + dx, sy + dy, sz + dz);
                    if (!level.getBlockState(m).canOcclude()) continue;
                    float falloff = 1f - dist / (r + 1f);
                    float amt = srcHeat * falloff * 0.08f;
                    if (amt < 0.003f) continue;
                    addHeat(m.asLong(), amt, srcHeat * falloff * 0.7f);
                }
    }

    public static float blockHeat(Block b) {
        // 自定义覆盖优先
        Float custom = customHeat.get(b);
        if (custom != null) return custom > 0 ? custom : 0f;
        if (b == Blocks.LAVA) return 1.0f;
        if (b == Blocks.FIRE || b == Blocks.SOUL_FIRE) return 0.85f;
        if (b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) return 0.7f;
        if (b == Blocks.MAGMA_BLOCK) return 0.7f;
        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) return 0.5f;
        if (b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH) return 0.4f;
        // 灯笼内含火焰，是温和热源
        if (b == Blocks.LANTERN) return 0.45f;
        if (b == Blocks.SOUL_LANTERN) return 0.38f;
        if (b == Blocks.LAVA_CAULDRON) return 0.9f;
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return 0.35f;
        return 0f;
    }

    private static void decay() {
        Iterator<Map.Entry<Long, Float>> it = heat.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Float> e = it.next();
            float v = e.getValue() * 0.986f;
            if (v < 0.008f) it.remove();
            else e.setValue(v);
        }
    }

    private static void addHeat(long pos, float amount, float max) {
        if (heat.size() >= 2000 && !heat.containsKey(pos)) return;
        heat.merge(pos, amount, (old, add) -> Math.min(old + add, max));
    }

    private static void snapshot() {
        int size = heat.size();
        if (snapKeys.length < size) {
            snapKeys = new long[size + 256];
            snapVals = new float[size + 256];
        }
        int i = 0;
        for (Map.Entry<Long, Float> e : heat.entrySet()) {
            if (e.getValue() < 0.025f) continue;
            snapKeys[i] = e.getKey();
            snapVals[i] = e.getValue();
            i++;
        }
        snapCount = i;

        // 热源方块快照
        int ssize = sources.size();
        if (srcKeys.length < ssize) {
            srcKeys = new long[ssize + 64];
            srcVals = new float[ssize + 64];
        }
        int j = 0;
        for (Map.Entry<Long, Float> e : sources.entrySet()) {
            srcKeys[j] = e.getKey();
            srcVals[j] = e.getValue();
            j++;
        }
        srcCount = j;
    }
}
