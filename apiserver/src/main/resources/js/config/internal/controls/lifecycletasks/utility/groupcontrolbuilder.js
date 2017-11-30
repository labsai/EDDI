function GroupControlBuilder() {
    let createBackingUneditableGroupControl = function (groupControlModel, cssClass, isConnectedToActionHandler) {
        let gc = new GroupControl(groupControlModel, cssClass);

        gc.observable.addObserver(application.contentBuilder.observer);

        if (isConnectedToActionHandler) {
            gc.observable.addObserver(application.actionHandler.observer);
        }

        return gc;
    };

    let createBackingUneditableGroupControlModel = function (id, idPrefix, name, opened, isDeleteAble, hasAddButton) {
        let footerControls = [];

        if (isDeleteAble) {
            let footerModel = new FooterControlModel(id, idPrefix + 'footer_', true);
            let footerControl = new FooterControl(footerModel, 'footercontrol');

            footerControls.push(footerControl);
        }

        return new GroupControlModel(id, idPrefix, name,
            footerControls, false, opened, null, false, isDeleteAble, hasAddButton);
    };

    this.createStandardUneditableGroupControl = function (id, idPrefix, name, opened, isDeleteAble, hasAddButton, cssClass, isConnectedToActionHandler) {
        let model = createBackingUneditableGroupControlModel(id, idPrefix, name, opened, isDeleteAble, hasAddButton);
        return createBackingUneditableGroupControl(model, cssClass, isConnectedToActionHandler);
    }
}