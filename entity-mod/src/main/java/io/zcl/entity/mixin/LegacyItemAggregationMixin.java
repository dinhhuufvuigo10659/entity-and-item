package io.zcl.entity.mixin;

import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.zcl.entity.feature.EntityRenderAggregation;

/**
 * 掉落物渲染合并：原版对同一堆物品会按 count 画多个模型，
 * 合并开关打开时强制只画一个。
 */
@Mixin(ItemEntityRenderer.class)
public abstract class LegacyItemAggregationMixin
{
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL"))
    private void entity_singleItemModel(ItemEntity entity, ItemEntityRenderState state, float partialTick, CallbackInfo ci)
    {
        if (EntityRenderAggregation.isItemAggregationEnabled(entity)) state.count = 1;
    }
}