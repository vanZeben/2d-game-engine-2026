package ge.input;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

/**
 * The one place the game reads input from. Keys arrive from the window (as a {@link KeyListener}) or from a
 * {@link InputScript} player (via {@link #press}/{@link #release}); {@code Game} cannot tell which.
 *
 * <p>Each of the eight mapped keys is tracked on its own, so a direction stays pressed while either of its two
 * keys is held. Opposite directions are independent. Key events come on the AWT thread while the game thread
 * reads, so state is one volatile bit mask written under a lock.
 */
public class InputHandler implements KeyListener {
    private static final int UP = 1, DOWN = 2, LEFT = 4, RIGHT = 8;
    // One bit per mapped key.
    private static final int UP_ARROW = 1, W = 1 << 1, DOWN_ARROW = 1 << 2, S = 1 << 3,
            LEFT_ARROW = 1 << 4, A = 1 << 5, RIGHT_ARROW = 1 << 6, D = 1 << 7;

    private volatile int held;

    /** @return the bit for a key code, or 0 when the key has no game meaning. */
    private static int bit(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_UP -> UP_ARROW;
            case KeyEvent.VK_W -> W;
            case KeyEvent.VK_DOWN -> DOWN_ARROW;
            case KeyEvent.VK_S -> S;
            case KeyEvent.VK_LEFT -> LEFT_ARROW;
            case KeyEvent.VK_A -> A;
            case KeyEvent.VK_RIGHT -> RIGHT_ARROW;
            case KeyEvent.VK_D -> D;
            default -> 0;
        };
    }

    public synchronized void press(int keyCode) {
        held |= bit(keyCode);
    }

    public synchronized void release(int keyCode) {
        held &= ~bit(keyCode);
    }

    /** Clears every key, e.g. when the window loses focus and key-up events would be missed. */
    public synchronized void releaseAll() {
        held = 0;
    }

    public boolean up() {
        return (held & (UP_ARROW | W)) != 0;
    }

    public boolean down() {
        return (held & (DOWN_ARROW | S)) != 0;
    }

    public boolean left() {
        return (held & (LEFT_ARROW | A)) != 0;
    }

    public boolean right() {
        return (held & (RIGHT_ARROW | D)) != 0;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        press(e.getKeyCode());
    }

    @Override
    public void keyReleased(KeyEvent e) {
        release(e.getKeyCode());
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // typed characters are not part of the pressed state
    }
}
