package dev.orwell.auth.http.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthWorkspacesTest {
    @Test
    void acceptsTheBuiltInNamesAndOrdinaryOnes() {
        assertTrue(AuthWorkspaces.isValid(AuthWorkspaces.DEFAULT));
        assertTrue(AuthWorkspaces.isValid(AuthWorkspaces.ADMIN));
        assertTrue(AuthWorkspaces.isValid("team-a"));
        assertTrue(AuthWorkspaces.isValid("t3am-a-b"));
    }

    /**
     * Every rejection here is a second spelling of some workspace, a path that is not one segment,
     * or the health route — the three ways a name could point somewhere its author did not mean.
     */
    @Test
    void rejectsNamesThatWouldNotBeOneUnambiguousPathSegment() {
        assertFalse(AuthWorkspaces.isValid(null));
        assertFalse(AuthWorkspaces.isValid(""));
        assertFalse(AuthWorkspaces.isValid("Def"));
        assertFalse(AuthWorkspaces.isValid("team a"));
        assertFalse(AuthWorkspaces.isValid("team_a"));
        assertFalse(AuthWorkspaces.isValid("team/a"));
        assertFalse(AuthWorkspaces.isValid(".."));
        assertFalse(AuthWorkspaces.isValid("-team"));
        assertFalse(AuthWorkspaces.isValid("team-"));
        assertFalse(AuthWorkspaces.isValid("health"));
        assertFalse(AuthWorkspaces.isValid("a".repeat(65)));
    }

    @Test
    void fallsBackToTheDefaultWorkspaceForAnAbsentName() {
        assertEquals(AuthWorkspaces.DEFAULT, AuthWorkspaces.orDefault(null));
        assertEquals(AuthWorkspaces.DEFAULT, AuthWorkspaces.orDefault("  "));
        assertEquals("team-a", AuthWorkspaces.orDefault(" team-a "));
    }
}
