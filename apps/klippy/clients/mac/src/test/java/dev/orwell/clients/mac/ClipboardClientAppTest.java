package dev.orwell.clients.mac;

import dev.orwell.clients.core.ClipboardReader;
import org.junit.jupiter.api.Test;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClipboardClientAppTest {
    @Test
    void readsStringContentFromClipboard() throws Exception {
        Clipboard clipboard = new Clipboard("test");
        clipboard.setContents(new StringSelection("copied text"), null);

        ClipboardReader reader = ClipboardClientApp.clipboardReader(clipboard);

        assertEquals("copied text", reader.readText());
    }

    @Test
    void returnsNullWhenClipboardHoldsNoText() throws Exception {
        Clipboard clipboard = new Clipboard("test");

        ClipboardReader reader = ClipboardClientApp.clipboardReader(clipboard);

        assertNull(reader.readText());
    }
}
