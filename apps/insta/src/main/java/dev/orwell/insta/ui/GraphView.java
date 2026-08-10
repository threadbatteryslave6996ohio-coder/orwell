package dev.orwell.insta.ui;

import java.util.List;

/**
 * What the browser draws: the accounts in view and the follows between them.
 *
 * @param nodes every account in scope, with the handle it currently goes by
 * @param links every follow we know about <em>between accounts already in {@code nodes}</em> —
 *              which is what turns a star of followers into a web wherever two of them have also
 *              been synced
 */
public record GraphView(List<Node> nodes, List<Link> links) {

    /**
     * @param subject   whether this is the account the view is centred on
     * @param followers how many live followers we have recorded for it, used for node size
     */
    public record Node(String id, String username, boolean subject, int followers) {
    }

    /** {@code follower} follows {@code followee}. {@code active} false means they have left. */
    public record Link(String followee, String follower, boolean active) {
    }
}
