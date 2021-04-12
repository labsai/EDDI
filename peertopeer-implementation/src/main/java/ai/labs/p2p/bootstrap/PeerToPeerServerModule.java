package ai.labs.p2p.bootstrap;

import ai.labs.p2p.IServer;
import ai.labs.p2p.impl.Server;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

import java.io.InputStream;

/**
 * @author rpi
 */
public class PeerToPeerServerModule extends AbstractBaseModule {

    public PeerToPeerServerModule(InputStream...configfile) {
        super(configfile);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);
        bind(IServer.class).to(Server.class).in(Scopes.SINGLETON);
        super.configure();
    }
}
