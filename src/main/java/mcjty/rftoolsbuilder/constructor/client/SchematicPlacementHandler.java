package mcjty.rftoolsbuilder.constructor.client;

import mcjty.rftoolsbuilder.constructor.ConstructorNetworking;
import mcjty.rftoolsbuilder.constructor.ConstructorStateFilterRegistry;
import mcjty.rftoolsbuilder.constructor.SchematicCardItem;
import mcjty.rftoolsbuilder.constructor.SchematicFolderIndex;
import mcjty.rftoolsbuilder.constructor.SchematicTransform;
import mcjty.rftoolsbuilder.constructor.UniversalSchematicLoader;
import mcjty.rftoolsbuilder.constructor.plan.BlockSubstitutionRules;
import mcjty.rftoolsbuilder.constructor.plan.ConstructionPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side schematic deployment editor.
 *
 * The editing model deliberately follows the Create schematic workflow: a
 * schematic has a persistent anchor plus rotation/mirror state, while a tool
 * mode decides how the user manipulates that transform. The card is only
 * synchronized after an explicit confirm. No mandatory key mappings are used.
 */
public final class SchematicPlacementHandler {
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();
    private static final Map<BlockState, BlockModelRenderState> MODEL_CACHE = new HashMap<>();

    private static boolean installed;
    private static String loadedKey = "";
    private static int loadGeneration;
    private static ConstructionPlan plan;
    private static BlockSubstitutionRules substitutions = new BlockSubstitutionRules();
    private static int loadedReplacementSignature;

    private static boolean editing;
    private static boolean followingLook;
    private static BlockPos editAnchor = BlockPos.ZERO;
    private static int editRotation;
    private static int editMirror;
    private static BlockPos resetAnchor = BlockPos.ZERO;
    private static int resetRotation;
    private static int resetMirror;
    private static SchematicPlacementTool tool = SchematicPlacementTool.DEPLOY;

    private SchematicPlacementHandler() {}

    public static void install() {
        if (installed) return;
        installed = true;
        NeoForge.EVENT_BUS.addListener(SchematicPlacementHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener(SchematicPlacementHandler::onMouseButton);
        NeoForge.EVENT_BUS.addListener(SchematicPlacementHandler::onKey);
        NeoForge.EVENT_BUS.addListener(SchematicPlacementHandler::onRenderGui);
        NeoForge.EVENT_BUS.addListener(SchematicPlacementHandler::onSubmitGeometry);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ClientSchematicUploader.tick();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clearTransient();
            return;
        }

        ItemStack card = currentCard(mc);
        if (!isWrittenCard(card)) {
            if (editing) editing = false;
            clearTransient();
            return;
        }

        ensurePlan(card);
        updateUndeployedPreview(card, mc);
    }

    /**
     * Create's Deploy Tool renders before placement and continuously chases the
     * player's target. A click freezes this transient transform onto the card.
     */
    private static void updateUndeployedPreview(ItemStack card, Minecraft mc) {
        if (SchematicCardItem.deployed(card) || plan == null) {
            followingLook = false;
            return;
        }
        editRotation = SchematicCardItem.rotation(card);
        editMirror = SchematicCardItem.mirror(card);
        editAnchor = anchorForTarget(previewTarget(mc));
        followingLook = true;
        tool = SchematicPlacementTool.DEPLOY;
    }

    private static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != 1 || event.getAction() != 1) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        ItemStack card = currentCard(mc);
        if (!isWrittenCard(card)) return;

        ensurePlan(card);

        // Create-style interaction without a dedicated key binding:
        // first use deploys directly into the world and keeps the hologram
        // active. A deployed card only opens its editor deliberately.
        if (!SchematicCardItem.deployed(card)) {
            event.setCanceled(true);
            beginEditing(card, mc);
            if (confirm()) {
                message("Schematic positioned — sneak + right-click to edit");
            }
            return;
        }

        // A deployed card is edited through the compact ALT panel. Right-click
        // remains free for normal world interaction after placement.
    }

    private static void onKey(InputEvent.Key event) {
        if (event.getKey() != GLFW.GLFW_KEY_LEFT_ALT && event.getKey() != GLFW.GLFW_KEY_RIGHT_ALT) return;
        Minecraft mc = Minecraft.getInstance();
        if (event.getAction() == GLFW.GLFW_PRESS) {
            if (mc.player == null || mc.level == null || mc.screen != null) return;
            ItemStack card = currentCard(mc);
            if (!isWrittenCard(card) || !SchematicCardItem.deployed(card)) return;
            ensurePlan(card);
            if (!editing) beginEditing(card, mc);
            mc.setScreen(new SchematicPlacementScreen());
        } else if (event.getAction() == GLFW.GLFW_RELEASE && mc.screen instanceof SchematicPlacementScreen screen) {
            screen.suspendForAltRelease();
        }
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen instanceof SchematicPlacementScreen) return;
        ItemStack card = currentCard(mc);
        if (!isWrittenCard(card) || !SchematicCardItem.deployed(card)) return;
        SchematicPlacementScreen.drawCompactPanel(event.getGuiGraphics(), mc.font,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), false);
    }

    /**
     * Create-style in-world tool manipulation. The selected tool remains active
     * after the editor closes and the wheel edits the deployed hologram without
     * reopening a blocking screen.
     */
    private static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        int amount = event.getAccumulatedScrollY();
        if (amount == 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        ItemStack card = currentCard(mc);
        if (!isWrittenCard(card) || !SchematicCardItem.deployed(card)) return;

        DirectionFace face = selectedBoundsFace(mc, card);
        SchematicPlacementTool activeTool = tool;
        boolean handled = false;
        beginEditing(card, mc);
        tool = activeTool;

        switch (tool) {
            case MOVE_XZ -> {
                if (face != null && face.horizontal()) {
                    nudge(-face.x() * Integer.signum(amount), 0,
                            -face.z() * Integer.signum(amount));
                    handled = true;
                }
            }
            case MOVE_Y -> {
                nudge(0, Integer.signum(amount), 0);
                handled = true;
            }
            case ROTATE -> {
                rotate90(amount > 0);
                handled = true;
            }
            case MIRROR -> {
                if (face != null && face.horizontal()) {
                    boolean localX = face.x() != 0;
                    if ((editRotation & 1) != 0) localX = !localX;
                    if (localX) flipX(); else flipZ();
                    handled = true;
                }
            }
            default -> {
            }
        }

        if (handled) {
            syncDeployment(card, true);
            editing = false;
            event.setCanceled(true);
        } else {
            editing = false;
        }
    }

    private static DirectionFace selectedBoundsFace(Minecraft mc, ItemStack card) {
        if (mc.player == null) return null;
        SchematicTransform transform = SchematicCardItem.transform(card);
        AABB bounds = new AABB(
                transform.anchor().getX(), transform.anchor().getY(), transform.anchor().getZ(),
                transform.anchor().getX() + Math.max(1, transform.transformedSizeX()),
                transform.anchor().getY() + Math.max(1, transform.sizeY()),
                transform.anchor().getZ() + Math.max(1, transform.transformedSizeZ()));
        Vec3 start = mc.player.getEyePosition();
        Vec3 direction = mc.player.getLookAngle();
        return rayFace(bounds, start, direction, 75.0);
    }

    private static DirectionFace rayFace(AABB box, Vec3 start, Vec3 direction, double range) {
        double near = 0.0;
        double far = range;
        DirectionFace hitFace = null;
        double[] origins = {start.x, start.y, start.z};
        double[] directions = {direction.x, direction.y, direction.z};
        double[] mins = {box.minX, box.minY, box.minZ};
        double[] maxs = {box.maxX, box.maxY, box.maxZ};

        for (int axis = 0; axis < 3; axis++) {
            double d = directions[axis];
            if (Math.abs(d) < 1.0e-7) {
                if (origins[axis] < mins[axis] || origins[axis] > maxs[axis]) return null;
                continue;
            }
            double t1 = (mins[axis] - origins[axis]) / d;
            double t2 = (maxs[axis] - origins[axis]) / d;
            DirectionFace entering = DirectionFace.forAxis(axis, d > 0 ? -1 : 1);
            if (t1 > t2) {
                double swap = t1; t1 = t2; t2 = swap;
                entering = DirectionFace.forAxis(axis, d > 0 ? 1 : -1);
            }
            if (t1 > near) {
                near = t1;
                hitFace = entering;
            }
            far = Math.min(far, t2);
            if (near > far) return null;
        }
        return near <= range ? hitFace : null;
    }

    private record DirectionFace(int x, int y, int z) {
        boolean horizontal() { return y == 0; }
        static DirectionFace forAxis(int axis, int sign) {
            return switch (axis) {
                case 0 -> new DirectionFace(sign, 0, 0);
                case 1 -> new DirectionFace(0, sign, 0);
                default -> new DirectionFace(0, 0, sign);
            };
        }
    }

    private static void beginEditing(ItemStack card, Minecraft mc) {
        editRotation = SchematicCardItem.rotation(card);
        editMirror = SchematicCardItem.mirror(card);

        if (SchematicCardItem.deployed(card)) {
            editAnchor = SchematicCardItem.anchor(card);
            tool = SchematicPlacementTool.MOVE_XZ;
        } else {
            editAnchor = anchorForTarget(previewTarget(mc));
            tool = SchematicPlacementTool.DEPLOY;
        }

        resetAnchor = editAnchor;
        resetRotation = editRotation;
        resetMirror = editMirror;
        editing = true;
        followingLook = false;
        MODEL_CACHE.clear();
    }

    public static boolean isEditing() { return editing; }
    public static SchematicPlacementTool tool() { return tool; }
    public static void setTool(SchematicPlacementTool value) {
        if (!editing || value == null) return;
        tool = value;
    }

    public static boolean hasValidCard() {
        return isWrittenCard(currentCard(Minecraft.getInstance()));
    }

    public static boolean hasSavedDeployment() {
        ItemStack card = currentCard(Minecraft.getInstance());
        return isWrittenCard(card) && SchematicCardItem.deployed(card);
    }

    public static BlockPos anchor() { return editAnchor; }
    public static int rotationQuarter() { return editRotation; }
    public static int rotationDegrees() { return Math.floorMod(editRotation, 4) * 90; }
    public static int mirror() { return editMirror; }
    public static String mirrorName() { return mirrorName(editMirror); }

    public static int sizeX() {
        if (plan != null) return plan.sizeX();
        ItemStack card = currentCard(Minecraft.getInstance());
        return isWrittenCard(card) ? SchematicCardItem.sizeX(card) : 0;
    }

    public static int sizeY() {
        if (plan != null) return plan.sizeY();
        ItemStack card = currentCard(Minecraft.getInstance());
        return isWrittenCard(card) ? SchematicCardItem.sizeY(card) : 0;
    }

    public static int sizeZ() {
        if (plan != null) return plan.sizeZ();
        ItemStack card = currentCard(Minecraft.getInstance());
        return isWrittenCard(card) ? SchematicCardItem.sizeZ(card) : 0;
    }

    public static int transformedSizeX() {
        return (Math.floorMod(editRotation, 4) & 1) == 0 ? Math.max(1, sizeX()) : Math.max(1, sizeZ());
    }

    public static int transformedSizeZ() {
        return (Math.floorMod(editRotation, 4) & 1) == 0 ? Math.max(1, sizeZ()) : Math.max(1, sizeX());
    }

    public static boolean previewReady() {
        return plan != null && plan.totalTargets() > 0;
    }

    public static int previewBlockCount() {
        return plan == null ? 0 : plan.blockCount();
    }

    public static int previewEntityCount() {
        return plan == null ? 0 : plan.entityCount();
    }

    public static void setAnchor(BlockPos anchor) {
        if (!editing || anchor == null) return;
        editAnchor = anchor.immutable();
    }

    public static void setAnchorX(int x) {
        if (!editing) return;
        editAnchor = new BlockPos(x, editAnchor.getY(), editAnchor.getZ());
    }

    public static void setAnchorY(int y) {
        if (!editing) return;
        editAnchor = new BlockPos(editAnchor.getX(), y, editAnchor.getZ());
    }

    public static void setAnchorZ(int z) {
        if (!editing) return;
        editAnchor = new BlockPos(editAnchor.getX(), editAnchor.getY(), z);
    }

    public static void nudge(int dx, int dy, int dz) {
        if (!editing) return;
        editAnchor = editAnchor.offset(dx, dy, dz);
    }

    /** Moves one block relative to the player's current horizontal view. */
    public static void nudgeView(int forward, int right) {
        if (!editing) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var facing = mc.player.getDirection();
        int dx = facing.getStepX() * forward - facing.getStepZ() * right;
        int dz = facing.getStepZ() * forward + facing.getStepX() * right;
        nudge(dx, 0, dz);
    }

    /** Move in schematic-local X/Z space, honoring mirror and rotation. */
    public static void nudgeLocal(int localX, int localZ) {
        if (!editing) return;
        int x = localX;
        int z = localZ;
        // Match vanilla/Create mirror semantics used by SchematicTransform:
        // LEFT_RIGHT mirrors local Z, FRONT_BACK mirrors local X.
        if (editMirror == 1) z = -z;
        if (editMirror == 2) x = -x;

        int q = Math.floorMod(editRotation, 4);
        int wx;
        int wz;
        switch (q) {
            case 1 -> { wx = -z; wz = x; }
            case 2 -> { wx = -x; wz = -z; }
            case 3 -> { wx = z; wz = -x; }
            default -> { wx = x; wz = z; }
        }
        nudge(wx, 0, wz);
    }

    public static void setRotation(int quarterTurns) {
        if (!editing) return;
        int target = Math.floorMod(quarterTurns, 4);
        int delta = target - editRotation;
        SchematicTransform rotated = new SchematicTransform(
                editAnchor, editRotation, editMirror, sizeX(), sizeY(), sizeZ())
                .rotateKeepingCenter(delta);
        editAnchor = rotated.anchor();
        editRotation = rotated.rotationQuarterTurns();
        MODEL_CACHE.clear();
    }

    /** Used by the exact-coordinate editor where the entered anchor is authoritative. */
    public static void setRotationAtAnchor(int quarterTurns) {
        if (!editing) return;
        editRotation = Math.floorMod(quarterTurns, 4);
        MODEL_CACHE.clear();
    }

    public static void rotate90(boolean clockwise) {
        if (!editing) return;
        setRotation(editRotation + (clockwise ? 1 : -1));
    }

    public static void setMirror(int mirror) {
        if (!editing) return;
        editMirror = Math.max(0, Math.min(2, mirror));
        MODEL_CACHE.clear();
    }

    public static void flipX() {
        if (!editing) return;
        setMirror(editMirror == 2 ? 0 : 2);
    }

    public static void flipZ() {
        if (!editing) return;
        setMirror(editMirror == 1 ? 0 : 1);
    }

    public static void placeAtLook() {
        if (!editing) return;
        Minecraft mc = Minecraft.getInstance();
        BlockPos target = lookTarget(mc);
        if (target != null) editAnchor = anchorForTarget(target);
    }

    public static void placeInFront() {
        if (!editing) return;
        editAnchor = centeredInFront(Minecraft.getInstance(), 4);
    }

    public static void centerOnPlayer() {
        if (!editing) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int sx = transformedSizeX();
        int sz = transformedSizeZ();
        BlockPos base = mc.player.blockPosition();
        editAnchor = base.offset(-sx / 2, 0, -sz / 2);
    }

    public static void reset() {
        if (!editing) return;
        editAnchor = resetAnchor;
        editRotation = resetRotation;
        editMirror = resetMirror;
        tool = hasSavedDeployment() ? SchematicPlacementTool.MOVE_XZ : SchematicPlacementTool.DEPLOY;
        MODEL_CACHE.clear();
    }

    public static boolean confirm() {
        if (!editing) return false;
        if (!previewReady()) {
            message("Schematic preview is still loading");
            return false;
        }

        ItemStack card = currentCard(Minecraft.getInstance());
        if (!isWrittenCard(card)) return false;

        syncDeployment(card, true);
        editing = false;
        followingLook = false;
        MODEL_CACHE.clear();
        return true;
    }

    public static boolean clearDeployment() {
        ItemStack card = currentCard(Minecraft.getInstance());
        if (!isWrittenCard(card)) return false;

        syncDeployment(card, false);
        editing = false;
        followingLook = true;
        MODEL_CACHE.clear();
        return true;
    }

    public static void cancel() {
        editing = false;
        followingLook = false;
        MODEL_CACHE.clear();
    }

    private static BlockPos centeredInFront(Minecraft mc, int distance) {
        if (mc.player == null) return BlockPos.ZERO;
        BlockPos center = mc.player.blockPosition().relative(mc.player.getDirection(), distance);
        return anchorForTarget(center);
    }

    /**
     * Converts Create's deploy target (the horizontal center selected in the
     * world) into the persisted minimum-corner anchor used by the printer.
     */
    private static BlockPos anchorForTarget(BlockPos target) {
        return target.offset(-transformedSizeX() / 2, 0, -transformedSizeZ() / 2);
    }

    private static BlockPos lookTarget(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit)) return null;
        return hit.getBlockPos().relative(hit.getDirection());
    }

    private static BlockPos previewTarget(Minecraft mc) {
        BlockPos hit = lookTarget(mc);
        if (hit != null) return hit;
        if (mc.player == null) return BlockPos.ZERO;
        int range = Math.max(3, Math.min(24,
                (int) Math.ceil(Math.sqrt(
                        transformedSizeX() * transformedSizeX()
                                + transformedSizeZ() * transformedSizeZ()) / 2.0)));
        Vec3 target = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(range));
        return BlockPos.containing(target);
    }

    private static void syncDeployment(ItemStack card, boolean deployed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        BlockPos anchor = deployed ? editAnchor : BlockPos.ZERO;
        int rotation = deployed ? editRotation : 0;
        int mirror = deployed ? editMirror : 0;

        SchematicCardItem.setDeployment(card, anchor, rotation, mirror, deployed);
        ClientPacketDistributor.sendToServer(new ConstructorNetworking.SyncDeployment(
                mc.player.getInventory().getSelectedSlot(), anchor, rotation, mirror, deployed, SchematicCardItem.sha256(card)));
    }

    private static void ensurePlan(ItemStack card) {
        String key = SchematicCardItem.clientFile(card) + "|" + SchematicCardItem.sourceType(card) + "|" + SchematicCardItem.sha256(card);
        int replacementSignature = SchematicCardItem.replacementSignature(card);
        if (key.equals(loadedKey) && replacementSignature == loadedReplacementSignature) return;

        loadedKey = key;
        loadedReplacementSignature = replacementSignature;
        plan = null;
        MODEL_CACHE.clear();
        substitutions = new BlockSubstitutionRules();
        SchematicCardItem.applyReplacements(card, substitutions);
        int generation = ++loadGeneration;

        SchematicFolderIndex.Format format = SchematicFolderIndex.Format.fromId(SchematicCardItem.sourceType(card));
        if (format == null) format = SchematicFolderIndex.Format.fromFileName(SchematicCardItem.clientFile(card));
        if (format == null) {
            message("Unsupported schematic format on card");
            return;
        }

        SchematicFolderIndex.Entry entry = new SchematicFolderIndex.Entry(SchematicCardItem.clientFile(card), format);
        CompletableFuture.supplyAsync(() -> {
            try {
                return UniversalSchematicLoader.load(entry, false);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete((loaded, error) -> Minecraft.getInstance().execute(() -> {
            if (generation != loadGeneration) return;
            if (error != null || loaded == null || loaded.totalTargets() == 0) {
                plan = null;
                message("Could not preview schematic: " + safeMessage(error));
                return;
            }
            plan = loaded;
        }));
    }

    private static void onSubmitGeometry(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || plan == null) return;

        ItemStack card = currentCard(mc);
        if (!isWrittenCard(card)) return;

        SchematicTransform transform = (editing || followingLook)
                ? new SchematicTransform(editAnchor, editRotation, editMirror, plan.sizeX(), plan.sizeY(), plan.sizeZ())
                : SchematicCardItem.transform(card);

        if (!editing && !followingLook && !SchematicCardItem.deployed(card)) return;

        var pose = event.getPoseStack();
        var collector = event.getSubmitNodeCollector();
        var camera = event.getLevelRenderState().cameraRenderState.pos;

        for (var entry : plan.entries()) {
            BlockState source = substitutions.apply(entry.sourceState());
            BlockState state = ConstructorStateFilterRegistry.sanitize(transform.transformState(source));
            if (state.isAir() || state.getBlock() == Blocks.STRUCTURE_VOID) continue;

            BlockPos worldPos = transform.transformWorld(entry.relativePos());
            BlockModelRenderState renderState = MODEL_CACHE.computeIfAbsent(state, s -> {
                BlockModelRenderState created = new BlockModelRenderState();
                mc.getBlockModelResolver().update(created, s, DISPLAY_CONTEXT);
                return created;
            });

            pose.pushPose();
            pose.translate(worldPos.getX() - camera.x, worldPos.getY() - camera.y, worldPos.getZ() - camera.z);
            pose.translate(.01, .01, .01);
            pose.scale(.98f, .98f, .98f);
            int packedLight = LightCoordsUtil.FULL_BRIGHT;
            if (mc.level.hasChunkAt(worldPos)) {
                int blockLight = mc.level.getBrightness(LightLayer.BLOCK, worldPos);
                int skyLight = mc.level.getBrightness(LightLayer.SKY, worldPos);
                packedLight = LightCoordsUtil.pack(blockLight, skyLight);
            }
            // NeoForge 26.1's reference SubmitCustomGeometryEvent renderer passes
            // zero as the final shader argument. Using -1 here washes out large
            // previews, especially with shader packs.
            renderState.submit(pose, collector, packedLight, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }

    private static ItemStack currentCard(Minecraft mc) {
        if (mc == null || mc.player == null) return ItemStack.EMPTY;
        return mc.player.getMainHandItem();
    }

    private static boolean isWrittenCard(ItemStack stack) {
        return stack != null && stack.getItem() instanceof SchematicCardItem && SchematicCardItem.hasSource(stack);
    }

    private static void clearTransient() {
        if (!loadedKey.isEmpty() || plan != null) {
            loadedKey = "";
            plan = null;
            MODEL_CACHE.clear();
            editing = false;
            followingLook = false;
            ++loadGeneration;
        }
    }

    public static String mirrorName(int mirror) {
        return switch (mirror) {
            case 1 -> "Left/Right";
            case 2 -> "Front/Back";
            default -> "None";
        };
    }

    private static void message(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) mc.gui.getChat().addClientSystemMessage(Component.literal(message));
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
