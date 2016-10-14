package io.sls.core.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User: jarisch
 * Date: 10.08.12
 * Time: 12:13
 */
public class URIUtilities {
    private static Logger logger = LoggerFactory.getLogger(URIUtilities.class);

    public static Map<String, List<String>> getUrlParameters(String url) throws UnsupportedEncodingException {
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        String[] urlParts = url.split("\\?");
        if (urlParts.length > 1) {
            String query = urlParts[1];
            for (String param : query.split("&")) {
                String pair[] = param.split("=");
                String key = URLDecoder.decode(pair[0], "UTF-8");
                String value = "";
                if (pair.length > 1) {
                    value = URLDecoder.decode(pair[1], "UTF-8");
                }
                List<String> values = params.get(key);
                if (values == null) {
                    values = new ArrayList<String>();
                    params.put(key, values);
                }
                values.add(value);
            }
        }
        return params;
    }

    public static ResourceId extractResourceId(URI uri) {
        String uriString = uri.toString();
        if (uriString.endsWith("/")) {
            uriString = uriString.substring(0, uriString.length() - 1);
        }

        String[] split = uriString.split("/");
        String id = split[split.length - 1].split("\\?")[0];

        Integer version = Integer.parseInt(uriString.split("\\?")[1].split("=")[1]);

        return new ResourceId(id, version);
    }

    public static class ResourceId {
        private String id;
        private Integer version;

        public ResourceId(String id, Integer version) {
            this.id = id;
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }
    }

    public static List<URL> listToURL(List<String> urlStrings) {
        List<URL> urls = new ArrayList<URL>();
        for (String urlString : urlStrings) {
            try {
                urls.add(URI.create(urlString).toURL());
            } catch (MalformedURLException e) {
                logger.error(e.getMessage(), e);
            }
        }

        return urls;
    }
}
