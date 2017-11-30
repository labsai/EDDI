function DataProviderState() {
    let activeId = application.url.getCurrentId();
    let activeVersion;

    if (typeof $.url.parse(window.location.href).params !== 'undefined') {
        activeVersion = decodeURIComponent($.url.parse(window.location.href).params['version'])
    }

    this.getActiveId = function () {
        return activeId;
    };

    this.setActiveId = function (value) {
        activeId = value;
    };

    this.getActiveVersion = function () {
        return activeVersion;
    };

    this.setActiveVersion = function (value) {
        activeVersion = value;
    };

    /* RegularDictionaryConfiguration */
    let regularDictionaryConfigurationLanguage;

    this.getRegularDictionaryConfigurationLanguage = function () {
        return regularDictionaryConfigurationLanguage;
    };

    this.setRegularDictionaryConfigurationLanguage = function (value) {
        regularDictionaryConfigurationLanguage = value;
    }
}