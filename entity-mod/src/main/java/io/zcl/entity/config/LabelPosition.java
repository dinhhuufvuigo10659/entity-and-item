package io.zcl.entity.config;

import java.util.List;

/**
 * 聚合后「名称 * 数量」标签的挂载位置。
 *
 * <p>沿用原 halfmasa 的取值字符串（top / side），保证旧配置文件可以原样迁移；
 * 新增的 {@link #HIDDEN} 用 {@code hidden}，旧配置里不会出现，读到未知名时仍回落到头顶。</p>
 */
public enum LabelPosition
{
    /** 头顶：沿用原版名牌锚点（实体高度 + 0.5）。 */
    TOP("top"),
    /** 侧边：右侧偏移（宽度一半 + 0.35，高度一半）。 */
    SIDE("side"),
    /** 隐藏标签：仍合并、仍只渲染代表实体，但不显示名称和数量。 */
    HIDDEN("hidden");

    private final String value;

    LabelPosition(String value)
    {
        this.value = value;
    }

    /** 与配置 JSON 中的字符串一致。 */
    public String getStringValue()
    {
        return this.value;
    }

    public String getDisplayName()
    {
        return switch (this)
        {
            case TOP -> "头顶";
            case SIDE -> "侧边";
            case HIDDEN -> "隐藏标签";
        };
    }

    public static LabelPosition fromString(String value)
    {
        for (LabelPosition position : values())
        {
            if (position.value.equalsIgnoreCase(value))
            {
                return position;
            }
        }
        return TOP;
    }

    public static List<String> displayNames()
    {
        return List.of(TOP.getDisplayName(), SIDE.getDisplayName(), HIDDEN.getDisplayName());
    }
}