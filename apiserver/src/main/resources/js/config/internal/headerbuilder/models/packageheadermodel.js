function PackageHeaderModel() {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        let model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_SAVE'), function () {
            this.observable.notify(new Event(this, 'Save'));
        }, this, 'iconimage_plus'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_CANCEL'), function () {
            this.observable.notify(new Event(this, 'Cancel'));
        }, this, 'iconimage_cross'));

        return model;
    }
}