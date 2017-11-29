function GroupControlBuilder() {
    var createBackingUneditableGroupControl = function (groupControlModel, cssClass, isConnectedToActionHandler) {
        var gc = new GroupControl(groupControlModel, cssClass);

        gc.observable.addObserver(application.contentBuilder.observer);

        if (isConnectedToActionHandler) {
            gc.observable.addObserver(application.actionHandler.observer);
        }

        return gc;
    }

    var createBackingUneditableGroupControlModel = function (id, idPrefix, name, opened, isDeleteAble, hasAddButton) {
        var footerControls = [];

        if (isDeleteAble) {
            var footerModel = new FooterControlModel(id, idPrefix + 'footer_', true);
            var footerControl = new FooterControl(footerModel, 'footercontrol');

            footerControls.push(footerControl);
        }

        return new GroupControlModel(id, idPrefix, name,
            footerControls, false, opened, null, false, isDeleteAble, hasAddButton);
    }

    this.createStandardUneditableGroupControl = function (id, idPrefix, name, opened, isDeleteAble, hasAddButton, cssClass, isConnectedToActionHandler) {
        var model = createBackingUneditableGroupControlModel(id, idPrefix, name, opened, isDeleteAble, hasAddButton);
        var control = createBackingUneditableGroupControl(model, cssClass, isConnectedToActionHandler);

        return control;
    }
}