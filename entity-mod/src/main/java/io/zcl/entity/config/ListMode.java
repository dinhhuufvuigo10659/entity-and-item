package io.zcl.entity.config;

/** 名单处理方式：关闭 / 白名单 / 黑名单。字符串取值沿用原 halfmasa 配置。 */
public enum ListMode
{
    NONE("none"),
    WHITELIST("whitelist"),
    BLACKLIST("blacklist");

    private final String value;

    ListMode(String value)
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
            case NONE -> "关闭";
            case WHITELIST -> "白名单";
            case BLACKLIST -> "黑名单";
        };
    }

    public static ListMode fromString(String value)
    {
        for (ListMode mode : values())
        {
            if (mode.value.equalsIgnoreCase(value))
            {
                return mode;
            }
        }
        return NONE;
    }
}