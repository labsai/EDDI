package io.sls.resources.rest.patch;

/**
 * User: jarisch
 * Date: 15.08.12
 * Time: 11:17
 */
public class PatchInstruction<T> {
    public enum PatchOperation {
        SET,
        DELETE
    }

    private PatchOperation operation;
    private T document;

    public PatchOperation getOperation() {
        return operation;
    }

    public void setOperation(PatchOperation operation) {
        this.operation = operation;
    }

    public T getDocument() {
        return document;
    }

    public void setDocument(T document) {
        this.document = document;
    }
}
