package net.sunbird.mod.world;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.sunbird.mod.SunbirdMod;
import net.sunbird.mod.entity.SunbirdEntity;

/** Registry glue: entity type, spawn egg, sunstone block (craftable nest for the bird). */
public final class SunbirdFeatures {

    public static final EntityType<SunbirdEntity> SUNBIRD_ENTITY_TYPE =
            Registry.register(Registries.ENTITY_TYPE, SunbirdMod.id("sunbird"),
                    FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, SunbirdEntity::new)
                            .dimensions(EntityDimensions.fixed(0.6f, 0.6f))
                            .build());

    public static final Item SUNBIRD_SPAWN_EGG = new SpawnEggItem(SUNBIRD_ENTITY_TYPE,
            0xFFD93B, 0x8A6D1F, new Item.Settings());

    public static final Block SUNSTONE = new Block(AbstractBlock.Settings.create()
            .strength(1.5f, 6.0f)
            .luminance(state -> 12)   // it glows, like a caged sunrise
            .sounds(BlockSoundGroup.STONE));

    public static final Item SUNSTONE_ITEM = new BlockItem(SUNSTONE, new Item.Settings());

    private SunbirdFeatures() {}

    public static void register() {
        Registry.register(Registries.BLOCK, SunbirdMod.id("sunstone"), SUNSTONE);
        Registry.register(Registries.ITEM, SunbirdMod.id("sunstone"), SUNSTONE_ITEM);
        Registry.register(Registries.ITEM, SunbirdMod.id("sunbird_spawn_egg"), SUNBIRD_SPAWN_EGG);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> {
            entries.add(SUNBIRD_SPAWN_EGG);
            entries.add(SUNSTONE_ITEM);
        });

        // Rare ambient spawns across the overworld so it feels inhabited by little suns
        if (net.sunbird.mod.SunbirdConfig.get().naturalSpawning) {
            BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld(),
                    SpawnGroup.CREATURE, SUNBIRD_ENTITY_TYPE, 2, 1, 1);
        }

        SunbirdMod.LOGGER.info("[Sunbird] Registered entities/blocks/items.");
    }
}
