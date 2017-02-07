package ai.labs.server;

import javax.servlet.Servlet;
import java.util.HashMap;
import java.util.Map;

public class HttpServletHolder {
    private Servlet servlet;
    private final String path;
    private Map<String, String> initParameter = new HashMap<>();

    public HttpServletHolder(Servlet servlet, String path) {
        this.servlet = servlet;
        this.path = path;
    }

    public void setServlet(Servlet servlet) {
        this.servlet = servlet;
    }

    public Servlet getServlet() {
        return servlet;
    }

    public Map<String, String> getInitParameter() {
        return initParameter;
    }

    public String getPath() {
        return path;
    }

    public void addInitParameter(String key, String value) {
        initParameter.put(key, value);
    }
}
