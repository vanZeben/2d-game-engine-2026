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

    /** @param x,y world pixel of the sprite's top-left corner, from the packet */
    public RemotePlayer(String name, int x, int y) {
        super(x, y);
        if (!GamePacket.isValidName(name)) throw new IllegalArgumentException("bad player name: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }

    @Override
    public void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset) {
        Player.drawPose(screen, sheet, x() - xOffset, y() - yOffset, dir(), walkFrame(), isSwimming());
    }
}
