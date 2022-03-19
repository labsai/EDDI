package ai.labs.eddi.modules.nlp.model;

/**
 * @author ginccc
 */
public class FoundUnknown extends FoundWord {
    public FoundUnknown(Unknown unknown) {
        super(unknown, false, 0.0);
    }
}
