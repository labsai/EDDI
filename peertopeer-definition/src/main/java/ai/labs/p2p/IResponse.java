package ai.labs.p2p;

/**
 * @author rpi
 */
public interface IResponse {

    enum PeerErrorCode {
        OK(),
        NOT_FOUND(),
        NOT_AUTHENTICATED(),
        NO_OUTPUT();
    }

    String getOutput();
    PeerErrorCode getErrorCode();
}
