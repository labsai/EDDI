package io.sls.persistence.impl.scriptimport;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 22.06.13
 * Time: 22:38
 */
public class ScriptParser implements IScriptParser {
    private String script;
    private final String interactionDelim;
    private String[] groups;
    private int counter = 0;

    public ScriptParser(String script, String groupDelim, String interactionDelim) {
        this.script = script;
        this.interactionDelim = interactionDelim;
        groups = this.script.split(groupDelim);
        counter++;
    }

    @Override
    public Group nextGroup() {
        return new Group(groups[counter++], interactionDelim);
    }

    @Override
    public boolean hasMoreGroups() {
        return groups.length > counter;
    }

    public static class Group implements IScriptParser.Group {
        private final String groupName;
        private final String scriptGroup;
        private String[] interactions;
        private int counter = 0;

        public Group(String scriptGroup, String interactionDelim) {
            this.scriptGroup = scriptGroup;
            this.interactions = this.scriptGroup.split(interactionDelim);
            if(interactions.length > 0){
                this.groupName = interactions[0];
                counter++;
            }else {
                this.groupName = "";
            }
        }

        @Override
        public String getGroupName() {
            return groupName;
        }

        @Override
        public boolean hasMoreInteractions() {
            return interactions.length > counter;
        }

        @Override
        public IScriptParser.Interaction nextInteraction() {
            return new Interaction(interactions[counter++]);
        }
    }

    public static class Interaction implements IScriptParser.Interaction {
        private final String scriptPart;

        public Interaction(String scriptPart) {
            this.scriptPart = scriptPart;
        }

        @Override
        public String getValue(String startKey, String stopKey) {
            int startKeyPos = scriptPart.indexOf(startKey);
            if (startKeyPos == -1) {
                return null;
            }

            int stopKeyPos = stopKey != null ? scriptPart.indexOf(stopKey, startKeyPos) : -1;
            int valueBegin = startKeyPos + startKey.length();
            if (stopKeyPos > -1) {
                return scriptPart.substring(valueBegin, stopKeyPos);
            } else {
                return scriptPart.substring(valueBegin);
            }
        }
    }
}
