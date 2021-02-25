package ai.labs.p2p;

/**
 * @author rpi
 */
public interface IPeer {

    String getName();
    String getPublicKey();
    PeerAuthState getAuthState();

    enum PeerAuthState {
        PUBLIC(),
        PRIVATE();
    }

}
