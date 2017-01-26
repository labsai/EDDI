package ai.labs.memory;

/**
 * @author ginccc
 */
public interface IReversibility {
    void undo();

    void redo();

    boolean isUndoAvailable();

    boolean isRedoAvailable();
}
