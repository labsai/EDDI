package ai.labs.lifecycle;

/**
 * @author ginccc
 */
public interface ILifecycleTaskProvider {

    String getId();

    ILifecycleTask createLifecycleTask();
}
