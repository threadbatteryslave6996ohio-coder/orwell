package dev.orwell.insta.instagram;

/** Which side of an account's graph to scrape. The value is what the connections actor expects. */
public enum ConnectionType {
    FOLLOWERS("Followers"),
    FOLLOWING("Following");

    private final String actorValue;

    ConnectionType(String actorValue) {
        this.actorValue = actorValue;
    }

    public String actorValue() {
        return actorValue;
    }
}
