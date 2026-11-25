package io.zcl.entity.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import io.zcl.entity.EntityMod;

/**
 * 模组配置。
 *
 * <p>内存里的字段就是 Cloth Config 直接读写的对象；load() / save() 负责序列化。
 * 为了兼容原版 halfmasa 已有的 config/halfmasa/halfmasa.json，加载时会按 <b>键名</b>
 * 去旧的 Disabled / Extensions 分组里取值：读到什么用什么，读不到的保留默认值。
 * 键名与字段名一一对应，所以旧配置无需改写。</p>
 */
public final class Config
{
    public static final String FILE_NAME = "entity.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    // ---------------------------------------------------------------- 实体聚合组

    /** 同类实体渲染聚合总开关。默认 false。 */
    public boolean entityRenderAggregation = false;
    /** 幼年 / 成年 / 不同体型分开分组计数。 */
    public boolean entityAggregationSeparateSizes = true;
    /** 仅统计数量不渲染实体模型（不影响掉落物）。 */
    public boolean entityAggregationCountOnly = false;
    /** 实体聚合三维半径。 */
    public double entityAggregationRadius = 1.0D;
    /** 触发合并的最低数量。 */
    public int entityAggregationThreshold = 11;
    /** 扫描刷新 tick 间隔。 */
    public int entityAggregationScanInterval = 10;
    /** 聚合标签位置。 */
    public LabelPosition entityAggregationLabelPosition = LabelPosition.TOP;
    /** 名单模式。 */
    public ListMode entityAggregationListMode = ListMode.NONE;
    /** 实体白名单（如 minecraft:zombie）。 */
    public List<String> entityAggregationWhitelist = new ArrayList<>();
    /** 实体黑名单。 */
    public List<String> entityAggregationBlacklist = new ArrayList<>();

    // ------------------------------------------------------------- 掉落物合并组

    /** 掉落物渲染合并总开关。默认 true。 */
    public boolean itemRenderAggregation = true;
    /** 掉落物触发合并最低数量。 */
    public int itemAggregationThreshold = 2;
    /** 掉落物合并三维范围。 */
    public double itemAggregationRadius = 1.0D;
    /** 名单模式。 */
    public ListMode itemAggregationListMode = ListMode.NONE;
    /** 物品白名单（如 minecraft:stone）。 */
    public List<String> itemAggregationWhitelist = new ArrayList<>();
    /** 物品黑名单。 */
    public List<String> itemAggregationBlacklist = new ArrayList<>();
    /** 掉落物标签位置。 */
    public LabelPosition itemAggregationLabelPosition = LabelPosition.TOP;
    /** 掉落物扫描刷新 tick 间隔。 */
    public int itemAggregationScanInterval = 10;

    private Config() {}

    public static Config get()
    {
        if (instance == null)
        {
            instance = load();
        }
        return instance;
    }

    private static Path configPath()
    {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 原 halfmasa 的配置文件位置，用于一次性迁移。 */
    private static Path legacyPath()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("halfmasa").resolve("halfmasa.json");
    }

    // ------------------------------------------------------------------ 读写

    private static Config load()
    {
        Config config = new Config();
        Path file = configPath();

        if (!Files.isReadable(file) && Files.isReadable(legacyPath()))
        {
            file = legacyPath();
            EntityMod.LOGGER.info("Migrating halfmasa aggregation settings from {}", file);
        }

        if (!Files.isReadable(file))
        {
            return config;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            JsonElement element = JsonParser.parseReader(reader);
            if (element != null && element.isJsonObject())
            {
                config.readFrom(element.getAsJsonObject());
            }
        }
        catch (IOException | RuntimeException exception)
        {
            EntityMod.LOGGER.warn("Failed to read config {}", file, exception);
        }
        return config;
    }

    /**
     * 依次在自己这一层、旧的 Disabled / Extensions / Client / Ported 分组里取值。
     * 键名是唯一的迁移依据。
     */
    private void readFrom(JsonObject root)
    {
        this.entityRenderAggregation = readBoolean(root, "entityRenderAggregation", this.entityRenderAggregation);
        this.entityAggregationSeparateSizes = readBoolean(root, "entityAggregationSeparateSizes", this.entityAggregationSeparateSizes);
        this.entityAggregationCountOnly = readBoolean(root, "entityAggregationCountOnly", this.entityAggregationCountOnly);
        this.entityAggregationRadius = readDouble(root, "entityAggregationRadius", this.entityAggregationRadius);
        this.entityAggregationThreshold = readInt(root, "entityAggregationThreshold", this.entityAggregationThreshold);
        this.entityAggregationScanInterval = readInt(root, "entityAggregationScanInterval", this.entityAggregationScanInterval);
        this.entityAggregationLabelPosition = LabelPosition.fromString(
                readString(root, "entityAggregationLabelPosition", this.entityAggregationLabelPosition.getStringValue()));
        this.entityAggregationListMode = ListMode.fromString(
                readString(root, "entityAggregationListMode", this.entityAggregationListMode.getStringValue()));
        this.entityAggregationWhitelist = readStrings(root, "entityAggregationWhitelist", this.entityAggregationWhitelist);
        this.entityAggregationBlacklist = readStrings(root, "entityAggregationBlacklist", this.entityAggregationBlacklist);

        this.itemRenderAggregation = readBoolean(root, "itemRenderAggregation", this.itemRenderAggregation);
        this.itemAggregationThreshold = readInt(root, "itemAggregationThreshold", this.itemAggregationThreshold);
        this.itemAggregationRadius = readDouble(root, "itemAggregationRadius", this.itemAggregationRadius);
        this.itemAggregationListMode = ListMode.fromString(
                readString(root, "itemAggregationListMode", this.itemAggregationListMode.getStringValue()));
        this.itemAggregationWhitelist = readStrings(root, "itemAggregationWhitelist", this.itemAggregationWhitelist);
        this.itemAggregationBlacklist = readStrings(root, "itemAggregationBlacklist", this.itemAggregationBlacklist);
        this.itemAggregationLabelPosition = LabelPosition.fromString(
                readString(root, "itemAggregationLabelPosition", this.itemAggregationLabelPosition.getStringValue()));
        this.itemAggregationScanInterval = readInt(root, "itemAggregationScanInterval", this.itemAggregationScanInterval);

        // 旧版 halfmasa < 14 只有一份合并名单 entityAggregationEntityList，尽量接过它的内容。
        if (this.entityAggregationWhitelist.isEmpty() && this.entityAggregationBlacklist.isEmpty())
        {
            List<String> legacy = readStrings(root, "entityAggregationEntityList", List.of());
            if (!legacy.isEmpty())
            {
                if (this.entityAggregationListMode == ListMode.BLACKLIST)
                {
                    this.entityAggregationBlacklist = new ArrayList<>(legacy);
                }
                else
                {
                    this.entityAggregationWhitelist = new ArrayList<>(legacy);
                }
            }
        }
    }

    private static JsonObject section(JsonObject root, String name)
    {
        JsonElement element = root.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonElement find(JsonObject root, String key)
    {
        if (root.has(key))
        {
            return root.get(key);
        }
        for (String sectionName : new String[] { "Disabled", "Extensions", "Client", "Ported" })
        {
            JsonObject section = section(root, sectionName);
            if (section != null && section.has(key))
            {
                return section.get(key);
            }
        }
        return null;
    }

    private static boolean readBoolean(JsonObject root, String key, boolean fallback)
    {
        try
        {
            JsonElement element = find(root, key);
            return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
        }
        catch (RuntimeException ignored)
        {
            return fallback;
        }
    }

    private static int readInt(JsonObject root, String key, int fallback)
    {
        try
        {
            JsonElement element = find(root, key);
            return element == null || element.isJsonNull() ? fallback : element.getAsInt();
        }
        catch (RuntimeException ignored)
        {
            return fallback;
        }
    }

    private static double readDouble(JsonObject root, String key, double fallback)
    {
        try
        {
            JsonElement element = find(root, key);
            return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
        }
        catch (RuntimeException ignored)
        {
            return fallback;
        }
    }

    private static String readString(JsonObject root, String key, String fallback)
    {
        try
        {
            JsonElement element = find(root, key);
            return element == null || element.isJsonNull() ? fallback : element.getAsString();
        }
        catch (RuntimeException ignored)
        {
            return fallback;
        }
    }

    private static List<String> readStrings(JsonObject root, String key, List<String> fallback)
    {
        try
        {
            JsonElement element = find(root, key);
            if (element == null || !element.isJsonArray())
            {
                return new ArrayList<>(fallback);
            }
            JsonArray array = element.getAsJsonArray();
            List<String> values = new ArrayList<>(array.size());
            for (JsonElement entry : array)
            {
                if (entry != null && !entry.isJsonNull())
                {
                    values.add(entry.getAsString());
                }
            }
            return values;
        }
        catch (RuntimeException ignored)
        {
            return new ArrayList<>(fallback);
        }
    }

    public void save()
    {
        JsonObject root = new JsonObject();
        root.addProperty("entityRenderAggregation", this.entityRenderAggregation);
        root.addProperty("entityAggregationSeparateSizes", this.entityAggregationSeparateSizes);
        root.addProperty("entityAggregationCountOnly", this.entityAggregationCountOnly);
        root.addProperty("entityAggregationRadius", this.entityAggregationRadius);
        root.addProperty("entityAggregationThreshold", this.entityAggregationThreshold);
        root.addProperty("entityAggregationScanInterval", this.entityAggregationScanInterval);
        root.addProperty("entityAggregationLabelPosition", this.entityAggregationLabelPosition.getStringValue());
        root.addProperty("entityAggregationListMode", this.entityAggregationListMode.getStringValue());
        root.add("entityAggregationWhitelist", toArray(this.entityAggregationWhitelist));
        root.add("entityAggregationBlacklist", toArray(this.entityAggregationBlacklist));

        root.addProperty("itemRenderAggregation", this.itemRenderAggregation);
        root.addProperty("itemAggregationThreshold", this.itemAggregationThreshold);
        root.addProperty("itemAggregationRadius", this.itemAggregationRadius);
        root.addProperty("itemAggregationListMode", this.itemAggregationListMode.getStringValue());
        root.add("itemAggregationWhitelist", toArray(this.itemAggregationWhitelist));
        root.add("itemAggregationBlacklist", toArray(this.itemAggregationBlacklist));
        root.addProperty("itemAggregationLabelPosition", this.itemAggregationLabelPosition.getStringValue());
        root.addProperty("itemAggregationScanInterval", this.itemAggregationScanInterval);

        Path file = configPath();
        try
        {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
            {
                GSON.toJson(root, writer);
            }
        }
        catch (IOException | RuntimeException exception)
        {
            EntityMod.LOGGER.warn("Failed to write config {}", file, exception);
        }
    }

    private static JsonArray toArray(List<String> values)
    {
        JsonArray array = new JsonArray();
        if (values != null)
        {
            for (String value : values)
            {
                if (value != null)
                {
                    array.add(value);
                }
            }
        }
        return array;
    }
}