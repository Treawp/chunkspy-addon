package com.egaganteng.chunkspy.modules;

import com.egaganteng.chunkspy.ChunkSpyAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.*;

/**
 * OrientationChunkFinder
 * ----------------------
 * Deteksi stash / base tersembunyi berdasarkan orientasi block yang TIDAK WAJAR
 * di Y rendah (deepslate layer).
 *
 * LOGIKA DETEKSI:
 *  - Block alam → axis = Y (deepslate tumbuh vertikal)
 *  - Block manusia → axis = X / Z (ditaruh miring) → MENCURIGAKAN
 *  - Block "manmade" (chest, furnace, dll) di Y < threshold → MENCURIGAKAN
 *
 * ESP: Highlight box di tiap block mencurigakan + flat box di atas chunk.
 */
public class OrientationChunkFinder extends Module {

    // ───────────────────────────── SETTING GROUPS ─────────────────────────────

    private final SettingGroup sgDetection    = settings.createGroup("Detection");
    private final SettingGroup sgEsp          = settings.createGroup("ESP");
    private final SettingGroup sgNotification = settings.createGroup("Notification");

    // ── Detection ──

    private final Setting<Integer> yThreshold = sgDetection.add(new IntSetting.Builder()
        .name("y-threshold")
        .description("Hanya scan block di Y lebih rendah dari nilai ini")
        .defaultValue(-30)
        .range(-64, 64)
        .sliderRange(-64, 64)
        .build());

    private final Setting<Boolean> detectRotated = sgDetection.add(new BoolSetting.Builder()
        .name("detect-rotated-blocks")
        .description("Deteksi deepslate/log/pillar yang axis-nya horizontal (X/Z) → kemungkinan player-placed")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> detectManmade = sgDetection.add(new BoolSetting.Builder()
        .name("detect-manmade-blocks")
        .description("Deteksi block buatan tangan (chest, furnace, crafting, barrel, dll) di Y rendah")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreExposed = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-exposed")
        .description("Abaikan block yang terekspos ke udara (kemungkinan natural cave)")
        .defaultValue(false)
        .build());

    private final Setting<Integer> minSuspiciousToFlag = sgDetection.add(new IntSetting.Builder()
        .name("min-suspicious-per-chunk")
        .description("Minimum block mencurigakan agar chunk di-flag")
        .defaultValue(2)
        .range(1, 20)
        .sliderRange(1, 20)
        .build());

    // ── ESP ──

    private final Setting<Boolean> espBlocks = sgEsp.add(new BoolSetting.Builder()
        .name("esp-blocks")
        .description("Render box di sekitar block mencurigakan")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> espChunk = sgEsp.add(new BoolSetting.Builder()
        .name("esp-chunk")
        .description("Render flat box di atas chunk yang di-flag")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgEsp.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> colorRotated = sgEsp.add(new ColorSetting.Builder()
        .name("rotated-color")
        .description("Warna block rotasi mencurigakan")
        .defaultValue(new SettingColor(255, 60, 60, 180))
        .build());

    private final Setting<SettingColor> colorManmade = sgEsp.add(new ColorSetting.Builder()
        .name("manmade-color")
        .description("Warna block manmade")
        .defaultValue(new SettingColor(60, 180, 255, 180))
        .build());

    private final Setting<SettingColor> colorChunk = sgEsp.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Warna highlight chunk")
        .defaultValue(new SettingColor(255, 215, 0, 80))
        .build());

    private final Setting<Double> chunkHighlightY = sgEsp.add(new DoubleSetting.Builder()
        .name("chunk-highlight-y")
        .description("Ketinggian render flat box chunk")
        .defaultValue(64.0)
        .range(-64.0, 320.0)
        .sliderRange(-64.0, 320.0)
        .build());

    private final Setting<Integer> maxEspBlocks = sgEsp.add(new IntSetting.Builder()
        .name("max-esp-blocks")
        .description("Maksimum block yang di-render (performance)")
        .defaultValue(300)
        .range(50, 1000)
        .sliderRange(50, 1000)
        .build());

    // ── Notification ──

    private final Setting<Boolean> chatAlert = sgNotification.add(new BoolSetting.Builder()
        .name("chat-alert")
        .description("Kirim pesan di chat saat chunk di-flag")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> toastAlert = sgNotification.add(new BoolSetting.Builder()
        .name("toast-alert")
        .description("Tampilkan toast notification")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> soundAlert = sgNotification.add(new BoolSetting.Builder()
        .name("sound-alert")
        .description("Bunyi saat deteksi")
        .defaultValue(true)
        .build());

    // ───────────────────────────── DATA ─────────────────────────────────────

    // Chunk yang di-flag
    private final Set<ChunkPos>                       flaggedChunks    = ConcurrentHashMap.newKeySet();
    // Block mencurigakan: posisi → tipe
    private final ConcurrentHashMap<BlockPos, SusType> suspiciousBlocks = new ConcurrentHashMap<>();
    // Chunks yang sudah di-scan
    private final Set<ChunkPos>                       scannedChunks    = ConcurrentHashMap.newKeySet();
    // Cooldown notifikasi per chunk
    private final ConcurrentHashMap<ChunkPos, Long>   notifCooldown    = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChunkSpy-Scanner");
        t.setDaemon(true);
        return t;
    });

    // ──────── Block sets ────────

    /** Block yang punya Properties.AXIS — kalau axis != Y di kedalaman → player placed */
    private static final Set<Block> AXIS_BLOCKS = Set.of(
        Blocks.DEEPSLATE,
        Blocks.COBBLED_DEEPSLATE,   // tidak punya axis, tapi included untuk manmade check
        Blocks.CHISELED_DEEPSLATE,
        Blocks.POLISHED_DEEPSLATE,
        Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG,
        Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
        Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
        Blocks.OAK_WOOD, Blocks.SPRUCE_WOOD, Blocks.BIRCH_WOOD,
        Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG,
        Blocks.STRIPPED_DEEPSLATE,
        Blocks.BASALT, Blocks.POLISHED_BASALT,
        Blocks.BONE_BLOCK,
        Blocks.HAY_BLOCK,
        Blocks.PURPUR_PILLAR,
        Blocks.QUARTZ_PILLAR
    );

    /** Block buatan manusia — kehadirannya di Y rendah sudah sangat mencurigakan */
    private static final Set<Block> MANMADE_BLOCKS = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST,
        Blocks.BARREL,
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
        Blocks.CRAFTING_TABLE,
        Blocks.ENCHANTING_TABLE,
        Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.BREWING_STAND,
        Blocks.BEACON,
        Blocks.HOPPER,
        Blocks.DISPENSER, Blocks.DROPPER,
        Blocks.PISTON, Blocks.STICKY_PISTON,
        Blocks.OBSERVER,
        Blocks.COMPARATOR, Blocks.REPEATER,
        Blocks.LEVER, Blocks.STONE_BUTTON, Blocks.OAK_BUTTON,
        Blocks.JUKEBOX,
        Blocks.NOTE_BLOCK,
        Blocks.SHULKER_BOX,
        Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX,
        Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
        Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX,
        Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX,
        Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX,
        Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
        Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX,
        Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX,
        Blocks.TORCH, Blocks.WALL_TORCH,
        Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
        Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
        Blocks.LADDER,
        Blocks.IRON_DOOR, Blocks.OAK_DOOR,
        Blocks.IRON_TRAPDOOR, Blocks.OAK_TRAPDOOR,
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
        Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
        Blocks.CRACKED_STONE_BRICKS,
        Blocks.BOOKSHELVES,
        Blocks.BOOKSHELF
    );

    // ───────────────────────────── KONSTRUKTOR ───────────────────────────────

    public OrientationChunkFinder() {
        super(ChunkSpyAddon.NAME, "orientation-chunk-finder",
            "Deteksi stash/base dari block yang rotasinya tidak wajar di Y rendah");
    }

    // ───────────────────────────── EVENT HANDLERS ────────────────────────────

    @Override
    public void onDeactivate() {
        flaggedChunks.clear();
        suspiciousBlocks.clear();
        scannedChunks.clear();
        notifCooldown.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        ChunkPos pos = event.chunk.getPos();
        if (scannedChunks.contains(pos)) return;
        scanChunkAsync(event.chunk, pos);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        // Cleanup chunk yang udah terlalu jauh
        int viewDist = mc.options.getViewDistance().getValue();
        int px = (int) mc.player.getX() >> 4;
        int pz = (int) mc.player.getZ() >> 4;

        flaggedChunks.removeIf(cp -> {
            boolean far = Math.abs(cp.x - px) > viewDist + 4
                       || Math.abs(cp.z - pz) > viewDist + 4;
            if (far) {
                notifCooldown.remove(cp);
                // bersihkan juga block dari chunk ini
                suspiciousBlocks.keySet().removeIf(bp ->
                    (bp.getX() >> 4) == cp.x && (bp.getZ() >> 4) == cp.z);
            }
            return far;
        });
        scannedChunks.removeIf(cp ->
            Math.abs(cp.x - px) > viewDist + 2 || Math.abs(cp.z - pz) > viewDist + 2);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;

        // Render chunk highlight
        if (espChunk.get()) {
            Color cc = new Color(colorChunk.get());
            for (ChunkPos cp : flaggedChunks) {
                renderChunkBox(event, cp, cc);
            }
        }

        // Render individual block ESP
        if (espBlocks.get()) {
            int rendered = 0;
            int viewPx   = mc.options.getViewDistance().getValue() * 16;

            for (Map.Entry<BlockPos, SusType> e : suspiciousBlocks.entrySet()) {
                if (rendered >= maxEspBlocks.get()) break;
                BlockPos bp = e.getKey();
                if (mc.player.getPos().distanceTo(Vec3d.ofCenter(bp)) > viewPx + 32) continue;

                Color c = e.getValue() == SusType.ROTATED
                    ? new Color(colorRotated.get())
                    : new Color(colorManmade.get());

                Box box = new Box(bp);
                event.renderer.box(box, c, c, shapeMode.get(), 0);
                rendered++;
            }
        }
    }

    // ───────────────────────────── SCAN LOGIC ────────────────────────────────

    private void scanChunkAsync(WorldChunk chunk, ChunkPos pos) {
        executor.submit(() -> {
            try {
                scanChunk(chunk, pos);
            } catch (Exception ignored) {}
        });
    }

    private void scanChunk(WorldChunk chunk, ChunkPos pos) {
        if (mc.world == null) return;

        int yMin   = mc.world.getBottomY();
        int yLimit = yThreshold.get();

        Map<BlockPos, SusType> found = new HashMap<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = yMin; y < yLimit; y++) {
                    BlockPos bp         = new BlockPos(pos.getStartX() + x, y, pos.getStartZ() + z);
                    BlockState state    = chunk.getBlockState(bp);
                    Block      block    = state.getBlock();

                    if (block == Blocks.AIR
                     || block == Blocks.CAVE_AIR
                     || block == Blocks.VOID_AIR) continue;

                    // Skip kalau exposed ke udara dan setting aktif
                    if (ignoreExposed.get() && isExposed(bp)) continue;

                    // Cek ROTATED (axis horizontal)
                    if (detectRotated.get() && AXIS_BLOCKS.contains(block)) {
                        if (state.contains(Properties.AXIS)) {
                            Axis axis = state.get(Properties.AXIS);
                            if (axis == Axis.X || axis == Axis.Z) {
                                found.put(bp, SusType.ROTATED);
                                continue;
                            }
                        }
                        // Cek FACING horizontal (bukan UP/DOWN)
                        if (state.contains(Properties.FACING)) {
                            Direction facing = state.get(Properties.FACING);
                            if (facing != Direction.UP && facing != Direction.DOWN) {
                                found.put(bp, SusType.ROTATED);
                                continue;
                            }
                        }
                        // Cek HORIZONTAL_FACING
                        if (state.contains(Properties.HORIZONTAL_FACING)) {
                            found.put(bp, SusType.ROTATED);
                            continue;
                        }
                    }

                    // Cek MANMADE
                    if (detectManmade.get() && MANMADE_BLOCKS.contains(block)) {
                        found.put(bp, SusType.MANMADE);
                    }
                }
            }
        }

        scannedChunks.add(pos);

        if (found.size() >= minSuspiciousToFlag.get()) {
            flaggedChunks.add(pos);
            suspiciousBlocks.putAll(found);
            long now = System.currentTimeMillis();
            Long last = notifCooldown.get(pos);
            if (last == null || now - last > 30_000) {
                notifCooldown.put(pos, now);
                long rotCount    = found.values().stream().filter(t -> t == SusType.ROTATED).count();
                long manmadeCount= found.values().stream().filter(t -> t == SusType.MANMADE).count();
                String detail = String.format("R:%d M:%d total:%d", rotCount, manmadeCount, found.size());
                sendAlert(pos, detail);
            }
        }
    }

    // ───────────────────────────── HELPERS ───────────────────────────────────

    private boolean isExposed(BlockPos bp) {
        if (mc.world == null) return false;
        for (Direction dir : Direction.values()) {
            BlockState adj = mc.world.getBlockState(bp.offset(dir));
            if (adj.isAir() || !adj.isSolidBlock(mc.world, bp.offset(dir))) return true;
        }
        return false;
    }

    private void renderChunkBox(Render3DEvent event, ChunkPos cp, Color color) {
        double sx = cp.getStartX(), sz = cp.getStartZ();
        double ex = cp.getEndX() + 1, ez = cp.getEndZ() + 1;
        double y  = chunkHighlightY.get();
        Box box   = new Box(sx, y, sz, ex, y + 0.3, ez);
        event.renderer.box(box, color, color, shapeMode.get(), 0);
    }

    private void sendAlert(ChunkPos pos, String detail) {
        mc.execute(() -> {
            String msg = String.format("[ChunkSpy] Chunk [%d, %d] mencurigakan! %s",
                pos.x, pos.z, detail);

            if (chatAlert.get() && mc.player != null) {
                mc.player.sendMessage(Text.literal(msg), false);
            }
            if (toastAlert.get()) {
                mc.getToastManager().add(
                    new MeteorToast(Items.COMPASS, "ChunkSpy Alert", msg));
            }
            if (soundAlert.get()) {
                mc.getSoundManager().play(
                    PositionedSoundInstance.master(
                        SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.5f));
            }
        });
    }

    @Override
    public String getInfoString() {
        return String.format("C:%d B:%d", flaggedChunks.size(), suspiciousBlocks.size());
    }

    // ─────────── Enum ────────────────────────────────────────────────────────

    private enum SusType { ROTATED, MANMADE }
}
