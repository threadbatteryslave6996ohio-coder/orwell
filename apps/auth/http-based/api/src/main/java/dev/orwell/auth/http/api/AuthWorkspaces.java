package dev.orwell.auth.http.api;

/**
 * Workspace names and the rule for what may be one.
 *
 * <p>A workspace is the first path segment of every auth route ({@code /{workspace}/login}) and
 * the scope a {@code clientId} is unique within: {@code def/dummy} and {@code admin/dummy} are two
 * identities. Workspaces are not rows anywhere — an identity carries the name it was created in,
 * so a workspace exists exactly as long as an identity names it.
 *
 * <p>Two names are built in. {@link #DEFAULT} is where an unconfigured client looks, so every
 * caller that predates workspaces keeps working by landing there. {@link #ADMIN} is the one name
 * with a meaning attached: a token issued to an identity in it is what {@code POST
 * /{workspace}/identities} requires, in <em>every</em> workspace including {@code admin} itself.
 * That is deliberate — admin is a property of where the identity lives, not a flag on it, so the
 * grant is visible in the URL a credential was minted at rather than hidden in a column.
 */
public final class AuthWorkspaces {
    /** Where identities live when nobody chose a workspace. */
    public static final String DEFAULT = "def";

    /** Identities here may provision identities into any workspace. */
    public static final String ADMIN = "admin";

    private static final int MAX_LENGTH = 64;

    private AuthWorkspaces() {
    }

    /**
     * Whether a name may be used as a workspace.
     *
     * <p>Restrictive on purpose: the name is a URL path segment, so anything that could be
     * percent-decoded, traversed, or case-folded into a second spelling of the same workspace is
     * rejected rather than normalized. {@code health} is excluded because {@code /health} is the
     * shared health route on every server in the repo, and a workspace by that name would put an
     * auth route behind it under a route prefix.
     */
    public static boolean isValid(String workspace) {
        if (workspace == null || workspace.isEmpty() || workspace.length() > MAX_LENGTH) {
            return false;
        }
        if ("health".equals(workspace)) {
            return false;
        }
        for (int index = 0; index < workspace.length(); index++) {
            char character = workspace.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || (character == '-' && index > 0 && index < workspace.length() - 1);
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** The given workspace, or {@link #DEFAULT} when null or blank. */
    public static String orDefault(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return DEFAULT;
        }
        return workspace.trim();
    }
}
