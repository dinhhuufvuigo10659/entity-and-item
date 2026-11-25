package io.zcl.entity.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import io.zcl.entity.gui.AggregationConfigScreen;

/** 在 ModMenu 的模组条目上注册「配置」按钮，点开就是 Cloth Config 生成的界面。 */
public final class ModMenuIntegration implements ModMenuApi
{
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory()
    {
        return AggregationConfigScreen::create;
    }
}