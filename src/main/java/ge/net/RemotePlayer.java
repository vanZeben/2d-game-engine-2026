package ge.net;

import ge.Screen;
import ge.entity.Mob;
import ge.entity.Player;
import ge.gfx.SpriteSheet;

/**
 * Another player, as this client's world knows them: a name and a position taken from the network, drawn with the
 * same sprite as the local {@link Player}. It has no input and never moves by itself; later episodes position it
 * from move packets (see {@link Mob#setPosition}). Like every mob it is only touched by the world's owner thread.
 */
public final class RemotePlayer extends Mob {
    private final String name;
    /** Seq of the last move applied; -1 for a new remote, so the first move (seq >= 0) is accepted. Game thread only. */
    private int lastSeq = -1;

    /** @param x,y world pixel of the sprite's top-left corner, from the packet */
    public RemotePlayer(String name, int x, int y) {
        super(x, y);
        if (!GamePacket.isValidName(name)) throw new IllegalArgumentException("bad player name: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * Newest-seq-wins: puts the player at (x, y), with no collision check, if {@code seq} is strictly higher than the
     * last applied one; otherwise (stale, duplicate) changes nothing. Facing and walk frame are left as they are.
     * @return whether the move was applied
     */
    public boolean applyMove(int x, int y, int seq) {
        if (seq <= lastSeq) return false;
        lastSeq = seq;
        setPosition(x, y);
        return true;
    }

    /**
     * As {@link #applyMove(int, int, int)}, and the pose follows the same newest move: {@code dir} is a
     * {@link Mob.Dir} ordinal, {@code walkFrame} 0 or 1 (both validated by {@link GamePacket.Move}).
     */
    public boolean applyMove(int x, int y, int dir, int walkFrame, int seq) {
        if (!applyMove(x, y, seq)) return false;
        setPose(Dir.values()[dir], walkFrame);
        return true;
    }

    /**
     * The server announced this player (again) at (x, y): forget the old sequence numbers, because a fresh login
     * restarts the sender's seq at 0 and a remote that missed the PlayerLeft in between would otherwise ignore every
     * move of the new session as stale. Position is taken from the notice; the pose stays until the next move.
     */
    public void reset(int x, int y) {
        lastSeq = -1;
        setPosition(x, y);
    }

    @Override
    public void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset) {
        Player.drawPose(screen, sheet, x() - xOffset, y() - yOffset, dir(), walkFrame(), isSwimming());
    }
}
