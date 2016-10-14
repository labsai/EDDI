function PackageEditHelper() {
    this.showEditDialogWithDefinition = function (model, definition) {
        var callback = function (success, event) {
            if (success) {
                for (key in event.map) {
                    model[key] = event.map[key];
                }
            }
        }

        var formRowCSS = 'editdialog_row';
        var formLeftCSS = 'editdialog_left';
        var formRightCSS = 'editdialog_right';
        var formElements = [];

        for (var key in definition) {
            var representation;

            if (definition[key].type.indexOf('resource://') === 0) {
                var kBlockControlCSSClassBase = 'resourceuri';
                representation = new ResourceURIFormElement(kBlockControlCSSClassBase, false, key, definition[key].type,
                                                            definition[key].displayKey, model[key]);

                representation.observable.addObserver(application.actionHandler.observer);
            } else {
                representation = '<div class="' + formRowCSS + '"><div class="' + formLeftCSS + '">'
                    + window.lang.convert(definition[key].displayKey) + '</div>' +
                    '<input class="' + formRightCSS + '" name="' + key + '" type="text" value="' + model[key] + '"></input>' +
                    '<div class="clear"></div></div>';
            }

            formElements.push(representation);
        }

        var dialogModel = new DialogControlModel(window.lang.convert("EDIT_LIFECYCLE_TEXT"), callback,
            window.lang.convert("OK_BUTTON"),
            window.lang.convert("CANCEL_BUTTON"),
            formElements,
            {dialogType:'textFields'});

        var dialog = new DialogControl(dialogModel);

        dialog.showDialog();
    }
}