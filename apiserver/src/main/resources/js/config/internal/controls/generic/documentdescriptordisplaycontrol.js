function DocumentDescriptorDisplayControl(descriptor) {
    var baseCSSClass = 'documentdescriptorui';
    var rowCSSPostfix = '_row';
    var keyCSSPostfix = '_key';
    var valueCSSPostfix = '_value';
    var headerCSSClassPostfix = '_header';
    var descriptionCSSClassPostfix = '_description';
    var descriptionTextPostfix = '_descriptiontext';

    var makeRow = function (key, value) {
        return '<div class="' + baseCSSClass + rowCSSPostfix + '"><span class="' + baseCSSClass + keyCSSPostfix + '">'
            + window.lang.convert(key) + '</span><span class"' + baseCSSClass + valueCSSPostfix + '">' + value + '</span></div>';
    }

    this.createRepresentation = function () {
        var representation = '<div class="' + baseCSSClass + '">\
            <div class="' + baseCSSClass + headerCSSClassPostfix + '"><span>\
            ' + window.lang.convert('DESCRIPTORUI_HEADER') + '\
            </span></div>\
            <div class="' + baseCSSClass + descriptionCSSClassPostfix + '">';

        representation += makeRow('DESCRIPTORUI_NAME', descriptor.name);
        representation += '<div class="' + baseCSSClass + rowCSSPostfix + '"><span class="' + baseCSSClass + keyCSSPostfix + '">'
            + window.lang.convert('DESCRIPTORUI_DESCRIPTION') + '</span><p id="' + baseCSSClass + descriptionTextPostfix
            + '" class="' + baseCSSClass + descriptionTextPostfix + '">'
            + descriptor.description + '</p></div>';

        representation += '</div>\
            </div>';

        return representation;
    }

    this.registerButtonEvents = function () {
        $('#' + baseCSSClass + descriptionTextPostfix).editable(function (value, settings) {
            if (value != descriptor.description) {
                var patch = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');

                patch.document = descriptor;
                var oldValue = patch.document.description;
                patch.document.description = value;

                $('#' + baseCSSClass + descriptionTextPostfix).showLoadingIndicator();
                application.dataProvider.patchDocumentDescription(application.dataProvider.dataProviderState.getActiveId(),
                    application.dataProvider.dataProviderState.getActiveVersion(),
                    patch,
                    function (httpCode) {
                        if (application.httpCodeManager.successfulRequest(httpCode)) {
                            $('#' + baseCSSClass + descriptionTextPostfix).text(value);
                        } else {
                            $('#' + baseCSSClass + descriptionTextPostfix).text(oldValue);
                        }

                        $('#' + baseCSSClass + descriptionTextPostfix).hideLoadingIndicator();
                    });
            }

            return application.bindingManager.bindFromString(value);
        }, {
            tooltip: window.lang.convert('EDITABLE_TOOLTIP'),
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
}