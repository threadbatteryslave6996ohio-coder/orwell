package dev.clippy.clients.linux.clipboard;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * Reads the clipboard through AWT. Keeps one clipboard connection open, so the factory
 * prefers it over command backends that spawn a process for every poll.
 */
public final class AwtClipboardReader implements LinuxClipboardReader {
    @Override
    public String name() {
        return "awt";
    }

    @Override
    public boolean isAvailable() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard();
            return true;
        } catch (HeadlessException exception) {
            return false;
        }
    }

    @Override
    public String readText() throws IOException, UnsupportedFlavorException {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            return null;
        }

        Object data = clipboard.getData(DataFlavor.stringFlavor);
        return data instanceof String text ? text : null;
    }
}
