package ai.labs.server;

/**
 * @author ginccc
 */
public interface IServerRuntime {
    void startup(IStartupCompleteListener startupCompleteListener);

    interface IStartupCompleteListener {
        void onComplete() throws Exception;
    }
}
