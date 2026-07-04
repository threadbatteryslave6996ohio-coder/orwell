package dev.orwell.keeboarder.server;

import com.google.gson.Gson;

public class Message {
    public String type;
    public String clientId;
    public String name;
    public String token;
    public String toClientId;
    public String content;

    private static final Gson gson = new Gson();

    public static Message fromJson(String json) {
        try {
            return gson.fromJson(json, Message.class);
        } catch (Exception e) {
            return null;
        }
    }

    public String toJson() {
        return gson.toJson(this);
    }
}
