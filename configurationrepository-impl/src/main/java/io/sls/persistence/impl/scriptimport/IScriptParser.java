package io.sls.persistence.impl.scriptimport;

/**
 * User: jarisch
 * Date: 22.06.13
 * Time: 22:35
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
