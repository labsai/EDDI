package ai.labs.eddi.utils;

public class LifecycleUtilities {
    public static String createComponentKey(String workflowId, Integer workflowVersion, Integer stepIndex) {
        return workflowId + ":" + workflowVersion + ":" + stepIndex;
    }
}
