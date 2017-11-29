function InputmatcherModel(extension) {
    this.makeDefaultJSON = function () {
        return {
            id: application.dataProvider.getNextIdForBehaviorRuleExtension(),
            values: {
                expressions: application.dataProvider.getInputMatcherDefaultExpression(),
                occurrence: application.dataProvider.getInputMatcherDefaultOccurrence()
            },
            selected: false,
            type: 'inputmatcher'
        };
    }

    if (typeof extension === "undefined" || extension === null) {
        extension = this.makeDefaultJSON();
    }

    this.id = extension.id;
    this.idPrefix = "inputmatcher_";
    this.CSSClassBase = "inputmatcher_control";
    this.selected = extension.selected;
    this.expressions = extension.values.expressions;
    this.occurrence = extension.values.occurrence;
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