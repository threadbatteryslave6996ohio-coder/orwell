package dev.orwell.google.gmail;

/**
 * An attachment as it appears in a payload: everything needed to decide whether to fetch it, and
 * where to fetch it from — but not the bytes.
 *
 * <p>By reference rather than inline because deliveries and list responses would otherwise carry
 * whole files. Base64 inflates content by a third, and a single 25 MB attachment inlined into a
 * webhook POST would block that subscription's cursor behind one enormous request for as long as
 * the receiver took to accept it.
 *
 * @param n         the index in {@code url}; stable for a stored message.
 * @param url       where to GET the bytes. Absolute when {@code GMAIL_PUBLIC_BASE_URL} is set, and
 *                  a path relative to this service's root otherwise — the service cannot know the
 *                  address it is reachable at from outside unless it is told.
 * @param contentId the {@code Content-ID} without angle brackets, which an HTML body references as
 *                  {@code src="cid:..."}. Null when the part has none.
 * @param inline    true for a part meant to render inside the body, such as an embedded image,
 *                  rather than to be listed as a file.
 * @param available false when the message exceeded {@code GMAIL_MAX_MESSAGE_BYTES}: the part is
 *                  described here but its bytes were never stored, so {@code url} will answer 409.
 */
public record AttachmentRef(
        int n,
        String filename,
        String mimeType,
        long sizeBytes,
        String contentId,
        boolean inline,
        boolean available,
        String url) {
}
