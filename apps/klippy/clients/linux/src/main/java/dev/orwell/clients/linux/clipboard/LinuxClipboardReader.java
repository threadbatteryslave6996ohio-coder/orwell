package dev.orwell.clients.linux.clipboard;

import dev.orwell.clients.core.ClipboardReader;

import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * A Linux clipboard backend. Extends the shared {@link ClipboardReader} with an
 * {@link #isAvailable()} probe so the factory can pick the first usable backend.
 */
public interface LinuxClipboardReader extends ClipboardReader {
    @Override
    String name();

    boolean isAvailable();

    @Override
    String readText() throws IOException, InterruptedException, UnsupportedFlavorException;
}
