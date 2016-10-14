package io.sls.memory;

/**
 * Created by IntelliJ IDEA.
 * User: jarisch
 * Date: 09.02.2012
 * Time: 22:21:04
 */
public interface IReversibility {
    void undo();

    void redo();

    boolean isUndoAvailable();

    boolean isRedoAvailable();
}
