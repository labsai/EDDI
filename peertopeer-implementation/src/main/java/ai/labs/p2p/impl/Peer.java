package ai.labs.p2p.impl;

import ai.labs.p2p.IClient;
import ai.labs.p2p.IPeer;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;


/**
 * @author rpi
 */
@Setter
public class Peer implements IPeer, Serializable {

    private String name;
    private String hostName;
    private int port;
    private IPeer.PeerAuthState authState = PeerAuthState.PUBLIC;
    private String publicKey;
    private IClient client;


    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPublicKey() {
        return publicKey;
    }

    @Override
    public IPeer.PeerAuthState getAuthState() {
        return authState;
    }

    @Override
    public String getHostName() {
        return this.hostName;
    }

    @Override
    public int getPort() {
        return this.port;
    }

    @Override
    public IClient getClient() {
        return client;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Peer peer = (Peer) o;
        return port == peer.port && Objects.equals(name, peer.name) && Objects.equals(hostName, peer.hostName) && Objects.equals(publicKey, peer.publicKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, hostName, port, publicKey);
    }
}
