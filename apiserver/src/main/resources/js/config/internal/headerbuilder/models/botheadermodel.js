function BotHeaderModel() {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        let model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_SAVE'), function () {
            this.observable.notify(new Event(this, 'Save'));
        }, this, 'iconimage_plus'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_CANCEL'), function () {
            this.observable.notify(new Event(this, 'Cancel'));
        }, this, 'iconimage_cross'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_MONITOR'), function () {
            this.observable.notify(new Event(this, 'Monitor'));
        }, this));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_TEST'), function () {
            this.observable.notify(new Event(this, 'Test'));
        }, this));

        let tmp = new HeaderElement(window.lang.convert('CONTEXT_DEPLOY'), function () {
            this.observable.notify(new Event(this, 'Deploy'));
        }, this);

        tmp.action = function () {
            this.observable.notify(new Event(tmp, 'Deploy'));
        };

        model.push(tmp);

        let tmp2 = new HeaderElement(window.lang.convert('CONTEXT_TEST_DEPLOY'), function () {
            this.observable.notify(new Event(this, 'TestDeploy'));
        }, this);

        tmp2.action = function () {
            this.observable.notify(new Event(tmp2, 'TestDeploy'));
        };

        model.push(tmp2);

        return model;
    }
}