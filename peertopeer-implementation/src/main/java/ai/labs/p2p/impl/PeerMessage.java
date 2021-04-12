package ai.labs.p2p.impl;

import ai.labs.p2p.IPeer;
import ai.labs.p2p.IPeerMessage;
import lombok.Setter;

import java.io.Serializable;

@Setter
public class PeerMessage implements IPeerMessage, Serializable {

    private IPeer peer;
    private PeerMessageType peerMessageType;
    private String message;

    @Override
    public IPeer getPeer() {
        return peer;
    }

    @Override
    public PeerMessageType getMessageType() {
        return peerMessageType;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return peerMessageType.toString() + "|" + message + "|" + peer.getHostName() + "|" + peer.getPort() + "|" + peer.getName() + "|" + peer.getPublicKey();
    }
}
