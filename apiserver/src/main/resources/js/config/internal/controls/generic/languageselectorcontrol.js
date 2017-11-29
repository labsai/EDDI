function LanguageSelectorControl() {
    var cssClassBase = 'language_selector';
    var dropdownPostfix = '_dropdown';

    this.createRepresentation = function () {
        var representation = '<div class="' + cssClassBase + dropdownPostfix + '"></div>';

        return representation;
    }

    this.registerButtonEvents = function () {
        $('.' + cssClassBase + dropdownPostfix).dropdown({
            value: application.configuration.languageKey + ' / ' + application.configuration.locationKey,
            possibleValues: application.configuration.languageSet,
            valueChanged: function (value, oldValue) {
                var urlObj = $.url.parse(window.location.href);

                var values = value.split(' / ');

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
    }

    this.getModel = function () {
        return {};
    }
}