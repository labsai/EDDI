package io.sls.utilities;

/**
 * Created with IntelliJ IDEA.
 * User: jarisch
 * Date: 17.09.12
 * Time: 11:13
 * To change this template use File | Settings | File Templates.
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
