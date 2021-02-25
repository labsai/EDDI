package ai.labs.p2p;


/**
 * @author rpi
 */
public interface IClient {

    IPeer getPeer();
    IResponse sendMessage(IRequest request);

}
