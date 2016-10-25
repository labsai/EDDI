package io.sls.persistence.impl.scriptimport;

/**
 * @author ginccc
 */
public interface IScriptParser {
    Group nextGroup();

    boolean hasMoreGroups();

    interface Group {
        boolean hasMoreInteractions();

        Interaction nextInteraction();

        String getGroupName();
    }

    interface Interaction {
        String getValue(String key, String stopKey);
    }
}
