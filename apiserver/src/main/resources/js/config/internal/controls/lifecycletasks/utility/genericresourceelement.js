function GenericResourceElement(cssClassBase, key, displayKey, currentValue, modelOrigin) {
    var id = application.dataProvider.getNextIdGlobal();
    var valuePostfix = '_value';
    var keyPostfix = '_key';
    var rowPostfix = '_row';

    var instance = this;
    this.observable = new Observable();

    this.createRepresentation = function () {
        var representation = '<div class="' + cssClassBase + rowPostfix + '"><div class="' + cssClassBase + keyPostfix + '">'
            + window.lang.convert(displayKey) + '</div>' +
            '<div id="' + cssClassBase + valuePostfix + id + '" class="' + cssClassBase + valuePostfix + '">' + currentValue + '</div>' +
            '<div class="clear"></div></div>';

        return representation;
    }

    this.registerButtonEvents = function () {
        $('#' + cssClassBase + valuePostfix + id).editable(function (value, settings) {
            modelOrigin[key] = value;
            instance.observable.notify(new Event(this, 'UpdatedModel'));

            return application.bindingManager.bindFromString(value);
        }, {
            tooltip: window.lang.convert('CONFIG_VALUE_PLACEHOLDER'),
            type: 'text',
            style: 'inherit',
            submit: window.lang.convert('EDITABLE_OK'),
            cancel: window.lang.convert('EDITABLE_CANCEL'),
            placeholder: window.lang.convert('EDITABLE_PLACEHOLDER'),
            data: function (value, settings) {
                /** Unescape innerHtml before editing. */
                return application.bindingManager.bindToString(value);
            }
        });
    }

    this.getModel = function () {
        return {
            id: id,
            idPrefix: cssClassBase
        }
    }
}