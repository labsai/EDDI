package io.sls.faces.html.model;

import io.sls.faces.model.Face;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 18.01.13
 * Time: 21:52
 */
public class HtmlFace extends Face {
    private String host;
    private String uiLanguage;
    private String uiLocation;
    private String uiIdentifier;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getUiLanguage() {
        return uiLanguage;
    }

    public void setUiLanguage(String uiLanguage) {
        this.uiLanguage = uiLanguage;
    }

    public String getUiLocation() {
        return uiLocation;
    }

    public void setUiLocation(String uiLocation) {
        this.uiLocation = uiLocation;
    }

    public String getUiIdentifier() {
        return uiIdentifier;
    }

    public void setUiIdentifier(String uiIdentifier) {
        this.uiIdentifier = uiIdentifier;
    }
}
