package ai.labs.p2p.impl;

import ai.labs.p2p.IPeerMessage;
import ai.labs.p2p.IServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
public class PeerMessageHandler {

    PeerMessage peerMessage;

    public PeerMessageHandler(String message) {
        String[] splitStrMessage = message.split("\\|");
        peerMessage = new PeerMessage();
        peerMessage.setPeerMessageType(IPeerMessage.PeerMessageType.valueOf(splitStrMessage[0]));
        peerMessage.setMessage(splitStrMessage[1]);
        Peer peer = new Peer();
        peer.setHostName(splitStrMessage[2]);
        peer.setPort(Integer.parseInt(splitStrMessage[3]));
        peer.setName(splitStrMessage[4]);
        peer.setPublicKey(splitStrMessage[5]);
        peerMessage.setPeer(peer);
    }

    public PeerMessage handleMessage(IServer server) {
        PeerMessage response = new PeerMessage();
        switch (peerMessage.getMessageType()) {
            case REGISTER:
            case REGISTERRESPONSE:
                if (!server.getAvailablePeers().contains(peerMessage.getPeer())) {
                    server.getAvailablePeers().add(peerMessage.getPeer());
                    response = new PeerMessage();
                    response.setPeer(server.getMyself());
                    response.setPeerMessageType(IPeerMessage.PeerMessageType.REGISTERRESPONSE);
                }
                break;
            case QUESTION:
                break;
            default:
        }
        return response;
    }
}
