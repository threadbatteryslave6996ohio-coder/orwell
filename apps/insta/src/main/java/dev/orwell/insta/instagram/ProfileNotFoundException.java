package dev.orwell.insta.instagram;

/**
 * No public Instagram profile exists for a username — or none the actor can see, which from here
 * is the same thing. Distinct from {@link ApifyException} because nothing went wrong: the answer
 * is simply "no such account", and {@link InstaCli} gives it its own exit code so a script can
 * tell a missing account apart from a failed lookup.
 */
public class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException(String message) {
        super(message);
    }
}
