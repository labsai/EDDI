package ai.labs.p2p;

/**
 * @author rpi
 */
public interface IPeer {

    String getName();
    String getPublicKey();
    PeerAuthState getAuthState();
    String getHostName();
    int getPort();
    IClient getClient();


    enum PeerAuthState {
        PUBLIC(),
        PRIVATE();
    }

}
