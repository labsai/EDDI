function OutputReferenceModel(extension) {
    this.makeDefaultJSON = function () {
        return {
            id: application.dataProvider.getNextIdForBehaviorRuleExtension(),
            values: {
                inputValue: application.dataProvider.getOutputReferenceDefaultInputValue(),
                filter: application.dataProvider.getOutputReferenceDefaultFilter(),
                sessionValue: ""
            },
            selected: false,
            type: 'outputReference'
        };
    }

    if (typeof extension === "undefined" || extension === null) {
        extension = this.makeDefaultJSON();
    }

    this.id = extension.id;
    this.idPrefix = "outputreference_";
    this.CSSClassBase = "outputreference_control";
    this.selected = extension.selected;
    this.inputValue = extension.values.inputValue;
    this.filter = extension.values.filter;
    this.backingData = extension;

    let footerControls = [];
    let footerModel = new FooterControlModel(this.id, this.idPrefix + 'footer_', false);
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