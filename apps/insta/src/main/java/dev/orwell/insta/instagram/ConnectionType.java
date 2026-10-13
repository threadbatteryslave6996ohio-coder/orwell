package dev.orwell.insta.instagram;

/** Which side of an account's graph to scrape. The value is what the connections actor expects. */
public enum ConnectionType {
    FOLLOWERS("Followers"),
    // Plural, not "Following": scraping_solutions rejects anything but "Followers" / "Followings"
    // with a 400, which a test pinning the old value hid until the first real following walk.
    FOLLOWING("Followings");

    private final String actorValue;

    ConnectionType(String actorValue) {
        this.actorValue = actorValue;
    }

    public String actorValue() {
        return actorValue;
    }
}
