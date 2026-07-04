package dev.orwell.bucket.proxy.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectKeysTest {
    @Test
    void createsTheSameSafeObjectKeyForEveryProvider() {
        assertThat(ObjectKeys.objectKey("/screen captures/host/", "../capture.webm"))
                .isEqualTo("screen_captures/host/capture.webm");
    }

    @Test
    void rejectsTraversalInExistingKeys() {
        assertThatThrownBy(() -> ObjectKeys.normalizeKey("uploads/../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid object key.");
    }
}
