package dev.orwell.keeboarder.server.service;

import dev.orwell.keeboarder.server.websocket.ChatEndpoint;
import org.springframework.stereotype.Service;

/** Server behavior backing the Keeboarder REST API. */
@Service
public class KeeboarderService {
    public String connectedClientsJson() {
        return ChatEndpoint.getConnectedClientsJson();
    }

    public boolean send(String toClientId, String fromClientId, String content) {
        return ChatEndpoint.sendServerPersonalMessage(toClientId, fromClientId, content);
    }
}
