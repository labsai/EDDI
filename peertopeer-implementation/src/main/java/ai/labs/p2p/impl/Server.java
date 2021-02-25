package ai.labs.p2p.impl;

import ai.labs.p2p.IClient;
import ai.labs.p2p.IPeer;
import ai.labs.p2p.IServer;

import java.util.List;


/**
 * @author rpi
 */
public class Server implements IServer {

    @Override
    public void init() {
        // check if private keys exist
        // if not create private key

    }

    @Override
    public List<IPeer> getAvailablePeers() {
        return null;
    }

    @Override
    public IClient connectToPeer(IPeer peer) {
        return null;
    }
}
