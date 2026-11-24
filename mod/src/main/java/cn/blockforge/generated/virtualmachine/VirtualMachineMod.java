package cn.blockforge.generated.virtualmachine;

import cn.blockforge.generated.virtualmachine.computer.ComputerBlock;
import cn.blockforge.generated.virtualmachine.computer.ComputerBlockEntity;
import cn.blockforge.generated.virtualmachine.computer.ComputerScreenHandler;
import cn.blockforge.generated.virtualmachine.computer.MediaDiskItem;
import cn.blockforge.generated.virtualmachine.computer.OsType;
import cn.blockforge.generated.virtualmachine.network.ComputerNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** 主入口：注册方块、物品、方块实体、界面和网络包。 */
public final class VirtualMachineMod implements ModInitializer {
    public static final String MOD_ID = "virtual_machine";

    public static final ComputerBlock COMPUTER = Registry.register(
        Registries.BLOCK,
        Identifier.of(MOD_ID, "computer"),
        new ComputerBlock(AbstractBlock.Settings.create()
            .mapColor(MapColor.IRON_GRAY)
            .strength(2.5f, 7.0f)
            .sounds(BlockSoundGroup.METAL)
            .luminance(state -> switch (state.get(ComputerBlock.SCREEN)) {
                case DESKTOP -> 9;
                case BOOT -> 4;
                default -> 0;
            })));

    public static final BlockItem COMPUTER_ITEM = Registry.register(
        Registries.ITEM,
        Identifier.of(MOD_ID, "computer"),
        new BlockItem(COMPUTER, new Item.Settings()));

    public static final BlockEntityType<ComputerBlockEntity> COMPUTER_ENTITY = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        Identifier.of(MOD_ID, "computer"),
        FabricBlockEntityTypeBuilder.create(ComputerBlockEntity::new, COMPUTER).build());

    public static final ScreenHandlerType<ComputerScreenHandler> COMPUTER_SCREEN_HANDLER = Registry.register(
        Registries.SCREEN_HANDLER,
        Identifier.of(MOD_ID, "computer"),
        new ScreenHandlerType<>(ComputerScreenHandler::new, FeatureFlags.VANILLA_FEATURES));

    public static final MediaDiskItem LINDOWS_ISO = registerDisk("lindows_iso", OsType.LINDOWS);
    public static final MediaDiskItem PENGUIX_ISO = registerDisk("penguix_iso", OsType.PENGUIX);
    public static final MediaDiskItem PCDOS_IMG = registerDisk("pcdos_img", OsType.PCDOS);
    public static final MediaDiskItem NANOOS_IMG = registerDisk("nanoos_img", OsType.NANOOS);

    public static final ItemGroup ITEM_GROUP = Registry.register(
        Registries.ITEM_GROUP,
        Identifier.of(MOD_ID, "main"),
        FabricItemGroup.builder()
            .displayName(Text.translatable("itemGroup.virtual_machine"))
            .icon(() -> new ItemStack(COMPUTER_ITEM))
            .entries((context, entries) -> {
                entries.add(COMPUTER_ITEM);
                entries.add(LINDOWS_ISO);
                entries.add(PENGUIX_ISO);
                entries.add(PCDOS_IMG);
                entries.add(NANOOS_IMG);
            })
            .build());

    private static MediaDiskItem registerDisk(String name, OsType os) {
        MediaDiskItem item = new MediaDiskItem(os, new Item.Settings().maxCount(1));
        return Registry.register(Registries.ITEM, Identifier.of(MOD_ID, name), item);
    }

    @Override
    public void onInitialize() {
        ComputerNetworking.registerCommon();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.currentScreenHandler instanceof ComputerScreenHandler handler) {
                    handler.pushState();
                }
            }
        });
    }
}
