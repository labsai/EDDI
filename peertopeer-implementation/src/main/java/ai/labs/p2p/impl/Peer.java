package ai.labs.p2p.impl;

import ai.labs.p2p.IPeer;


/**
 * @author rpi
 */
public class Peer implements IPeer {
    @Override
    public String getName() {
        return null;
    }

    @Override
    public String getPublicKey() {
        return null;
    }

    @Override
    public IPeer.PeerAuthState getAuthState() {
        return PeerAuthState.PUBLIC;
    }

}
