package io.zcl.entity.gui;

import java.util.ArrayList;
import java.util.List;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import io.zcl.entity.config.Config;
import io.zcl.entity.config.LabelPosition;
import io.zcl.entity.config.ListMode;
import io.zcl.entity.feature.EntityRenderAggregation;

/**
 * Cloth Config 配置界面。
 *
 * <p>只有一个分类「已停用功能」，里面两个独立可折叠的子分组：实体聚合组、掉落物合并组。
 * 分类和分组上都写了兼容性风险提示。</p>
 *
 * <p>所有条目直接读写 {@link Config} 的字段；保存时统一落盘，并按「实体 / 掉落物」
 * 各自清理对应的聚合缓存，互不干扰。</p>
 */
public final class AggregationConfigScreen
{
    private static final Component RISK_WARNING = Component.literal(
            "以下功能会改变实体的渲染方式：被合并的实体仍然正常 tick、碰撞与交互，"
                    + "但只显示一个代表实体和「名称 * 数量」标签。"
                    + "与其它改写实体渲染的模组（Sodium / Iris / 实体剔除 / 光影等）同时使用时"
                    + "可能出现绘制异常或崩溃，请自行评估兼容性风险后再开启。");

    private static final Component ENTITY_GROUP_WARNING = Component.literal(
            "实体聚合组：只影响生物实体，不影响掉落物。"
                    + "「仅统计数量」会隐藏代表实体的模型；名单留空时白名单不会匹配任何实体。");

    private static final Component ITEM_GROUP_WARNING = Component.literal(
            "掉落物合并组：只影响掉落的物品实体，不影响生物。"
                    + "合并数统计的是掉落物堆数，标签显示的是物品总件数；名单留空时白名单不会匹配任何物品。");

    private AggregationConfigScreen() {}

    public static Screen create(Screen parent)
    {
        Config config = Config.get();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("渲染合并 配置"))
                .setSavingRunnable(AggregationConfigScreen::save);

        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory category = builder.getOrCreateCategory(Component.literal("已停用功能"));
        category.setDescription(new Component[] { RISK_WARNING });

        // ---------------------------------------------------------- 实体聚合组
        // Cloth 的 startSubCategory 需要原始类型 List<AbstractConfigListEntry>，不能带 <?>
        @SuppressWarnings("rawtypes")
        List<AbstractConfigListEntry> entityGroup = new ArrayList<>();
        entityGroup.add(entries.startTextDescription(ENTITY_GROUP_WARNING).build());
        entityGroup.add(entries.startBooleanToggle(
                        Component.literal("同类实体渲染聚合"),
                        config.entityRenderAggregation)
                .setDefaultValue(false)
                .setTooltip(Component.literal(
                        "同类生物达到合并数量时只渲染一个代表实体并显示数量；实体仍会正常 tick、碰撞和交互。默认关闭"))
                .setSaveConsumer(value -> config.entityRenderAggregation = value)
                .build());
        entityGroup.add(entries.startBooleanToggle(
                        Component.literal("小生物与大生物分开计算"),
                        config.entityAggregationSeparateSizes)
                .setDefaultValue(true)
                .setTooltip(Component.literal(
                        "按幼年 / 成年和体型分别统计、分别判断阈值、分别渲染代表实体；关闭后同类生物一起计算。默认开启"))
                .setSaveConsumer(value -> config.entityAggregationSeparateSizes = value)
                .build());
        entityGroup.add(entries.startBooleanToggle(
                        Component.literal("仅统计数量（不渲染模型）"),
                        config.entityAggregationCountOnly)
                .setDefaultValue(false)
                .setTooltip(Component.literal(
                        "隐藏实体聚合组的代表模型，只保留名称和数量标签；不影响掉落物合并。默认关闭"))
                .setSaveConsumer(value -> config.entityAggregationCountOnly = value)
                .build());
        entityGroup.add(entries.startDoubleField(
                        Component.literal("实体聚合半径"),
                        config.entityAggregationRadius)
                .setDefaultValue(1.0D)
                .setMin(0.25D)
                .setMax(64.0D)
                .setTooltip(Component.literal("同类型实体之间按三维距离连锁聚合的最大半径，默认 1 格"))
                .setSaveConsumer(value -> config.entityAggregationRadius = value)
                .build());
        entityGroup.add(entries.startIntField(
                        Component.literal("实体合并数"),
                        config.entityAggregationThreshold)
                .setDefaultValue(11)
                .setMin(1)
                .setMax(10001)
                .setTooltip(Component.literal("同类生物至少达到此数量才只渲染一个代表实体，默认 11"))
                .setSaveConsumer(value -> config.entityAggregationThreshold = value)
                .build());
        entityGroup.add(entries.startIntField(
                        Component.literal("实体扫描刷新间隔（tick）"),
                        config.entityAggregationScanInterval)
                .setDefaultValue(10)
                .setMin(1)
                .setMax(100)
                .setTooltip(Component.literal("统计实体数量的客户端 tick 间隔，默认 10 tick 约半秒；独立于掉落物刷新间隔"))
                .setSaveConsumer(value -> config.entityAggregationScanInterval = value)
                .build());
        entityGroup.add(entries.startSelector(
                        Component.literal("实体聚合标签位置"),
                        LabelPosition.displayNames().toArray(new String[0]),
                        config.entityAggregationLabelPosition.getDisplayName())
                .setDefaultValue(LabelPosition.TOP.getDisplayName())
                .setTooltip(Component.literal("名称和数量显示在代表实体的头顶或侧边"))
                .setSaveConsumer(value -> config.entityAggregationLabelPosition = LabelPosition.fromString(value))
                .build());
        entityGroup.add(entries.startEnumSelector(
                        Component.literal("实体名单模式"),
                        ListMode.class,
                        config.entityAggregationListMode)
                .setDefaultValue(ListMode.NONE)
                .setEnumNameProvider(mode -> Component.literal(((ListMode) mode).getDisplayName()))
                .setTooltip(Component.literal("关闭聚合所有符合条件的实体，白名单只聚合列表内实体，黑名单排除列表内实体"))
                .setSaveConsumer(value -> config.entityAggregationListMode = value)
                .build());
        entityGroup.add(entries.startStrList(
                        Component.literal("实体白名单"),
                        new ArrayList<>(config.entityAggregationWhitelist))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Component.literal("名单模式为白名单时参与聚合的生物类型 ID，例如 minecraft:zombie 或 minecraft:cow"))
                .setSaveConsumer(value -> config.entityAggregationWhitelist = new ArrayList<>(value))
                .build());
        entityGroup.add(entries.startStrList(
                        Component.literal("实体黑名单"),
                        new ArrayList<>(config.entityAggregationBlacklist))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Component.literal("名单模式为黑名单时排除聚合的生物类型 ID，例如 minecraft:zombie 或 minecraft:cow"))
                .setSaveConsumer(value -> config.entityAggregationBlacklist = new ArrayList<>(value))
                .build());

        // --------------------------------------------------------- 掉落物合并组
        @SuppressWarnings("rawtypes")
        List<AbstractConfigListEntry> itemGroup = new ArrayList<>();
        itemGroup.add(entries.startTextDescription(ITEM_GROUP_WARNING).build());
        itemGroup.add(entries.startBooleanToggle(
                        Component.literal("掉落物渲染合并"),
                        config.itemRenderAggregation)
                .setDefaultValue(true)
                .setTooltip(Component.literal(
                        "每堆符合名单的掉落物只渲染一个物品模型，并合并附近同物品的掉落实体、显示物品总数。默认开启"))
                .setSaveConsumer(value -> config.itemRenderAggregation = value)
                .build());
        itemGroup.add(entries.startIntField(
                        Component.literal("掉落物合并数"),
                        config.itemAggregationThreshold)
                .setDefaultValue(2)
                .setMin(1)
                .setMax(10000)
                .setTooltip(Component.literal("至少达到此数量的相同掉落实体才合并；这里算实体堆数，标签显示物品总件数。默认 2"))
                .setSaveConsumer(value -> config.itemAggregationThreshold = value)
                .build());
        itemGroup.add(entries.startDoubleField(
                        Component.literal("掉落物合并范围"),
                        config.itemAggregationRadius)
                .setDefaultValue(1.0D)
                .setMin(0.25D)
                .setMax(64.0D)
                .setTooltip(Component.literal("相同掉落实体之间按三维距离连锁合并的最大半径，默认 1 格"))
                .setSaveConsumer(value -> config.itemAggregationRadius = value)
                .build());
        itemGroup.add(entries.startSelector(
                        Component.literal("掉落物标签位置"),
                        LabelPosition.displayNames().toArray(new String[0]),
                        config.itemAggregationLabelPosition.getDisplayName())
                .setDefaultValue(LabelPosition.TOP.getDisplayName())
                .setTooltip(Component.literal(
                        "将掉落物名称和数量显示在代表掉落实体的头顶或侧边；选择「隐藏标签」时仍然合并、仍只渲染一个代表实体，"
                                + "只是不显示名称和数量"))
                .setSaveConsumer(value -> config.itemAggregationLabelPosition = LabelPosition.fromString(value))
                .build());
        itemGroup.add(entries.startEnumSelector(
                        Component.literal("掉落物名单模式"),
                        ListMode.class,
                        config.itemAggregationListMode)
                .setDefaultValue(ListMode.NONE)
                .setEnumNameProvider(mode -> Component.literal(((ListMode) mode).getDisplayName()))
                .setTooltip(Component.literal("关闭处理所有物品，白名单只处理列出的物品，黑名单排除列出的物品"))
                .setSaveConsumer(value -> config.itemAggregationListMode = value)
                .build());
        itemGroup.add(entries.startIntField(
                        Component.literal("掉落物扫描刷新间隔（tick）"),
                        config.itemAggregationScanInterval)
                .setDefaultValue(10)
                .setMin(1)
                .setMax(100)
                .setTooltip(Component.literal("掉落物数量统计的客户端 tick 间隔，独立于实体刷新间隔，默认 10 tick 约半秒"))
                .setSaveConsumer(value -> config.itemAggregationScanInterval = value)
                .build());
        itemGroup.add(entries.startStrList(
                        Component.literal("掉落物白名单"),
                        new ArrayList<>(config.itemAggregationWhitelist))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Component.literal("参与合并的物品 ID，例如 minecraft:stone 或 minecraft:diamond；空白名单不处理任何物品"))
                .setSaveConsumer(value -> config.itemAggregationWhitelist = new ArrayList<>(value))
                .build());
        itemGroup.add(entries.startStrList(
                        Component.literal("掉落物黑名单"),
                        new ArrayList<>(config.itemAggregationBlacklist))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Component.literal("排除合并的物品 ID，例如 minecraft:stone 或 minecraft:diamond"))
                .setSaveConsumer(value -> config.itemAggregationBlacklist = new ArrayList<>(value))
                .build());

        category.addEntry(entries.startSubCategory(Component.literal("实体聚合组"), entityGroup).build());
        category.addEntry(entries.startSubCategory(Component.literal("掉落物合并组"), itemGroup).build());

        return builder.build();
    }

    private static void save()
    {
        Config config = Config.get();
        config.save();
        // 前面的改动在下次扫描时会自然覆盖旧分组；这里只作安全兜底。
        // 两组各自清理自己的聚合结果，另一组不受影响。
        EntityRenderAggregation.getInstance().clearEntities();
        EntityRenderAggregation.getInstance().clearItems();
    }
}