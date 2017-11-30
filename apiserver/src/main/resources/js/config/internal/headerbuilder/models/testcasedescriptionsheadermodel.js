function TestCaseDescriptionsHeaderModel() {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        let model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_RUN_ALL'), function () {
            this.observable.notify(new Event(this, 'RunAll'));
        }, this));

        return model;
    }
}
