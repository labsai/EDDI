function PackageEditHelper() {
    this.showEditDialogWithDefinition = function (model, definition) {
        let callback = function (success, event) {
            if (success) {
                for (key in event.map) {
                    model[key] = event.map[key];
                }
            }
        };

        let formRowCSS = 'editdialog_row';
        let formLeftCSS = 'editdialog_left';
        let formRightCSS = 'editdialog_right';
        let formElements = [];

        for (let key in definition) {
            let representation;

            if (definition[key].type.indexOf('eddi://') === 0) {
                let kBlockControlCSSClassBase = 'resourceuri';
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

        let dialogModel = new DialogControlModel(window.lang.convert("EDIT_LIFECYCLE_TEXT"), callback,
            window.lang.convert("OK_BUTTON"),
            window.lang.convert("CANCEL_BUTTON"),
            formElements,
            {dialogType: 'textFields'});

        let dialog = new DialogControl(dialogModel);

        dialog.showDialog();
    }
}