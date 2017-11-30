function LanguageSelectorControl() {
    let cssClassBase = 'language_selector';
    let dropdownPostfix = '_dropdown';

    this.createRepresentation = function () {
        return '<div class="' + cssClassBase + dropdownPostfix + '"></div>';
    };

    this.registerButtonEvents = function () {
        $('.' + cssClassBase + dropdownPostfix).dropdown({
            value: application.configuration.languageKey + ' / ' + application.configuration.locationKey,
            possibleValues: application.configuration.languageSet,
            valueChanged: function (value, oldValue) {
                let urlObj = $.url.parse(window.location.href);

                let values = value.split(' / ');

                delete urlObj.query;
                delete urlObj.relative;
                delete urlObj.source;

                if (typeof urlObj.params === 'undefined') {
                    urlObj.params = {};
                }

                urlObj.params["lang"] = values[0];
                urlObj.params["loc"] = values[1];

                window.location.assign($.url.build(urlObj));
            }
        });
    };

    this.getModel = function () {
        return {};
    }
}