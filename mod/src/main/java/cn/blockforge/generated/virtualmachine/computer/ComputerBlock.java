package cn.blockforge.generated.virtualmachine.computer;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 电脑方块。右键开机菜单，手里拿着 ISO/IMG 右键就直接插进驱动器。
 */
public class ComputerBlock extends HorizontalFacingBlock implements BlockEntityProvider {
    public static final MapCodec<ComputerBlock> CODEC = createCodec(ComputerBlock::new);
    public static final EnumProperty<ComputerVisual> SCREEN =
        EnumProperty.of("screen", ComputerVisual.class);

    public ComputerBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
            .with(FACING, Direction.NORTH)
            .with(SCREEN, ComputerVisual.OFF));
    }

    @Override
    protected MapCodec<? extends HorizontalFacingBlock> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SCREEN);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world == null || world.isClient) {
            return null;
        }
        return (tickWorld, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof ComputerBlockEntity computer) {
                computer.serverTick();
            }
        };
    }

    /** 空手（或者拿着别的东西）右键：打开电脑界面。 */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer
            && world.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            computer.openGui(serverPlayer);
        }
        return ActionResult.SUCCESS;
    }

    /** 手里拿着安装盘右键：先把盘插进去（机器会自动检测、自动装），再打开界面。潜行右键只开界面。 */
    @Override
    protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                             PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                if (!player.isSneaking()) {
                    computer.tryInsertDisk(stack, player);
                }
                computer.openGui(serverPlayer);
            }
            return ItemActionResult.SUCCESS;
        }
        return super.onUseWithItem(stack, state, world, pos, player, hand, hit);
    }

    /** 拆掉电脑的时候把光驱里的盘吐出来。 */
    @Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && !world.isClient
            && world.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            computer.dropDisk(world, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
