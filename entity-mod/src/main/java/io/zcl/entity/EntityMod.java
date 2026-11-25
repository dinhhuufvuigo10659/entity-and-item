package io.zcl.entity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import io.zcl.entity.config.Config;
import io.zcl.entity.feature.EntityRenderAggregation;

/**
 * 渲染合并 —— 只做两件事：同类实体渲染聚合、掉落物渲染合并。
 */
public final class EntityMod implements ClientModInitializer
{
    public static final String MOD_ID = "entity";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitializeClient()
    {
        Config.get();

        ClientTickEvents.END_CLIENT_TICK.register(EntityRenderAggregation.getInstance()::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> EntityRenderAggregation.getInstance().clear());

        LOGGER.info("渲染合并 initialized: entity aggregation + dropped item merge only");
    }
}