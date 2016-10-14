function MonitorHeaderModel() {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        var model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_CREATE_TEST_CASE'), function () {
            this.observable.notify(new Event(this, 'CreateTestCase'));
        }, this, 'iconimage_plus'));

        return model;
    }
}