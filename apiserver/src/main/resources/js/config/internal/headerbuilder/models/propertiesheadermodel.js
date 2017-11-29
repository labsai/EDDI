function PropertiesHeaderModel() {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        var model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_ADD'), function () {
            this.observable.notify(new Event(this, 'AddSelected'));
        }, this, 'iconimage_plus'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_DELETE'), function () {
            this.observable.notify(new Event(this, 'DeleteSelected'));
        }, this, 'iconimage_cross'));

        return model;
    }
}