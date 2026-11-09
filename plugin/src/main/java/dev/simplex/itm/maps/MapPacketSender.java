package dev.simplex.itm.maps;

import org.bukkit.entity.Player;
import org.bukkit.map.MapView;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Sends <b>partial</b> map updates (only the changed rectangle) directly to a client. This is what makes animated
 * GIFs smooth: vanilla only syncs maps in item frames every 10 ticks, and {@link Player#sendMap(MapView)} always
 * sends the full 16 KB. Uses Mojang-mapped class names (Paper ≥ 1.20.5 runs on Mojang mappings), resolved once via
 * reflection; if anything changes in a future version it silently falls back to {@code Player#sendMap}.
 * Geyser translates the same packets into Bedrock map updates, so Bedrock players get partial updates too.
 */
public final class MapPacketSender {

    private MethodHandle getHandle;
    private Field connectionField;
    private MethodHandle send;
    private MethodHandle newMapId;
    private MethodHandle newPatch;
    private MethodHandle newPacket;
    private boolean fast;

    public MapPacketSender(Logger logger) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Method gh = craftPlayer.getMethod("getHandle");
            getHandle = lookup.unreflect(gh);
            Class<?> serverPlayer = gh.getReturnType();
            connectionField = findField(serverPlayer, "connection");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet");
            Method sendMethod = findMethod(connectionField.getType(), "send", packetClass);
            send = lookup.unreflect(sendMethod);

            Class<?> mapIdClass = Class.forName("net.minecraft.world.level.saveddata.maps.MapId");
            newMapId = lookup.unreflectConstructor(mapIdClass.getConstructor(int.class));
            Class<?> patchClass = Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch");
            newPatch = lookup.unreflectConstructor(patchClass.getConstructor(int.class, int.class, int.class, int.class, byte[].class));
            Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundMapItemDataPacket");
            Constructor<?> ctor = null;
            for (Constructor<?> c : packet.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 5 && p[0] == mapIdClass && p[1] == byte.class && p[2] == boolean.class && p[3] == Optional.class && p[4] == Optional.class) {
                    ctor = c;
                }
            }
            if (ctor == null) throw new NoSuchMethodException("ClientboundMapItemDataPacket(MapId, byte, boolean, Optional, Optional)");
            newPacket = lookup.unreflectConstructor(ctor);
            fast = true;
            logger.info("Fast partial map updates enabled.");
        } catch (Throwable t) {
            fast = false;
            logger.warning("Fast map packets unavailable (" + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + ") – falling back to Player#sendMap. Animations still work, but use more bandwidth.");
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?> param) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == param) return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    public boolean fast() { return fast; }

    /** Sends the region {@code rect = {x, y, w, h}} of {@code tile} (128×128 indices). */
    public boolean sendRegion(Player player, int mapId, byte[] tile, int[] rect) {
        if (!fast) return false;
        try {
            int x = rect[0], y = rect[1], w = rect[2], h = rect[3];
            byte[] data = new byte[w * h];
            for (int row = 0; row < h; row++) System.arraycopy(tile, (y + row) * 128 + x, data, row * w, w);
            Object id = newMapId.invoke(mapId);
            Object patch = newPatch.invoke(x, y, w, h, data);
            Object packet = newPacket.invoke(id, (byte) 0, true, Optional.empty(), Optional.of(patch));
            Object handle = getHandle.invoke(player);
            Object connection = connectionField.get(handle);
            if (connection == null) return false;
            send.invoke(connection, packet);
            return true;
        } catch (Throwable t) {
            fast = false;
            return false;
        }
    }

    public static final int[] FULL = {0, 0, 128, 128};
}
