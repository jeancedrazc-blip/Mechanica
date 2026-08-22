package mcjty.rftoolsbuilder.constructor;

import mcjty.rftoolsbuilder.constructor.plan.BlockSubstitutionRules;
import mcjty.rftoolsbuilder.constructor.plan.ConstructionJob;
import mcjty.rftoolsbuilder.constructor.plan.ConstructionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

import java.io.IOException;

/**
 * Server-authoritative FE schematic printer. A validated/deployed card is the
 * sole source of truth for the plan. Blocks, deferred blocks and supported
 * entities share one persistent cursor and one reservation/impact pipeline.
 */
public final class ConstructorBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity implements MenuProvider, Container {
    public static final int ENERGY_CAPACITY = 5_000_000;
    public static final int MAX_RECEIVE = 250_000;
    public static final int ENERGY_BLOCK_INTERVAL = 500;
    public static final int AIM_TICKS = 4;
    public static final int CHARGE_TICKS = 5;
    public static final int MIN_FLIGHT_TICKS = 10;
    public static final int MIN_SCALED_FLIGHT_TICKS = 3;
    public static final int SHOT_COOLDOWN_TICKS = 4;
    public static final int TABLET_SCAN_TICKS = 24;
    public static final int MAX_TARGET_DISTANCE = 256;
    public static final int SLOT_SCHEMATIC = 0;
    public static final int SLOT_TABLET_INPUT = 1;
    public static final int SLOT_TABLET_OUTPUT = 2;

    private final ConstructorEnergyStorage energy = new ConstructorEnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE);
    private final NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);

    private ConstructorStatus status = ConstructorStatus.IDLE;
    private ConstructorReplaceMode replaceMode = ConstructorReplaceMode.REPLACE_ANY;
    private ConstructorSpeed speed = ConstructorSpeed.NORMAL;
    private boolean skipMissing;
    private boolean replaceBlockEntities;
    private int energyBlockProgress;

    private BlockPos targetPos;
    private BlockState targetState;
    private boolean targetIsEntity;
    private int phaseTick;
    private int shotProgress;
    private int flightTicks = MIN_FLIGHT_TICKS;
    private int shotCooldown;
    private int tabletScanProgress;
    private boolean running;
    private boolean shotReserved;
    private boolean pauseAfterShot;
    private ItemStack reservedPlacementStack = ItemStack.EMPTY;
    private ItemStack entityVisualStack = ItemStack.EMPTY;
    private ItemStack missingItem = ItemStack.EMPTY;
    private ConstructionJob activeJob;
    private CompoundTag pendingJobData;
    private transient ConstructorEntitySupport.Prepared preparedEntity;

    private final ContainerData menuData = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0 -> energy.getEnergyStored();
                case 1 -> energy.getMaxEnergyStored();
                case 2 -> status.ordinal();
                case 3 -> jobIndex();
                case 4 -> jobTotal();
                case 5 -> shotProgress;
                case 6 -> running ? 1 : 0;
                case 7 -> currentEnergyCost();
                case 8 -> SchematicCardItem.hasSource(schematicCard()) && SchematicCardItem.deployed(schematicCard()) ? 1 : 0;
                case 9 -> replaceMode.ordinal();
                case 10 -> skipMissing ? 1 : 0;
                case 11 -> replaceBlockEntities ? 1 : 0;
                case 12 -> flightTicks;
                case 13 -> targetIsEntity ? 1 : 0;
                case 14 -> energy.getEnergyStored() & 0xFFFF;
                case 15 -> energy.getEnergyStored() >>> 16;
                case 16 -> energy.getMaxEnergyStored() & 0xFFFF;
                case 17 -> energy.getMaxEnergyStored() >>> 16;
                case 18 -> tabletScanProgress;
                case 19 -> speed.ordinal();
                case 20 -> energyBlockProgress;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {}
        @Override public int getCount() { return 21; }
    };

    public ConstructorBlockEntity(BlockPos pos, BlockState state) {
        super(ConstructorBootstrap.CONSTRUCTOR_BLOCK_ENTITY.get(), pos, state);
    }

    public ConstructorEnergyStorage energyStorage() { return energy; }
    public int storedEnergy() { return energy.getEnergyStored(); }
    public void restoreEnergy(int amount) { energy.setStored(amount); setChangedAndSync(); }
    public void restorePortableState(int amount, int speedOrdinal, int blockProgress) {
        energy.setStored(amount);
        speed = ConstructorSpeed.byOrdinal(speedOrdinal);
        energyBlockProgress = Math.floorMod(blockProgress, ENERGY_BLOCK_INTERVAL);
        setChangedAndSync();
    }
    public ConstructorStatus status() { return status; }
    public ConstructorReplaceMode replaceMode() { return replaceMode; }
    public ConstructorSpeed speed() { return speed; }
    public int energyBlockProgress() { return energyBlockProgress; }
    public boolean skipMissing() { return skipMissing; }
    public boolean replaceBlockEntities() { return replaceBlockEntities; }
    public BlockPos targetPos() { return targetPos; }
    public BlockState targetState() { return targetState; }
    public boolean targetIsEntity() { return targetIsEntity; }
    public ItemStack projectileItem() {
        return !reservedPlacementStack.isEmpty() ? reservedPlacementStack : entityVisualStack;
    }
    public int shotProgress() { return shotProgress; }
    public int flightTicks() { return Math.max(MIN_SCALED_FLIGHT_TICKS, flightTicks); }
    public boolean shotReserved() { return shotReserved; }
    public boolean isRunning() { return running; }
    public ItemStack missingItem() { return missingItem; }
    public ContainerData menuData() { return menuData; }
    public ItemStack schematicCard() { return items.get(SLOT_SCHEMATIC); }
    public ItemStack tabletInput() { return items.get(SLOT_TABLET_INPUT); }
    public ItemStack tabletOutput() { return items.get(SLOT_TABLET_OUTPUT); }
    public int jobIndex() { return activeJob == null ? 0 : activeJob.completed(); }
    public int jobTotal() { return activeJob == null ? (targetPos == null ? 0 : 1) : activeJob.total(); }
    public float jobProgress() { return activeJob == null ? (status == ConstructorStatus.COMPLETE ? 1f : 0f) : activeJob.progress(); }

    public boolean canRemoveCard() {
        return !shotReserved && !running && (activeJob == null || status == ConstructorStatus.IDLE
                || status == ConstructorStatus.COMPLETE || status == ConstructorStatus.ERROR || status == ConstructorStatus.BLOCKED);
    }

    public int currentEnergyCost() {
        if (targetPos == null) return 0;
        return energyCost(targetPos, targetIsEntity, targetState != null && targetState.hasBlockEntity());
    }

    /** Internal single-block development hook retained for compatibility, never used by normal card flow. */
    public boolean queuePlacement(BlockPos target, BlockState state) {
        if (target == null || state == null || target.equals(worldPosition)) return false;
        if (running || shotReserved) return false;
        activeJob = null;
        pendingJobData = null;
        prepareBlockTarget(target, ConstructorPlacementHelper.sanitizeState(state));
        return true;
    }

    public boolean startPlan(ConstructionPlan plan, SchematicTransform transform, BlockSubstitutionRules substitutions) {
        if (plan == null || transform == null || substitutions == null) return false;
        if (running || shotReserved) return false;
        activeJob = new ConstructionJob(plan, transform, substitutions);
        pendingJobData = null;
        if (!activeJob.hasCurrentTarget()) {
            finishJob(0);
            return true;
        }
        loadCurrentFromJob(false);
        return status != ConstructorStatus.ERROR && status != ConstructorStatus.BLOCKED;
    }

    public boolean startPlan(ConstructionPlan plan, BlockPos origin, BlockSubstitutionRules substitutions) {
        if (plan == null || origin == null) return false;
        return startPlan(plan, new SchematicTransform(origin, 0, 0, plan.sizeX(), plan.sizeY(), plan.sizeZ()), substitutions);
    }

    public boolean startCardPlan() {
        if (!(level instanceof ServerLevel)) return false;
        ItemStack card = schematicCard();
        if (!(card.getItem() instanceof SchematicCardItem) || !SchematicCardItem.hasSource(card)
                || !SchematicCardItem.hasBounds(card) || !SchematicCardItem.deployed(card)) {
            running = false;
            status = ConstructorStatus.BLOCKED;
            setChangedAndSync();
            return false;
        }
        if (running || shotReserved) return false;

        try {
            boolean includeAir = replaceMode == ConstructorReplaceMode.REPLACE_EMPTY;
            ConstructionPlan plan = UniversalSchematicLoader.loadCard(card, includeAir);
            if (plan.totalTargets() <= 0 || plan.sizeX() != SchematicCardItem.sizeX(card)
                    || plan.sizeY() != SchematicCardItem.sizeY(card) || plan.sizeZ() != SchematicCardItem.sizeZ(card)) {
                failJob(ConstructorStatus.ERROR);
                return false;
            }
            BlockPos anchor = SchematicCardItem.anchor(card);
            SchematicTransform transform = new SchematicTransform(anchor, SchematicCardItem.rotation(card), SchematicCardItem.mirror(card),
                    plan.sizeX(), plan.sizeY(), plan.sizeZ());
            if (!transformWithinRange(transform)) {
                failJob(ConstructorStatus.BLOCKED);
                return false;
            }
            BlockSubstitutionRules rules = new BlockSubstitutionRules();
            SchematicCardItem.applyReplacements(card, rules);
            return startPlan(plan, transform, rules);
        } catch (IOException | RuntimeException ignored) {
            failJob(ConstructorStatus.ERROR);
            return false;
        }
    }

    private boolean transformWithinRange(SchematicTransform transform) {
        int maxX = Math.max(0, transform.transformedSizeX() - 1);
        int maxZ = Math.max(0, transform.transformedSizeZ() - 1);
        int maxY = Math.max(0, transform.sizeY() - 1);
        return withinRange(transform.anchor())
                && withinRange(transform.anchor().offset(maxX, 0, 0))
                && withinRange(transform.anchor().offset(0, 0, maxZ))
                && withinRange(transform.anchor().offset(maxX, maxY, maxZ));
    }

    private boolean withinRange(BlockPos pos) {
        return worldPosition.distSqr(pos) <= (double) MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE;
    }

    private void prepareBlockTarget(BlockPos target, BlockState state) {
        targetPos = target.immutable();
        targetState = ConstructorPlacementHelper.sanitizeState(state);
        targetIsEntity = false;
        preparedEntity = null;
        entityVisualStack = ItemStack.EMPTY;
        resetTargetPhase();
    }

    private void prepareEntityTarget(ServerLevel server, boolean preserveReservedFlight) {
        if (activeJob == null || !activeJob.hasCurrentEntity()) {
            finishJob(0);
            return;
        }
        ConstructorEntitySupport.Prepared prepared = ConstructorEntitySupport.prepare(server, activeJob.currentEntityEntry(), activeJob.transform());
        if (prepared == null) {
            if (skipMissing) {
                activeJob.advanceEntity();
                loadCurrentFromJob(false);
            } else failJob(ConstructorStatus.BLOCKED);
            return;
        }
        BlockPos nextPos = BlockPos.containing(prepared.target());
        if (preserveReservedFlight && shotReserved && (targetPos == null || !targetPos.equals(nextPos) || !targetIsEntity)) {
            failJob(ConstructorStatus.ERROR);
            return;
        }
        targetPos = nextPos;
        targetState = null;
        targetIsEntity = true;
        preparedEntity = prepared;
        entityVisualStack = prepared.projectileStack();
        if (!preserveReservedFlight || !shotReserved) resetTargetPhase();
        else running = status != ConstructorStatus.PAUSED && status != ConstructorStatus.BLOCKED && status != ConstructorStatus.ERROR;
    }

    private void resetTargetPhase() {
        phaseTick = 0;
        shotProgress = 0;
        flightTicks = targetPos == null ? MIN_FLIGHT_TICKS : ticksForDistance(targetPos);
        shotReserved = false;
        reservedPlacementStack = ItemStack.EMPTY;
        missingItem = ItemStack.EMPTY;
        running = true;
        status = ConstructorStatus.READY;
        setChangedAndSync();
    }

    private void loadCurrentFromJob(boolean preserveReservedFlight) {
        if (activeJob == null || !activeJob.hasCurrentTarget()) {
            finishJob(0);
            return;
        }
        if (activeJob.hasCurrentEntity()) {
            if (level instanceof ServerLevel server) prepareEntityTarget(server, preserveReservedFlight);
            else failJob(ConstructorStatus.ERROR);
            return;
        }

        BlockPos nextPos = activeJob.currentWorldPos();
        BlockState nextState = ConstructorPlacementHelper.sanitizeState(activeJob.currentTargetState());
        if (preserveReservedFlight && shotReserved) {
            if (targetPos == null || !targetPos.equals(nextPos) || targetState == null || !targetState.equals(nextState) || targetIsEntity) {
                failJob(ConstructorStatus.ERROR);
                return;
            }
            running = status != ConstructorStatus.PAUSED && status != ConstructorStatus.BLOCKED && status != ConstructorStatus.ERROR;
        } else prepareBlockTarget(nextPos, nextState);
    }

    public void pause() {
        if (targetPos == null || status == ConstructorStatus.COMPLETE || status == ConstructorStatus.ERROR) return;
        if (shotReserved || status == ConstructorStatus.FIRING) {
            pauseAfterShot = true;
            setChangedAndSync();
            return;
        }
        running = false;
        status = ConstructorStatus.PAUSED;
        setChangedAndSync();
    }

    public void resume() {
        pauseAfterShot = false;
        if (targetPos != null && status != ConstructorStatus.COMPLETE && status != ConstructorStatus.ERROR) {
            running = true;
            if (!shotReserved) status = ConstructorStatus.READY;
            setChangedAndSync();
        }
    }

    public boolean clearJob() {
        if (shotReserved || status == ConstructorStatus.FIRING) return false;
        activeJob = null;
        pendingJobData = null;
        targetPos = null;
        targetState = null;
        targetIsEntity = false;
        preparedEntity = null;
        phaseTick = 0;
        shotProgress = 0;
        flightTicks = MIN_FLIGHT_TICKS;
        shotCooldown = 0;
        pauseAfterShot = false;
        reservedPlacementStack = ItemStack.EMPTY;
        entityVisualStack = ItemStack.EMPTY;
        missingItem = ItemStack.EMPTY;
        running = false;
        status = ConstructorStatus.IDLE;
        setChangedAndSync();
        return true;
    }

    public boolean handleMenuButton(int id) {
        return switch (id) {
            case 0 -> { if (running && !pauseAfterShot) pause(); else resume(); yield true; }
            case 1 -> clearJob();
            case 2 -> startCardPlan();
            case 3 -> { replaceMode = replaceMode.next(); setChangedAndSync(); yield true; }
            case 4 -> { skipMissing = !skipMissing; setChangedAndSync(); yield true; }
            case 5 -> { replaceBlockEntities = !replaceBlockEntities; setChangedAndSync(); yield true; }
            case 6 -> writeMaterialTablet();
            case 7 -> {
                if (running || shotReserved || !(schematicCard().getItem() instanceof SchematicCardItem)) yield false;
                SchematicCardItem.clearReplacements(schematicCard());
                refreshMaterialTablet();
                setChangedAndSync();
                yield true;
            }
            case 13 -> { speed = speed.next(); setChangedAndSync(); yield true; }
            default -> false;
        };
    }

    private boolean writeMaterialTablet() {
        if (!(tabletInput().getItem() instanceof MaterialListTabletItem)
                || !tabletOutput().isEmpty()
                || !SchematicCardItem.hasSource(schematicCard())) return false;
        try {
            ConstructionPlan plan = UniversalSchematicLoader.loadCard(schematicCard(), false);
            java.util.Map<String, Integer> counts = materialCounts(plan);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("QTSchematicName", SchematicCardItem.sourceName(schematicCard()));
            tag.putInt("QTMaterialKinds", counts.size());
            tag.putInt("QTMaterialTotal", plan.blockCount());
            tag.putString("QTMaterials", encodeMaterials(counts));
            tag.putString("QTConstructorDimension", level.dimension().identifier().toString());
            tag.putLong("QTConstructorPos", worldPosition.asLong());
            ItemStack written = tabletInput().copyWithCount(1);
            written.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            items.set(SLOT_TABLET_INPUT, ItemStack.EMPTY);
            items.set(SLOT_TABLET_OUTPUT, written);
            setChangedAndSync();
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    void refreshMaterialTablet() {
        ItemStack tablet = !tabletOutput().isEmpty() ? tabletOutput() : tabletInput();
        refreshMaterialTablet(tablet);
    }

    public void refreshMaterialTablet(ItemStack tablet) {
        if (!(tablet.getItem() instanceof MaterialListTabletItem) || !MaterialListTabletItem.isWritten(tablet)
                || !SchematicCardItem.hasSource(schematicCard())) return;
        try {
            ConstructionPlan plan = UniversalSchematicLoader.loadCard(schematicCard(), false);
            java.util.Map<String, Integer> counts = materialCounts(plan);
            net.minecraft.nbt.CompoundTag tag = tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            tag.putInt("QTMaterialKinds", counts.size());
            tag.putInt("QTMaterialTotal", plan.blockCount());
            tag.putString("QTMaterials", encodeMaterials(counts));
            tag.putString("QTConstructorDimension", level.dimension().identifier().toString());
            tag.putLong("QTConstructorPos", worldPosition.asLong());
            tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Refreshes only owned quantities; the schematic card no longer needs to remain inserted. */
    public boolean refreshTabletAvailability(ItemStack tablet) {
        if (!(tablet.getItem() instanceof MaterialListTabletItem) || !MaterialListTabletItem.isWritten(tablet)
                || !(level instanceof ServerLevel server)) return false;
        var data = tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag();
        String current = tag.getString("QTMaterials").orElse("");
        StringBuilder refreshed = new StringBuilder(current.length() + 32);
        try {
            for (String token : current.split(";")) {
                if (token.isBlank()) continue;
                String[] fields = token.split("=");
                if (fields.length < 2) continue;
                var id = net.minecraft.resources.Identifier.parse(fields[0]);
                int required = Integer.parseInt(fields[1]);
                Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
                int available = block == null ? 0
                        : ConstructorMaterialAccess.countAvailable(server, worldPosition, block.asItem());
                refreshed.append(fields[0]).append('=').append(required).append('=').append(available).append(';');
            }
            tag.putString("QTMaterials", refreshed.toString());
            tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Makes a linked tablet a live monitor of this Constructor's current schematic. */
    public String refreshTabletFromCurrentSchematic(ItemStack tablet) {
        if (!(tablet.getItem() instanceof MaterialListTabletItem) || !(level instanceof ServerLevel)) return "READ ERROR";
        var existing = tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        CompoundTag tag = existing == null ? new CompoundTag() : existing.copyTag();
        tag.putString("QTConstructorDimension", level.dimension().identifier().toString());
        tag.putLong("QTConstructorPos", worldPosition.asLong());
        if (!SchematicCardItem.hasSource(schematicCard())) {
            tag.putString("QTSchematicName", "NO SCHEMATIC");
            tag.putInt("QTMaterialKinds", 0);
            tag.putInt("QTMaterialTotal", 0);
            tag.putString("QTMaterials", "");
            tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            return "NO SCHEMATIC";
        }
        try {
            ConstructionPlan plan = UniversalSchematicLoader.loadCard(schematicCard(), false);
            java.util.Map<String, Integer> counts = materialCounts(plan);
            tag.putString("QTSchematicName", SchematicCardItem.sourceName(schematicCard()));
            tag.putInt("QTMaterialKinds", counts.size());
            tag.putInt("QTMaterialTotal", plan.blockCount());
            tag.putString("QTMaterials", encodeMaterials(counts));
            tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            return status == ConstructorStatus.COMPLETE ? "COMPLETE"
                    : status == ConstructorStatus.PAUSED ? "PAUSED" : "LIVE";
        } catch (IOException | RuntimeException ignored) {
            return "READ ERROR";
        }
    }

    private java.util.Map<String, Integer> materialCounts(ConstructionPlan plan) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (var entry : plan.entries()) {
            Block source = entry.sourceState().getBlock();
            Block replacement = SchematicCardItem.replacementFor(schematicCard(), source);
            Block effective = replacement == null ? source : replacement;
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(effective).toString();
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }

    private String encodeMaterials(java.util.Map<String, Integer> counts) {
        StringBuilder encoded = new StringBuilder();
        for (var entry : counts.entrySet()) {
            int available = 0;
            try {
                var id = net.minecraft.resources.Identifier.parse(entry.getKey());
                Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
                if (level instanceof ServerLevel server && block != null) {
                    available = ConstructorMaterialAccess.countAvailable(server, worldPosition, block.asItem());
                }
            } catch (RuntimeException ignored) {
            }
            encoded.append(entry.getKey()).append('=').append(entry.getValue())
                    .append('=').append(available).append(';');
        }
        return encoded.toString();
    }

    public static void tick(Level level, BlockPos pos, BlockState blockState, ConstructorBlockEntity be) {
        if (level.isClientSide() || !(level instanceof ServerLevel server)) return;
        be.serverTick(server);
    }

    private void serverTick(ServerLevel level) {
        tickMaterialTabletWriter();
        if (activeJob == null && pendingJobData != null) restorePendingJob();
        if (!running || targetPos == null || (!targetIsEntity && targetState == null)) return;
        if (shotCooldown > 0 && !shotReserved) { shotCooldown--; return; }

        if (!level.hasChunkAt(targetPos)) { transition(ConstructorStatus.WAITING_CHUNK); return; }
        if (!level.getWorldBorder().isWithinBounds(targetPos) || !withinRange(targetPos)) {
            failJob(ConstructorStatus.BLOCKED);
            return;
        }

        if (shotReserved && status == ConstructorStatus.FIRING) {
            shotProgress++;
            if (++phaseTick >= flightTicks()) completeShot(level); else syncClientState();
            return;
        }

        if (!prepareShotIfPossible(level)) return;

        switch (status) {
            case READY, WAITING_ENERGY, WAITING_MATERIAL, WAITING_CHUNK -> {
                phaseTick = 0;
                transition(ConstructorStatus.AIMING);
            }
            case AIMING -> {
                if (++phaseTick >= scaledTicks(AIM_TICKS)) {
                    phaseTick = 0;
                    transition(ConstructorStatus.CHARGING);
                }
            }
            case CHARGING -> {
                if (++phaseTick >= scaledTicks(CHARGE_TICKS)) {
                    if (!reserveShot(level)) return;
                    phaseTick = 0;
                    shotProgress = 0;
                    transition(ConstructorStatus.FIRING);
                }
            }
            default -> { }
        }
    }

    private void tickMaterialTabletWriter() {
        boolean ready = tabletInput().getItem() instanceof MaterialListTabletItem
                && tabletOutput().isEmpty()
                && SchematicCardItem.hasSource(schematicCard());
        if (!ready) {
            if (tabletScanProgress != 0) {
                tabletScanProgress = 0;
                setChangedAndSync();
            }
            return;
        }
        tabletScanProgress++;
        if (tabletScanProgress >= TABLET_SCAN_TICKS) {
            tabletScanProgress = 0;
            writeMaterialTablet();
        } else if ((tabletScanProgress & 3) == 0) {
            syncClientState();
        }
    }

    private void restorePendingJob() {
        CompoundTag snapshot = pendingJobData;
        pendingJobData = null;
        ItemStack card = schematicCard();
        if (!(card.getItem() instanceof SchematicCardItem) || !SchematicCardItem.hasSource(card) || !SchematicCardItem.deployed(card)) {
            failJob(ConstructorStatus.ERROR);
            return;
        }
        try {
            boolean includeAir = replaceMode == ConstructorReplaceMode.REPLACE_EMPTY;
            ConstructionPlan plan = UniversalSchematicLoader.loadCard(card, includeAir);
            BlockSubstitutionRules rules = new BlockSubstitutionRules();
            SchematicCardItem.applyReplacements(card, rules);
            SchematicTransform transform = new SchematicTransform(SchematicCardItem.anchor(card), SchematicCardItem.rotation(card),
                    SchematicCardItem.mirror(card), plan.sizeX(), plan.sizeY(), plan.sizeZ());
            activeJob = ConstructionJob.restore(plan, transform, rules, snapshot);
            if (!activeJob.hasCurrentTarget()) {
                finishJob(shotProgress);
                return;
            }
            loadCurrentFromJob(true);
        } catch (IOException | RuntimeException ignored) {
            failJob(ConstructorStatus.ERROR);
        }
    }

    private boolean prepareShotIfPossible(ServerLevel level) {
        ConstructorRequirement requirement;
        if (targetIsEntity) {
            if (preparedEntity == null && activeJob != null && activeJob.hasCurrentEntity()) {
                preparedEntity = ConstructorEntitySupport.prepare(level, activeJob.currentEntityEntry(), activeJob.transform());
            }
            if (preparedEntity == null) {
                if (skipMissing) skipCurrentAndAdvance(); else failJob(ConstructorStatus.BLOCKED);
                return false;
            }
            requirement = preparedEntity.requirement();
        } else {
            if (ConstructorPlacementHelper.shouldIgnore(targetState)) { skipCurrentAndAdvance(); return false; }

            BlockState current = level.getBlockState(targetPos);
            boolean sameState = current.equals(targetState);
            if (sameState && (!targetState.hasBlockEntity() || !replaceBlockEntities)) { skipCurrentAndAdvance(); return false; }
            if (!replaceBlockEntities && current.hasBlockEntity()) { skipCurrentAndAdvance(); return false; }
            if (!current.isAir() && current.getDestroySpeed(level, targetPos) < 0) { skipCurrentAndAdvance(); return false; }
            if (!shouldReplace(level, current, targetState) && !sameState) { skipCurrentAndAdvance(); return false; }

            if (!targetState.isAir() && !targetState.canSurvive(level, targetPos)) {
                deferForSupport();
                return false;
            }
            requirement = currentBlockRequirement();
        }

        if (requirement.isInvalid()) {
            missingItem = ItemStack.EMPTY;
            if (skipMissing) skipCurrentAndAdvance(); else failJob(ConstructorStatus.BLOCKED);
            return false;
        }

        int cost = currentEnergyCost();
        if (energy.getEnergyStored() < cost) { transition(ConstructorStatus.WAITING_ENERGY); return false; }

        ConstructorMaterialAccess.Result material = ConstructorMaterialAccess.simulate(level, worldPosition, requirement);
        if (!material.success()) {
            missingItem = material.missingStack();
            if (skipMissing) skipCurrentAndAdvance(); else transition(ConstructorStatus.WAITING_MATERIAL);
            return false;
        }
        missingItem = ItemStack.EMPTY;
        return true;
    }

    private ConstructorRequirement currentBlockRequirement() {
        CompoundTag data = activeJob == null ? null : activeJob.currentBlockEntityData();
        return ConstructorRequirementRegistry.resolve(targetState, data);
    }

    private ConstructorRequirement currentRequirement() {
        if (targetIsEntity) return preparedEntity == null ? ConstructorRequirement.INVALID : preparedEntity.requirement();
        return currentBlockRequirement();
    }

    private boolean shouldReplace(ServerLevel level, BlockState current, BlockState desired) {
        if (desired.isAir()) return replaceMode == ConstructorReplaceMode.REPLACE_EMPTY && !current.isAir();
        if (current.isAir()) return true;
        return switch (replaceMode) {
            case DONT_REPLACE -> false;
            case REPLACE_SOLID -> desired.isRedstoneConductor(level, targetPos)
                    || !current.isRedstoneConductor(level, targetPos);
            case REPLACE_ANY, REPLACE_EMPTY -> true;
        };
    }

    private void deferForSupport() {
        if (activeJob != null && activeJob.deferCurrent()) {
            loadCurrentFromJob(false);
            return;
        }
        running = false;
        status = ConstructorStatus.BLOCKED;
        phaseTick = 0;
        setChangedAndSync();
    }

    private boolean reserveShot(ServerLevel level) {
        int cost = currentEnergyCost();
        if (energy.getEnergyStored() < cost) { transition(ConstructorStatus.WAITING_ENERGY); return false; }

        ConstructorMaterialAccess.Result material = ConstructorMaterialAccess.consume(level, worldPosition, currentRequirement());
        if (!material.success()) {
            missingItem = material.missingStack();
            if (skipMissing) skipCurrentAndAdvance(); else transition(ConstructorStatus.WAITING_MATERIAL);
            return false;
        }
        reservedPlacementStack = material.placementStack();
        missingItem = ItemStack.EMPTY;
        shotReserved = true;
        setChangedAndSync();
        return true;
    }

    private void completeShot(ServerLevel level) {
        if (targetIsEntity) {
            if (preparedEntity == null && activeJob != null && activeJob.hasCurrentEntity())
                preparedEntity = ConstructorEntitySupport.prepare(level, activeJob.currentEntityEntry(), activeJob.transform());
            boolean spawned = ConstructorEntitySupport.spawn(level, preparedEntity);
            shotReserved = false;
            reservedPlacementStack = ItemStack.EMPTY;
            if (!spawned) { failJob(ConstructorStatus.ERROR); return; }
            consumeCompletedPlacementEnergy();
            finishCurrentAndAdvance(flightTicks());
            return;
        }

        BlockState current = level.getBlockState(targetPos);
        if ((!replaceBlockEntities && current.hasBlockEntity()) || (!current.isAir() && current.getDestroySpeed(level, targetPos) < 0)) {
            blockShotAtImpact();
            return;
        }

        CompoundTag data = activeJob == null ? null : activeJob.currentBlockEntityData();
        boolean placed = ConstructorPlacementHelper.place(level, targetPos, targetState, reservedPlacementStack, data);
        shotReserved = false;
        reservedPlacementStack = ItemStack.EMPTY;
        if (!placed) { failJob(ConstructorStatus.ERROR); return; }
        consumeCompletedPlacementEnergy();
        finishCurrentAndAdvance(flightTicks());
    }

    private void blockShotAtImpact() {
        shotReserved = false;
        reservedPlacementStack = ItemStack.EMPTY;
        running = false;
        status = ConstructorStatus.BLOCKED;
        phaseTick = 0;
        shotProgress = flightTicks();
        setChangedAndSync();
    }

    private void skipCurrentAndAdvance() {
        shotReserved = false;
        reservedPlacementStack = ItemStack.EMPTY;
        if (activeJob == null) { finishJob(0); return; }
        boolean more = activeJob.hasCurrentEntity() ? activeJob.advanceEntity() : activeJob.skipCurrent();
        if (more) loadCurrentFromJob(false); else finishJob(0);
    }

    private void finishCurrentAndAdvance(int finishedShotProgress) {
        shotReserved = false;
        reservedPlacementStack = ItemStack.EMPTY;
        shotCooldown = scaledCooldown();
        if (activeJob != null) {
            boolean more = targetIsEntity ? activeJob.advanceEntity() : activeJob.advanceBlock();
            if (more) {
                loadCurrentFromJob(false);
                if (pauseAfterShot) {
                    pauseAfterShot = false;
                    running = false;
                    status = ConstructorStatus.PAUSED;
                    setChangedAndSync();
                }
                return;
            }
        }
        finishJob(finishedShotProgress);
    }

    private void finishJob(int finishedShotProgress) {
        status = ConstructorStatus.COMPLETE;
        running = false;
        pauseAfterShot = false;
        phaseTick = 0;
        shotProgress = finishedShotProgress;
        targetPos = null;
        targetState = null;
        targetIsEntity = false;
        preparedEntity = null;
        entityVisualStack = ItemStack.EMPTY;
        missingItem = ItemStack.EMPTY;
        setChangedAndSync();
    }

    private void failJob(ConstructorStatus failure) {
        running = false;
        pauseAfterShot = false;
        status = failure;
        phaseTick = 0;
        setChangedAndSync();
    }

    private int energyCost(BlockPos target, boolean entity, boolean blockEntity) {
        return energyBlockProgress >= ENERGY_BLOCK_INTERVAL - 1 ? 1 : 0;
    }

    private int ticksForDistance(BlockPos target) {
        double distSqr = target.distSqr(worldPosition);
        int normal = Math.max(MIN_FLIGHT_TICKS, (int) (Math.sqrt(Math.sqrt(distSqr)) * 4.0));
        return Math.max(MIN_SCALED_FLIGHT_TICKS, (normal + speed.multiplier() - 1) / speed.multiplier());
    }

    private int scaledTicks(int normalTicks) {
        return Math.max(1, (normalTicks + speed.multiplier() - 1) / speed.multiplier());
    }

    private int scaledCooldown() {
        return Math.max(0, SHOT_COOLDOWN_TICKS / speed.multiplier());
    }

    private void consumeCompletedPlacementEnergy() {
        energyBlockProgress++;
        if (energyBlockProgress >= ENERGY_BLOCK_INTERVAL) {
            energyBlockProgress = 0;
            energy.consume(1);
        }
    }

    private void transition(ConstructorStatus next) {
        if (status != next) {
            status = next;
            phaseTick = 0;
            setChangedAndSync();
        }
    }

    void setChangedAndSync() { setChanged(); syncClientState(); }
    private void syncClientState() {
        if (level instanceof ServerLevel server) server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override public Component getDisplayName() { return Component.literal("Constructor"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new ConstructorMenu(id, inventory, this, menuData); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider) { return saveWithoutMetadata(provider); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("Energy", energy.getEnergyStored());
        output.putInt("Status", status.ordinal());
        output.putInt("ReplaceMode", replaceMode.ordinal());
        output.putInt("Speed", speed.ordinal());
        output.putInt("EnergyBlockProgress", energyBlockProgress);
        output.putBoolean("SkipMissing", skipMissing);
        output.putBoolean("ReplaceBlockEntities", replaceBlockEntities);
        output.putBoolean("Running", running);
        output.putBoolean("ShotReserved", shotReserved);
        output.putBoolean("PauseAfterShot", pauseAfterShot);
        output.putBoolean("TargetIsEntity", targetIsEntity);
        output.putInt("PhaseTick", phaseTick);
        output.putInt("ShotProgress", shotProgress);
        output.putInt("FlightTicks", flightTicks);
        output.putInt("ShotCooldown", shotCooldown);
        if (targetPos != null) output.putLong("TargetPos", targetPos.asLong());
        if (targetState != null) output.store("TargetState", BlockState.CODEC, targetState);
        if (!schematicCard().isEmpty()) output.store("SchematicCard", ItemStack.CODEC, schematicCard());
        if (!tabletInput().isEmpty()) output.store("TabletInput", ItemStack.CODEC, tabletInput());
        if (!tabletOutput().isEmpty()) output.store("TabletOutput", ItemStack.CODEC, tabletOutput());
        if (!reservedPlacementStack.isEmpty()) output.store("ReservedPlacementStack", ItemStack.CODEC, reservedPlacementStack);
        if (!entityVisualStack.isEmpty()) output.store("EntityVisualStack", ItemStack.CODEC, entityVisualStack);
        if (!missingItem.isEmpty()) output.store("MissingItem", ItemStack.CODEC, missingItem);
        if (activeJob != null) output.store("ConstructionJob", CompoundTag.CODEC, activeJob.save());
        else if (pendingJobData != null) output.store("ConstructionJob", CompoundTag.CODEC, pendingJobData);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy.setStored(input.getIntOr("Energy", 0));
        int s = input.getIntOr("Status", ConstructorStatus.IDLE.ordinal());
        status = ConstructorStatus.values()[Math.max(0, Math.min(ConstructorStatus.values().length - 1, s))];
        int mode = input.getIntOr("ReplaceMode", ConstructorReplaceMode.REPLACE_ANY.ordinal());
        replaceMode = ConstructorReplaceMode.values()[Math.max(0, Math.min(ConstructorReplaceMode.values().length - 1, mode))];
        speed = ConstructorSpeed.byOrdinal(input.getIntOr("Speed", ConstructorSpeed.NORMAL.ordinal()));
        energyBlockProgress = Math.floorMod(input.getIntOr("EnergyBlockProgress", 0), ENERGY_BLOCK_INTERVAL);
        skipMissing = input.getBooleanOr("SkipMissing", false);
        replaceBlockEntities = input.getBooleanOr("ReplaceBlockEntities", false);
        running = input.getBooleanOr("Running", false);
        shotReserved = input.getBooleanOr("ShotReserved", false);
        pauseAfterShot = input.getBooleanOr("PauseAfterShot", false);
        targetIsEntity = input.getBooleanOr("TargetIsEntity", false);
        phaseTick = Math.max(0, input.getIntOr("PhaseTick", 0));
        shotProgress = Math.max(0, input.getIntOr("ShotProgress", 0));
        flightTicks = Math.max(MIN_SCALED_FLIGHT_TICKS, input.getIntOr("FlightTicks", MIN_FLIGHT_TICKS));
        shotCooldown = Math.max(0, input.getIntOr("ShotCooldown", 0));
        long packed = input.getLongOr("TargetPos", Long.MIN_VALUE);
        targetPos = packed == Long.MIN_VALUE ? null : BlockPos.of(packed);
        targetState = input.read("TargetState", BlockState.CODEC).orElse(null);
        items.set(SLOT_SCHEMATIC, input.read("SchematicCard", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        items.set(SLOT_TABLET_INPUT, input.read("TabletInput", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        items.set(SLOT_TABLET_OUTPUT, input.read("TabletOutput", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        reservedPlacementStack = input.read("ReservedPlacementStack", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        entityVisualStack = input.read("EntityVisualStack", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        missingItem = input.read("MissingItem", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        pendingJobData = input.read("ConstructionJob", CompoundTag.CODEC).orElse(null);
        activeJob = null;
        preparedEntity = null;

        if ((running || shotReserved) && pendingJobData == null) {
            running = false;
            shotReserved = false;
            reservedPlacementStack = ItemStack.EMPTY;
            entityVisualStack = ItemStack.EMPTY;
            targetPos = null;
            targetState = null;
            targetIsEntity = false;
            status = ConstructorStatus.ERROR;
            phaseTick = 0;
            shotProgress = 0;
        }
    }

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= items.size() || (slot == SLOT_SCHEMATIC && !canRemoveCard())) return ItemStack.EMPTY;
        ItemStack stack = items.get(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stack.split(amount);
        if (stack.isEmpty()) items.set(slot, ItemStack.EMPTY);
        if (slot == SLOT_SCHEMATIC && items.get(slot).isEmpty()) clearJob(); else setChangedAndSync();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= items.size() || (slot == SLOT_SCHEMATIC && !canRemoveCard())) return ItemStack.EMPTY;
        ItemStack result = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        if (slot == SLOT_SCHEMATIC) {
            activeJob = null;
            pendingJobData = null;
            preparedEntity = null;
        }
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) return;
        if (slot == SLOT_SCHEMATIC && (!canRemoveCard() && !ItemStack.matches(schematicCard(), stack))) return;
        if (slot == SLOT_TABLET_INPUT && !stack.isEmpty()
                && !(stack.getItem() instanceof MaterialListTabletItem)) return;
        if (slot == SLOT_TABLET_OUTPUT && !stack.isEmpty()) return;
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > 1) stack.setCount(1);
        if (slot == SLOT_TABLET_INPUT) tabletScanProgress = 0;
        if (slot == SLOT_SCHEMATIC && stack.isEmpty()) clearJob(); else setChangedAndSync();
    }

    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }

    @Override
    public void clearContent() {
        if (!canRemoveCard()) return;
        for (int slot = 0; slot < items.size(); slot++) items.set(slot, ItemStack.EMPTY);
        tabletScanProgress = 0;
        clearJob();
    }

    public static final class ConstructorEnergyStorage extends SimpleEnergyHandler {
        private ConstructorEnergyStorage(int capacity, int maxReceive) { super(capacity, maxReceive, 0, 0); }
        public int getEnergyStored() { return (int) getAmountAsLong(); }
        public int getMaxEnergyStored() { return (int) getCapacityAsLong(); }
        private void consume(int amount) { this.energy = Math.max(0, this.energy - Math.max(0, amount)); }
        private void setStored(int amount) { this.energy = Math.max(0, Math.min(this.capacity, amount)); }
    }
}
