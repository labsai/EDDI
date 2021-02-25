package ai.labs.p2p;

import java.util.List;

/**
 * @author rpi
 */
public interface IServer {

    void init();
    List<IPeer> getAvailablePeers();
    IClient connectToPeer(IPeer peer);
}
