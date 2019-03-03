package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.rest.IContentTypeProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */

public class ContentTypeProvider implements IContentTypeProvider {
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    private final Map<String, String> mimeTypes;

    public ContentTypeProvider() {
        mimeTypes = new HashMap<>();
        mimeTypes.put("txt", "text/plain");
        mimeTypes.put("html", "text/html");
        mimeTypes.put("css", "text/css");
        mimeTypes.put("js", "text/javascript");
        mimeTypes.put("gif", "image/gif");
        mimeTypes.put("json", "application/json");
        mimeTypes.put("jpg", "image/jpeg");
        mimeTypes.put("png", "image/png");
        mimeTypes.put("ico", "image/x-icon");
        mimeTypes.put("ttf", "application/x-font-ttf");
        mimeTypes.put("mp3", "audio/mpeg");
        mimeTypes.put("pdf", "application/pdf");
        mimeTypes.put("svg", "image/svg+xml");
    }

    @Override
    public String getContentTypeByExtension(String extension) {
        if (extension == null) {
            extension = "";
        }

        String mimeType = mimeTypes.get(extension);
        if (mimeType == null) {
            mimeType = DEFAULT_MIME_TYPE;
        }

        return mimeType;
    }
}
