package ai.labs.eddi.utils;

public class LifecycleUtilities {
    public static String createComponentKey(String workflowId, Integer packageVersion, Integer packageIndex) {
        return workflowId + ":" + packageVersion + ":" + packageIndex;
    }
}
