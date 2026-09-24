package dev.nuclr.plugin.core.assimp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * The "converting with Blender" notice shown over the viewport while a
 * {@code .blend} (or USD, Alembic) is turned into glTF, which can take a while.
 *
 * <p>It appears only once the conversion has run for {@link #SHOW_DELAY_MS}, so a
 * cache hit never flashes it, and it closes by itself when the conversion ends.
 *
 * <p>The window never takes keyboard focus: the quick view follows the panel
 * cursor, and a notice that grabbed the keys would stop the user arrowing on to
 * the next file. Esc still closes it, through a dispatcher that is installed only
 * while it is on screen. Closing it only hides it; the conversion carries on and
 * the status bar keeps reporting it.
 *
 * <p>EDT only.
 */
final class ConversionPopup {

    static final int SHOW_DELAY_MS = 400;

    private final JComponent anchor;

    private final Timer showTimer;
    private final Timer elapsedTimer;

    private JWindow window;
    private JLabel fileLabel;
    private JLabel elapsedLabel;

    private String fileName;
    private long startedAtNanos;

    private final KeyEventDispatcher escToClose = event -> {
        if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ESCAPE
                && event.getModifiersEx() == 0 && isShowing()) {
            hide();
            return true;
        }
        return false;
    };

    /** @param anchor the component the notice is centred over */
    ConversionPopup(JComponent anchor) {
        this.anchor = anchor;
        this.showTimer = new Timer(SHOW_DELAY_MS, e -> showNow());
        this.showTimer.setRepeats(false);
        this.elapsedTimer = new Timer(1000, e -> updateElapsed());
    }

    /** A conversion of {@code name} has started; the notice follows unless it ends first. */
    void conversionStarted(String name) {
        hide();
        fileName = name;
        startedAtNanos = System.nanoTime();
        showTimer.restart();
    }

    /** Closes the notice, or keeps it from appearing. Safe to call at any time. */
    void hide() {
        showTimer.stop();
        elapsedTimer.stop();
        if (window != null && window.isVisible()) {
            window.setVisible(false);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escToClose);
        }
    }

    /** Releases the window for good; the panel is going away. */
    void dispose() {
        hide();
        if (window != null) {
            window.dispose();
            window = null;
        }
    }

    boolean isShowing() {
        return window != null && window.isVisible();
    }

    private void showNow() {
        // The preview may have been closed or scrolled away while the delay ran.
        if (!anchor.isShowing()) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(anchor);
        if (window == null || window.getOwner() != owner) {
            if (window != null) {
                window.dispose();
            }
            window = build(owner);
        }

        fileLabel.setText(fileName);
        updateElapsed();
        window.pack();
        window.setSize(Math.max(window.getWidth(), 320), window.getHeight());

        Point origin = anchor.getLocationOnScreen();
        window.setLocation(origin.x + (anchor.getWidth() - window.getWidth()) / 2,
                origin.y + (anchor.getHeight() - window.getHeight()) / 2);

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escToClose);
        window.setVisible(true);
        elapsedTimer.restart();
    }

    private JWindow build(Window owner) {
        JWindow popup = new JWindow(owner);
        popup.setFocusableWindowState(false);
        popup.setAlwaysOnTop(false);

        JLabel title = new JLabel("Converting with Blender…");
        title.setFont(title.getFont().deriveFont(Font.BOLD));

        fileLabel = new JLabel(" ");
        elapsedLabel = new JLabel(" ");
        elapsedLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(280, bar.getPreferredSize().height));

        JButton hideButton = new JButton("Hide");
        hideButton.setFocusable(false);
        hideButton.setToolTipText("Hide this notice (Esc). The conversion carries on.");
        hideButton.addActionListener(e -> hide());

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        for (JComponent line : new JComponent[] { title, fileLabel, bar, elapsedLabel }) {
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(line);
            text.add(Box.createVerticalStrut(6));
        }

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(hideButton);

        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        content.add(text, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        popup.setContentPane(content);
        return popup;
    }

    private static Color borderColor() {
        Color color = UIManager.getColor("Component.borderColor");
        return color != null ? color : new Color(60, 60, 60);
    }

    private void updateElapsed() {
        long seconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
        elapsedLabel.setText(seconds < 1 ? "Starting…" : seconds + " s elapsed — Esc to hide");
    }
}
