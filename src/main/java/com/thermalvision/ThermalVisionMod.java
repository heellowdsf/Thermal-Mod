package com.thermalvision;

import com.thermalvision.item.NightVisionGogglesItem;
import com.thermalvision.item.ThermalGogglesItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热成像 Mod 主类
 * 注册物品并添加到创造模式战斗标签
 */
@Mod(ThermalVisionMod.MOD_ID)
public class ThermalVisionMod {

    public static final String MOD_ID = "thermalvision";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Item> THERMAL_GOGGLES = ITEMS.register(
            "thermal_goggles",
            () -> new ThermalGogglesItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> NIGHT_VISION_GOGGLES = ITEMS.register(
            "night_vision_goggles",
            () -> new NightVisionGogglesItem(new Item.Properties().stacksTo(1)));

    // 热成像模块：纯合成材料，用于合成热成像仪
    public static final RegistryObject<Item> THERMAL_MODULE = ITEMS.register(
            "thermal_module",
            () -> new Item(new Item.Properties()));

    public ThermalVisionMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::addCreative);
        LOGGER.info("[ThermalVision] Loaded (Forge)");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(THERMAL_GOGGLES);
            event.accept(NIGHT_VISION_GOGGLES);
        }
        // 模块归入材料/工具标签
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(THERMAL_MODULE);
        }
    }
}
