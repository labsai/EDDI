package io.sls.botmarklet;

import io.sls.core.utilities.PathUtilities;
import io.sls.utilities.FileUtilities;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BotmarkletResources {
    private List<URL> jsResources;
    private List<URL> cssResources;
    private String rootWebDir;

    public BotmarkletResources(/*String rootWebDir*/) {
        this.rootWebDir = rootWebDir;
    }

    public List<URL> getJsResources() {
        return jsResources;
    }

    public void setJsResources(List<URL> jsResources) {
        this.jsResources = jsResources;
    }

    public List<URL> getCssResources() {
        return cssResources;
    }

    public void setCssResources(List<URL> cssResources) {
        this.cssResources = cssResources;
    }

    public void createResources(String baseURL) throws MalformedURLException {
        cssResources = new LinkedList<URL>();
        jsResources = new LinkedList<URL>();

        String snippetPath = FileUtilities.buildPath(rootWebDir, "snippet", "desktop");

        String cssDirPath = FileUtilities.buildPath(snippetPath, "css", "excluded");
        List<String> cssPaths = new LinkedList<String>();
        FileUtilities.extractRelativePaths(cssPaths, cssDirPath, cssDirPath);
        for (String cssPath : cssPaths) {
            cssResources.add(new URL(PathUtilities.buildPath('/', false, baseURL, "text", "snippet", "desktop", "css", "excluded", cssPath)));
        }

        String jsDirPath = FileUtilities.buildPath(snippetPath, "js", "excluded");
        List<String> jsPaths = new LinkedList<String>();
        FileUtilities.extractRelativePaths(jsPaths, jsDirPath, jsDirPath);
        for (String jsPath : jsPaths) {
            jsResources.add(new URL(PathUtilities.buildPath('/', false, baseURL, "text", "snippet", "desktop", "js", "excluded", jsPath)));
        }
    }
}
