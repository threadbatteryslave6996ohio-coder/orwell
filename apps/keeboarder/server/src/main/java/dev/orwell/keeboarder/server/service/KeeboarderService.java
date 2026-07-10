package dev.orwell.keeboarder.server.service;

import dev.orwell.keeboarder.server.websocket.ChatEndpoint;
import org.springframework.stereotype.Service;

/** Server behavior shared by the standalone HTTP adapter and combined Spring app. */
@Service
public class KeeboarderService {
    public String connectedClientsJson() {
        return ChatEndpoint.getConnectedClientsJson();
    }

    public boolean send(String toClientId, String fromClientId, String content) {
        return ChatEndpoint.sendServerPersonalMessage(toClientId, fromClientId, content);
    }
}
