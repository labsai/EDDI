function UnknownExtensionControl(model) {
    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">' + window.lang.convert('UNKNOWN_EXTENSION') + '<b>' +
            model.type + '</b>' + '.' + '</div>';

        return representation;
    }

    this.getModel = function () {
        return model;
    }

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    }

    this.registerButtonEvents = function () {
        /** Preserve additional state classes. */
        for (let i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}