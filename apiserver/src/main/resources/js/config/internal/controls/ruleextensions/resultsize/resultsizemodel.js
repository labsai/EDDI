function ResultSizeModel(extension) {
    this.makeDefaultJSON = function () {
        return {
            id: application.dataProvider.getNextIdForBehaviorRuleExtension(),
            values: {
                equal: -1,
                min: application.dataProvider.getResultSizeDefaultMin(),
                max: application.dataProvider.getResultSizeDefaultMax()
            },
            selected: false,
            type: 'resultSize'
        };
    }

    if (typeof extension === "undefined" || extension === null) {
        extension = this.makeDefaultJSON();
    }

    this.id = extension.id;
    this.idPrefix = "resultsize_";
    this.CSSClassBase = "resultsize_control";
    this.selected = extension.selected;
    this.equal = extension.values.equal;
    this.min = extension.values.min;
    this.max = extension.values.max;
    this.backingData = extension;

    var footerControls = [];
    var footerModel = new FooterControlModel(this.id, this.idPrefix + 'footer_', false);
    footerControls.push(new FooterControl(footerModel, 'footercontrol_small'));

    this.footerControls = footerControls;

    this.additionalClasses = [];

    this.addClass = function (className) {
        if (this.additionalClasses.indexOf() == -1) {
            this.additionalClasses.push(className);
        }
    }

    this.removeClass = function (className) {
        try {
            this.additionalClasses.removeElement(className);
        } catch (ex) {
            if (ex instanceof InconsistentStateDetectedException) {
                console.log(ex.message);
            } else {
                throw ex;
            }
        }
    }
}