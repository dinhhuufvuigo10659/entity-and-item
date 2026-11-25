package io.zcl.entity.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.zcl.entity.feature.EntityRenderAggregation;

/**
 * 实体渲染聚合入口（Minecraft 26.3）。
 *
 * <ul>
 *     <li>extract HEAD：刷新每帧的代表实体锚点；</li>
 *     <li>entitiesForRendering()：把被合并掉的成员实体从抽取列表里剔除；</li>
 *     <li>extractVisibleEntities TAIL：所有代表实体的标签都挂好之后再做屏幕空间防重叠；</li>
 *     <li>isEntityVisible：兜底——替换过渲染器的环境里字段过滤可能被绕过。</li>
 * </ul>
 */
@Mixin(value = LevelExtractor.class, priority = 900)
public abstract class EntityRenderAggregationMixin
{
    @Inject(method = "extract", at = @At("HEAD"))
    private void entity_beginAggregationFrame(CallbackInfo ci)
    {
        EntityRenderAggregation.getInstance().beginRenderFrame();
    }

    @Redirect(
            method = "extractVisibleEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"),
            require = 0)
    private Iterable<Entity> entity_filterAggregatedEntities(ClientLevel level)
    {
        return EntityRenderAggregation.getInstance().filterForRendering(level.entitiesForRendering());
    }

    @Inject(method = "extractVisibleEntities", at = @At("TAIL"))
    private void entity_layoutAggregationLabels(Camera camera, Frustum frustum,
            DeltaTracker delta, LevelRenderState levelState, CallbackInfo ci)
    {
        EntityRenderAggregation.getInstance().layoutLabels(levelState);
    }

    // Check the visibility boundary as well: renderer replacements may bypass
    // the entitiesForRendering redirect while still calling isEntityVisible.
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true, require = 0)
    private void entity_hideGroupedVisibleEntity(Entity entity, Frustum frustum,
            double cameraX, double cameraY, double cameraZ, float partialTick, long fadeTime,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (EntityRenderAggregation.getInstance().shouldHide(entity))
        {
            cir.setReturnValue(false);
        }
    }
}