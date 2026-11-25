package io.zcl.entity.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.zcl.entity.feature.EntityRenderAggregation;

/**
 * 26.3 的实体渲染状态边界：
 * 在抽取阶段就把合并结果写进 {@link EntityRenderState}——
 * 代表实体挂上「名称 * 数量」标签与锚点，被合并的成员直接标记不可见。
 */
@Mixin(value = EntityRenderDispatcher.class, priority = 900)
public abstract class EntityRenderDispatcherAggregationMixin
{
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void entity_skipAggregatedEntity(
            Entity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ,
            float tickDelta,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (EntityRenderAggregation.getInstance().shouldHide(entity))
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void entity_applyAggregationState(
            E entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir)
    {
        EntityRenderState state = cir.getReturnValue();
        // Vanilla renders several copies for a single stacked ItemEntity. The
        // item switch should reduce that draw count even without a second entity.
        if (entity instanceof ItemEntity item && EntityRenderAggregation.isItemAggregationEnabled(item)
                && state instanceof ItemEntityRenderState itemState)
        {
            itemState.count = 1;
        }
        Component label = EntityRenderAggregation.getInstance().getLabel(entity);
        if (label != null)
        {
            boolean side = EntityRenderAggregation.isSideLabel(entity);
            state.nameTag = label;
            state.nameTagAttachment = new Vec3(
                    side ? entity.getBbWidth() * 0.5D + 0.35D : 0.0D,
                    side ? entity.getBbHeight() * 0.5D : entity.getBbHeight() + 0.5D,
                    0.0D);
            EntityRenderAggregation.getInstance().trackLabel(entity, state);
        }
        if (EntityRenderAggregation.getInstance().shouldHideModel(entity))
        {
            state.isInvisible = true;
        }
    }
}