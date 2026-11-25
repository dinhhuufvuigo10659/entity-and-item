package io.zcl.entity.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import io.zcl.entity.EntityMod;
import io.zcl.entity.config.Config;
import io.zcl.entity.config.LabelPosition;
import io.zcl.entity.config.ListMode;

/**
 * 实体渲染聚合 + 掉落物渲染合并（Minecraft 26.3）。
 *
 * <p>算法完全沿用原实现：同 key 的候选实体先按三维半径并查集连锁成组，组内成员数达到阈值才合并；
 * 代表实体取离相机最近的那个（上一帧的代表仍在组内时优先保留，避免相邻成员反复交换造成标签跳动）；
 * 实体标签显示成员数，掉落物标签显示物品总件数。</p>
 *
 * <p>实体与掉落物是两套完全独立的东西：各自一套扫描通道（entityScan / itemScan）、
 * 各自的计时器、各自的过滤名单与标签位置，互不干扰。</p>
 *
 * <p>26.3 的标签不再手写绘制，而是在实体抽取阶段写进 {@link EntityRenderState}
 * （nameTag / nameTagAttachment），再在 {@link #layoutLabels(LevelRenderState)} 里做屏幕空间防重叠。</p>
 */
public final class EntityRenderAggregation
{
    private static final EntityRenderAggregation INSTANCE = new EntityRenderAggregation();
    private final Map<Integer, Group> groupsByEntityId = new HashMap<>();
    private ClientLevel trackedLevel;
    private long lastRebuildTick = Long.MIN_VALUE;
    // Only numeric, immutable entity snapshots cross this thread boundary.
    private static final ExecutorService COUNT_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "entity-aggregation");
        thread.setDaemon(true);
        return thread;
    });
    private final ScanChannel entityScan = new ScanChannel();
    private final ScanChannel itemScan = new ScanChannel();
    private final Map<Integer, Double> previousLabelOffsets = new HashMap<>();
    /** 本帧已挂标签的渲染状态 -> 实体 id，用于抽取全部完成后再统一排版。 */
    private final Map<EntityRenderState, Integer> frameLabels = new IdentityHashMap<>();
    // Vanilla SubmitNodeCollection uses this scale and adds 0.5 to the attachment's Y.
    private static final float NAME_TAG_SCALE = 0.025F;
    private static final double NAME_TAG_Y_OFFSET = 0.5D;
    private static final double LABEL_GAP_PIXELS = 3.0D;

    private EntityRenderAggregation() {}

    public static EntityRenderAggregation getInstance()
    {
        return INSTANCE;
    }

    /** 客户端 tick：两套扫描通道各自按时刷新，谁也不等谁。 */
    public void onClientTick(Minecraft client)
    {
        Config config = Config.get();

        if ((!config.entityRenderAggregation && !config.itemRenderAggregation) || client.level == null)
        {
            clear();
            return;
        }

        if (this.trackedLevel != client.level)
        {
            clear();
            this.trackedLevel = client.level;
            this.lastRebuildTick = Long.MIN_VALUE;
        }

        long gameTime = client.level.getGameTime();
        tickChannel(client, this.entityScan, false, gameTime, config.entityAggregationScanInterval);
        tickChannel(client, this.itemScan, true, gameTime, config.itemAggregationScanInterval);
    }

    public void beginRenderFrame()
    {
        this.frameLabels.clear();
        for (Group group : new HashSet<>(this.groupsByEntityId.values()))
        {
            // Resolve the anchor once for the frame, independently of which
            // visibility hook a renderer uses. Recover immediately if it leaves.
            Entity representative = this.trackedLevel == null ? null
                    : this.trackedLevel.getEntity(group.representativeId);
            if (representative == null || representative.isRemoved())
            {
                group.representativeId = group.memberIds.stream().sorted()
                        .filter(id -> {
                            Entity member = this.trackedLevel == null ? null : this.trackedLevel.getEntity(id);
                            return member != null && !member.isRemoved();
                        }).findFirst().orElse(-1);
            }
        }
    }

    public boolean shouldHide(Entity entity)
    {
        Group group = this.groupsByEntityId.get(entity.getId());
        if (group == null) return false;

        return group.representativeId != entity.getId();
    }

    public List<Integer> getItemMemberIds(ItemEntity entity)
    {
        Group group = this.groupsByEntityId.get(entity.getId());
        return group == null || !group.item ? List.of(entity.getId()) : List.copyOf(group.memberIds);
    }

    public boolean shouldHideModel(Entity entity)
    {
        Group group = this.groupsByEntityId.get(entity.getId());
        return Config.get().entityAggregationCountOnly && group != null && !group.item;
    }

    public Iterable<Entity> filterForRendering(Iterable<Entity> entities)
    {
        Config config = Config.get();
        if ((!config.entityRenderAggregation && !config.itemRenderAggregation) || this.groupsByEntityId.isEmpty())
        {
            return entities;
        }

        List<Entity> filtered = new ArrayList<>();
        for (Entity entity : entities)
        {
            if (!config.entityRenderAggregation && !config.itemRenderAggregation)
            {
                filtered.add(entity);
                continue;
            }
            Group group = this.groupsByEntityId.get(entity.getId());
            if (group == null || group.representativeId == entity.getId())
            {
                filtered.add(entity);
            }
        }
        return filtered;
    }

    public Component getLabel(Entity entity)
    {
        if (isLabelHidden(entity)) return null;

        Group group = this.groupsByEntityId.get(entity.getId());
        return group != null && group.representativeId == entity.getId() ? group.label : null;
    }

    public void clear()
    {
        this.entityScan.cancel();
        this.itemScan.cancel();
        this.previousLabelOffsets.clear();
        this.frameLabels.clear();
        this.groupsByEntityId.clear();
        this.trackedLevel = null;
        this.lastRebuildTick = Long.MIN_VALUE;
    }

    /** 抽取某个代表实体的渲染状态时登记一下，供本帧末尾统一排版。 */
    public void trackLabel(Entity entity, EntityRenderState state)
    {
        this.frameLabels.put(state, entity.getId());
    }

    /** All visible representatives must be extracted before resolving screen-space collisions. */
    public void layoutLabels(LevelRenderState levelState)
    {
        if (this.frameLabels.size() < 2)
        {
            return;
        }
        var camera = levelState.cameraRenderState;
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getWidth(), height = client.getWindow().getHeight();
        if (camera == null || camera.pos == null || camera.orientation == null || width <= 0 || height <= 0) return;
        if (camera.projectionMatrix == null || camera.viewRotationMatrix == null) return;
        Matrix4f projectionMatrix = camera.projectionMatrix;
        Matrix4f viewRotation = camera.viewRotationMatrix;
        float focalX = projectionMatrix.m00(), focalY = projectionMatrix.m11();
        if (focalX == 0 || focalY == 0) return;
        Matrix4f projection = new Matrix4f(projectionMatrix).mul(viewRotation);
        Matrix4f inverseView = new Matrix4f(viewRotation).invert();
        var labels = new ArrayList<AggregationLabelLayout.Label>();
        var projected = new HashMap<Integer, ProjectedLabel>();
        for (EntityRenderState state : levelState.entityRenderStates)
        {
            Integer id = this.frameLabels.get(state);
            if (id == null || state.nameTag == null || state.nameTagAttachment == null) continue;
            Vec3 attachment = state.nameTagAttachment;
            Vector4f clip = projection.transform(new Vector4f(
                    (float) (state.x + attachment.x - camera.pos.x),
                    (float) (state.y + attachment.y + NAME_TAG_Y_OFFSET - camera.pos.y),
                    (float) (state.z + attachment.z - camera.pos.z), 1));
            if (!Float.isFinite(clip.w) || clip.w <= 0 || Math.abs(clip.x) > clip.w ||
                    Math.abs(clip.y) > clip.w || Math.abs(clip.z) > clip.w) continue;
            double scaleX = NAME_TAG_SCALE * Math.abs(focalX) * width / (2.0D * clip.w);
            double scaleY = NAME_TAG_SCALE * Math.abs(focalY) * height / (2.0D * clip.w);
            // Include vanilla's nameplate background and shadow around the glyphs.
            double textWidth = (client.font.width(state.nameTag) + 2) * scaleX;
            double textHeight = (client.font.lineHeight + 2) * scaleY;
            labels.add(new AggregationLabelLayout.Label(id,
                    (clip.x / clip.w + 1) * width / 2.0D,
                    (1 - clip.y / clip.w) * height / 2.0D - scaleY,
                    textWidth, textHeight, state.distanceToCameraSq));
            projected.put(id, new ProjectedLabel(state, clip.w));
        }
        List<AggregationLabelLayout.Placement> placements = AggregationLabelLayout.arrange(
                labels, LABEL_GAP_PIXELS, this.previousLabelOffsets);
        for (var placement : placements)
        {
            if (placement.offsetY() == 0) continue;
            // Remember the collision side through a briefly isolated or
            // off-screen frame without moving a currently isolated label.
            this.previousLabelOffsets.put(placement.id(), placement.offsetY());
            ProjectedLabel label = projected.get(placement.id());
            float viewY = (float) (-2 * placement.offsetY() * label.clipW() / (height * focalY));
            Vector3f offset = inverseView.transformDirection(new Vector3f(0, viewY, 0));
            EntityRenderState state = label.state();
            state.nameTagAttachment = state.nameTagAttachment.add(offset.x, offset.y, offset.z);
        }
    }

    private record ProjectedLabel(EntityRenderState state, float clipW) {}

    /** 实体配置变动：只丢实体组，掉落物组不动。 */
    public void clearEntities()
    {
        clearChannel(this.entityScan, false);
    }

    /** 掉落物配置变动：只丢掉落物组，实体组不动。 */
    public void clearItems()
    {
        clearChannel(this.itemScan, true);
    }

    private void clearChannel(ScanChannel channel, boolean item)
    {
        channel.cancel();
        this.groupsByEntityId.entrySet().removeIf(entry -> {
            if (entry.getValue().item != item) return false;
            this.previousLabelOffsets.remove(entry.getKey());
            return true;
        });
    }

    private void tickChannel(Minecraft client, ScanChannel channel, boolean item, long gameTime, int interval)
    {
        Config config = Config.get();
        boolean enabled = item ? config.itemRenderAggregation : config.entityRenderAggregation;
        if (!enabled)
        {
            clearChannel(channel, item);
            return;
        }
        if (channel.pending != null && channel.pending.isDone()) publishCompleted(channel, item);
        // Each domain owns its schedule and publication; an item refresh must
        // not replace mob groups or cancel their unfinished computation.
        if (channel.pending == null && (channel.lastRebuildTick == Long.MIN_VALUE ||
                gameTime < channel.lastRebuildTick || gameTime - channel.lastRebuildTick >= interval))
        {
            Snapshot snapshot = capture(client.level, client, item);
            channel.pendingMetadata = snapshot.metadata();
            channel.pendingGeneration = channel.generation;
            channel.pending = COUNT_EXECUTOR.submit(() -> calculate(snapshot));
            channel.lastRebuildTick = gameTime;
        }
    }

    private void publishCompleted(ScanChannel channel, boolean item)
    {
        Future<List<GroupResult>> completed = channel.pending;
        Map<Integer, GroupMeta> metadata = channel.pendingMetadata;
        long completedGeneration = channel.pendingGeneration;
        channel.pending = null;
        channel.pendingMetadata = null;
        try
        {
            List<GroupResult> results = completed.get();
            if (completedGeneration != channel.generation || metadata == null) return;

            publishResults(results, metadata, item);
        }
        catch (CancellationException ignored)
        {
            // Disabling aggregation or changing worlds deliberately invalidates the job.
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException exception)
        {
            EntityMod.LOGGER.warn("Entity aggregation worker failed", exception);
        }
    }

    private void publishResults(List<GroupResult> results, Map<Integer, GroupMeta> metadata, boolean item)
    {
        this.groupsByEntityId.entrySet().removeIf(entry -> entry.getValue().item == item);
        for (GroupResult result : results)
        {
            GroupMeta meta = metadata.get(result.keyId());
            if (meta == null) continue;
            Group group = new Group(meta.key(), meta.displayName(), meta.item(),
                    result.memberIds().size(), result.displayCount(), result.representativeId());
            group.memberIds.addAll(result.memberIds());
            for (int entityId : result.memberIds())
            {
                this.groupsByEntityId.put(entityId, group);
            }
        }
        this.previousLabelOffsets.keySet().retainAll(this.groupsByEntityId.keySet());
    }

    private Snapshot capture(ClientLevel level, Minecraft client, boolean item)
    {
        Map<Object, Integer> keyIds = new HashMap<>();
        Map<Integer, GroupMeta> metadata = new HashMap<>();
        Map<Integer, Integer> previousRepresentatives = new HashMap<>();
        List<FlatCandidate> candidates = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering())
        {
            Candidate candidate = candidateFor(entity);
            if (candidate == null || candidate.item() != item) continue;
            int keyId = keyIds.computeIfAbsent(candidate.key(), ignored -> keyIds.size());
            metadata.putIfAbsent(keyId,
                    new GroupMeta(candidate.key(), candidate.displayName(), candidate.item()));
            Group previous = this.groupsByEntityId.get(entity.getId());
            if (previous != null && previous.key.equals(candidate.key()))
            {
                previousRepresentatives.put(entity.getId(), previous.representativeId);
            }
            Vec3 position = candidate.position();
            candidates.add(new FlatCandidate(entity.getId(), keyId,
                    position.x, position.y, position.z, candidate.itemCount(), candidate.item()));
        }
        Vec3 camera = client.getCameraEntity() != null
                ? client.getCameraEntity().position() : Vec3.ZERO;
        Config config = Config.get();
        double radius = item ? config.itemAggregationRadius : config.entityAggregationRadius;
        int threshold = item ? config.itemAggregationThreshold : config.entityAggregationThreshold;
        return new Snapshot(List.copyOf(candidates), metadata, Map.copyOf(previousRepresentatives),
                camera.x, camera.y, camera.z, radius, threshold);
    }

    private static List<GroupResult> calculate(Snapshot snapshot)
    {
        Map<Integer, List<FlatCandidate>> byKey = new HashMap<>();
        for (FlatCandidate candidate : snapshot.candidates())
        {
            byKey.computeIfAbsent(candidate.keyId(), ignored -> new ArrayList<>()).add(candidate);
        }

        List<GroupResult> results = new ArrayList<>();
        double radius = snapshot.radius();
        double radiusSquared = radius * radius;
        for (List<FlatCandidate> candidates : byKey.values())
        {
            if (Thread.currentThread().isInterrupted()) return List.of();
            int threshold = snapshot.threshold();
            if (candidates.size() < threshold) continue;

            UnionFind unionFind = new UnionFind(candidates.size());
            Map<Cell, List<Integer>> buckets = new HashMap<>();
            for (int index = 0; index < candidates.size(); index++)
            {
                if (Thread.currentThread().isInterrupted()) return List.of();
                FlatCandidate candidate = candidates.get(index);
                Cell cell = Cell.of(candidate.x(), candidate.y(), candidate.z(), radius);
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        for (int dz = -1; dz <= 1; dz++)
                        {
                            List<Integer> neighbors = buckets.get(cell.offset(dx, dy, dz));
                            if (neighbors == null) continue;
                            for (int neighbor : neighbors)
                            {
                                if (candidate.distanceSquared(candidates.get(neighbor)) <= radiusSquared)
                                {
                                    unionFind.union(index, neighbor);
                                }
                            }
                        }
                    }
                }
                buckets.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(index);
            }

            Map<Integer, List<FlatCandidate>> components = new HashMap<>();
            for (int index = 0; index < candidates.size(); index++)
            {
                components.computeIfAbsent(unionFind.find(index), ignored -> new ArrayList<>())
                        .add(candidates.get(index));
            }
            for (List<FlatCandidate> component : components.values())
            {
                if (component.size() < threshold) continue;
                FlatCandidate representative = component.stream()
                        .min(Comparator.comparingDouble((FlatCandidate candidate) -> candidate.distanceSquared(
                                snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ()))
                                .thenComparingInt(FlatCandidate::entityId))
                        .orElseThrow();
                // Nearby members can trade "closest to camera" on successive scans.
                // Keep the previous anchor while it remains in this local cluster.
                FlatCandidate nearest = representative;
                FlatCandidate previous = component.stream()
                        .filter(candidate -> snapshot.previousRepresentatives()
                                .getOrDefault(candidate.entityId(), -1) == candidate.entityId())
                        .min(Comparator.comparingDouble((FlatCandidate candidate) -> candidate.distanceSquared(nearest))
                                .thenComparingInt(FlatCandidate::entityId))
                        .orElse(null);
                if (previous != null && previous.distanceSquared(nearest) <= radiusSquared)
                {
                    representative = previous;
                }
                List<Integer> ids = component.stream().map(FlatCandidate::entityId).toList();
                int displayCount = component.stream().mapToInt(FlatCandidate::itemCount).sum();
                results.add(new GroupResult(component.getFirst().keyId(), ids,
                        representative.entityId(), displayCount));
            }
        }
        return results;
    }

    private record Snapshot(List<FlatCandidate> candidates, Map<Integer, GroupMeta> metadata,
                            Map<Integer, Integer> previousRepresentatives,
                            double cameraX, double cameraY, double cameraZ, double radius, int threshold) {}

    private static final class ScanChannel
    {
        private Future<List<GroupResult>> pending;
        private Map<Integer, GroupMeta> pendingMetadata;
        private long generation;
        private long pendingGeneration;
        private long lastRebuildTick = Long.MIN_VALUE;

        private void cancel()
        {
            this.generation++;
            if (this.pending != null) this.pending.cancel(true);
            this.pending = null;
            this.pendingMetadata = null;
            this.lastRebuildTick = Long.MIN_VALUE;
        }
    }

    private record GroupMeta(Object key, Component displayName, boolean item) {}
    private record GroupResult(int keyId, List<Integer> memberIds, int representativeId, int displayCount) {}

    private record FlatCandidate(int entityId, int keyId, double x, double y, double z,
                                 int itemCount, boolean item)
    {
        private double distanceSquared(FlatCandidate other)
        {
            return distanceSquared(other.x, other.y, other.z);
        }

        private double distanceSquared(double otherX, double otherY, double otherZ)
        {
            double dx = this.x - otherX;
            double dy = this.y - otherY;
            double dz = this.z - otherZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static Candidate candidateFor(Entity entity)
    {
        if (entity.isRemoved() || entity instanceof Player || entity instanceof ArmorStand || entity instanceof Display ||
                entity instanceof EnderDragon || entity instanceof WitherBoss ||
                (entity.hasCustomName() && !(entity instanceof ItemEntity)))
        {
            return null;
        }

        if (entity instanceof ItemEntity itemEntity)
        {
            if (!isItemAggregationEnabled(itemEntity)) return null;
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) return null;
            return new Candidate(entity, new ItemKey(stack.copy()), stack.getHoverName(), true, stack.getCount());
        }

        if (entity instanceof LivingEntity living)
        {
            Config config = Config.get();
            if (!matchesEntityList(entity, config)) return null;
            if (!config.entityRenderAggregation) return null;
            EntityType<?> type = entity.getType();
            if (config.entityAggregationSeparateSizes)
            {
                // Standing dimensions distinguish baby/adult mobs, slime sizes and scale
                // attributes without splitting a group merely because its pose changes.
                var dimensions = living.getDimensions(Pose.STANDING);
                int cubeSize = 0;
                if (living instanceof AbstractCubeMob cubeMob) cubeSize = cubeMob.getSize();
                Object key = new MobSizeKey(type, living.isBaby(), dimensions.width(), dimensions.height(), cubeSize);
                Component displayName = type.getDescription();
                if (cubeSize > 0)
                {
                    String size = cubeSize <= 1 ? "small" : cubeSize < 4 ? "medium" : "large";
                    displayName = Component.translatable("entity.entity_aggregation.size." + size, displayName);
                    if (living.isBaby())
                    {
                        displayName = Component.translatable("entity.entity_aggregation.baby_cube", displayName);
                    }
                }
                else if (living.isBaby())
                {
                    displayName = Component.translatable("entity.entity_aggregation.baby", displayName);
                }
                return new Candidate(entity, key, displayName, false, 1);
            }
            return new Candidate(entity, type, type.getDescription(), false, 1);
        }

        return null;
    }

    private static boolean matchesEntityList(Entity entity, Config config)
    {
        ListMode mode = config.entityAggregationListMode;
        if (mode == ListMode.NONE) return true;

        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        List<String> configuredList = mode == ListMode.WHITELIST
                ? config.entityAggregationWhitelist
                : config.entityAggregationBlacklist;
        boolean listed = configuredList.stream()
                .map(String::trim)
                .anyMatch(typeId::equals);
        return mode == ListMode.WHITELIST ? listed : !listed;
    }

    public static boolean isItemAggregationEnabled(ItemEntity entity)
    {
        return isItemAggregationEnabled(entity.getItem());
    }

    public static boolean isItemAggregationEnabled(ItemStack stack)
    {
        Config config = Config.get();
        if (!config.itemRenderAggregation) return false;
        if (stack.isEmpty()) return false;
        ListMode mode = config.itemAggregationListMode;
        if (mode == ListMode.NONE) return true;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        List<String> configured = mode == ListMode.WHITELIST
                ? config.itemAggregationWhitelist : config.itemAggregationBlacklist;
        boolean listed = configured.stream().map(String::trim).anyMatch(itemId::equals);
        return mode == ListMode.WHITELIST ? listed : !listed;
    }

    public static boolean isSideLabel(Entity entity)
    {
        Config config = Config.get();
        LabelPosition position = entity instanceof ItemEntity
                ? config.itemAggregationLabelPosition
                : config.entityAggregationLabelPosition;
        return position == LabelPosition.SIDE;
    }

    /** 标签位置选了「隐藏标签」时，只合并、只渲染代表实体，不画名称和数量。 */
    public static boolean isLabelHidden(Entity entity)
    {
        Config config = Config.get();
        LabelPosition position = entity instanceof ItemEntity
                ? config.itemAggregationLabelPosition
                : config.entityAggregationLabelPosition;
        return position == LabelPosition.HIDDEN;
    }

    private static final class Group
    {
        private final Object key;
        private final Component displayName;
        private final boolean item;
        private final List<Integer> memberIds = new ArrayList<>();
        private final int preferredRepresentativeId;
        private int entityCount;
        private int itemCount;
        private Component label;
        private int representativeId;

        private Group(Object key, Component displayName, boolean item, int entityCount, int itemCount, int preferredRepresentativeId)
        {
            this.key = key;
            this.displayName = displayName;
            this.item = item;
            this.entityCount = entityCount;
            this.itemCount = itemCount;
            this.preferredRepresentativeId = preferredRepresentativeId;
            this.representativeId = preferredRepresentativeId;
            this.updateLabel();
        }

        private void updateLabel()
        {
            int count = this.item ? this.itemCount : this.entityCount;
            this.label = Component.empty()
                    .append(this.displayName)
                    .append(Component.literal(" * " + count));
        }
    }

    private record Candidate(Entity entity, Object key, Component displayName, boolean item, int itemCount)
    {
        private Vec3 position()
        {
            return this.entity.position();
        }
    }

    private record MobSizeKey(EntityType<?> type, boolean baby, float width, float height, int cubeSize) {}

    private record Cell(int x, int y, int z)
    {
        private static Cell of(double x, double y, double z, double size)
        {
            return new Cell((int) Math.floor(x / size),
                    (int) Math.floor(y / size), (int) Math.floor(z / size));
        }

        private Cell offset(int x, int y, int z)
        {
            return new Cell(this.x + x, this.y + y, this.z + z);
        }
    }

    private static final class ItemKey
    {
        private final ItemStack stack;

        private ItemKey(ItemStack stack)
        {
            this.stack = stack;
        }

        @Override
        public boolean equals(Object object)
        {
            return object instanceof ItemKey other && ItemStack.isSameItemSameComponents(this.stack, other.stack);
        }

        @Override
        public int hashCode()
        {
            // Components are compared exactly in equals; bucket by item first
            // so component implementations with unstable hash codes still merge
            return this.stack.getItem().hashCode();
        }
    }

    private static final class UnionFind
    {
        private final int[] parents;

        private UnionFind(int size)
        {
            this.parents = new int[size];
            for (int index = 0; index < size; index++) this.parents[index] = index;
        }

        private int find(int value)
        {
            if (this.parents[value] != value) this.parents[value] = find(this.parents[value]);
            return this.parents[value];
        }

        private void union(int first, int second)
        {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot != secondRoot) this.parents[secondRoot] = firstRoot;
        }
    }
}