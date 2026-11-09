package dev.simplex.itm.maps.placement;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Hanging;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a {@code tilesX × tilesY} image would end up for a targeted block face, plus per cell validation.
 *
 * @param anchor   frame block of the top-left tile
 * @param right    step vector for columns (viewer's right)
 * @param down     step vector for rows (viewer's down)
 * @param rotation item frame map rotation (0-3) for floors / ceilings
 */
public record PlacementPlan(World world, int[] anchor, BlockFace face, int[] right, int[] down, int rotation,
                            int tilesX, int tilesY, List<Cell> cells) {

    public enum Problem { NONE, NO_SUPPORT, BLOCKED, OCCUPIED }

    public record Cell(int col, int row, int x, int y, int z, Problem problem) {}

    public boolean valid() {
        for (Cell c : cells) if (c.problem != Problem.NONE) return false;
        return !cells.isEmpty();
    }

    public long count(Problem p) {
        return cells.stream().filter(c -> c.problem == p).count();
    }

    private static final BlockFace[] CARDINAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /** Viewer's right-hand direction when looking at a wall whose frames face {@code face}. */
    public static int[] rightOf(BlockFace face) {
        return switch (face) {
            case NORTH -> new int[]{-1, 0, 0};
            case SOUTH -> new int[]{1, 0, 0};
            case EAST -> new int[]{0, 0, -1};
            default -> new int[]{0, 0, 1}; // WEST
        };
    }

    private static int[] vec(BlockFace f) {
        return new int[]{f.getModX(), f.getModY(), f.getModZ()};
    }

    public static BlockFace cardinal(float yaw) {
        int i = Math.floorMod(Math.round(yaw / 90f), 4); // 0 = south
        return switch (i) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private static int index(BlockFace[] arr, BlockFace f) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == f) return i;
        return 0;
    }

    /**
     * @param hit    the targeted block
     * @param face   the targeted face (frames will face this way)
     * @param facing horizontal facing of the player (decides the image's "up" on floors and ceilings)
     */
    public static PlacementPlan compute(Block hit, BlockFace face, BlockFace facing, int tilesX, int tilesY) {
        int[] right, down;
        int rotation = 0;
        if (face == BlockFace.UP) {
            // image top points away from the player; map rotation 0 = top towards north, clockwise seen from above
            right = vec(CARDINAL[(index(CARDINAL, facing) + 1) % 4]);
            down = vec(facing.getOppositeFace());
            rotation = index(CARDINAL, facing);
        } else if (face == BlockFace.DOWN) {
            // ceiling: rotation 0 = top towards south, clockwise seen from below
            right = vec(CARDINAL[(index(CARDINAL, facing) + 3) % 4]);
            down = vec(facing.getOppositeFace());
            rotation = index(new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST, BlockFace.NORTH, BlockFace.WEST}, facing);
        } else {
            right = rightOf(face);
            down = new int[]{0, -1, 0};
        }
        Block center = hit.getRelative(face);
        int offX = (tilesX - 1) / 2, offY = (tilesY - 1) / 2;
        int[] anchor = {
                center.getX() - right[0] * offX - down[0] * offY,
                center.getY() - right[1] * offX - down[1] * offY,
                center.getZ() - right[2] * offX - down[2] * offY
        };
        World world = hit.getWorld();
        List<Cell> cells = new ArrayList<>(tilesX * tilesY);
        for (int row = 0; row < tilesY; row++) {
            for (int col = 0; col < tilesX; col++) {
                int x = anchor[0] + right[0] * col + down[0] * row;
                int y = anchor[1] + right[1] * col + down[1] * row;
                int z = anchor[2] + right[2] * col + down[2] * row;
                Problem problem = Problem.NONE;
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    problem = Problem.BLOCKED;
                } else {
                    Block frameBlock = world.getBlockAt(x, y, z);
                    Block support = frameBlock.getRelative(face.getOppositeFace());
                    if (!frameBlock.isPassable() || frameBlock.isLiquid()) {
                        problem = Problem.BLOCKED;
                    } else if (!support.getType().isSolid()) {
                        problem = Problem.NO_SUPPORT;
                    } else {
                        for (var e : world.getNearbyEntities(org.bukkit.util.BoundingBox.of(frameBlock).expand(-0.05), en -> en instanceof Hanging)) {
                            if (((Hanging) e).getFacing() == face) {
                                problem = Problem.OCCUPIED;
                                break;
                            }
                        }
                    }
                }
                cells.add(new Cell(col, row, x, y, z, problem));
            }
        }
        return new PlacementPlan(world, anchor, face, right, down, rotation, tilesX, tilesY, cells);
    }
}
