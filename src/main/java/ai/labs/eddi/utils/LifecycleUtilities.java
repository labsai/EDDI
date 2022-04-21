package ai.labs.eddi.utils;

public class LifecycleUtilities {
    public static String createComponentKey(String packageId, Integer packageVersion, Integer packageIndex) {
        return packageId + ":" + packageVersion + ":" + packageIndex;
    }
}
