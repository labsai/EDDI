function DocumentDescriptionsHeaderModel(page) {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        var model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_ADD'), function () {
            this.observable.notify(new Event(this, 'Add'));
        }, this, 'iconimage_plus'));

        return model;
    }
}