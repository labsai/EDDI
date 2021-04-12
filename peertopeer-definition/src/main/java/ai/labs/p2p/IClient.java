package ai.labs.p2p;


import java.security.PrivateKey;

/**
 * @author rpi
 */
public interface IClient {

    String sendMessage(IPeerMessage request, IPeer peer, PrivateKey privateKey);

}
