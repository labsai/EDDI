package ai.labs.p2p;


public interface IPeerMessage {

    IPeer getPeer();
    PeerMessageType getMessageType();
    String getMessage();

    enum PeerMessageType {
        REGISTER(),
        REGISTERRESPONSE(),
        QUESTION(),
        QUESTIONRESPONSE()
    }

}
