package io.sls.utilities;

/**
 * @author ginccc
 */
public class HtmlUtilities {
    private HtmlUtilities() {
        //utility class
    }

    public static String wrapInJavascriptStatement(String javascript) {
        StringBuilder jsScriptTag = new StringBuilder();
        jsScriptTag.append("<script language=\"JavaScript\" type=\"text/javascript\">\n");
        jsScriptTag.append(javascript).append("\n");
        jsScriptTag.append("</script>\n");
        return jsScriptTag.toString();
    }
}
