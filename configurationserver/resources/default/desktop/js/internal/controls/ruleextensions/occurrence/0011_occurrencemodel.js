function OccurrenceModel(extension) {
    this.makeDefaultJSON = function () {
        return {
            id:application.dataProvider.getNextIdForBehaviorRuleExtension(),
            values:{
                maxOccurrence:application.dataProvider.getMaxOccurrenceDefaultContext(),
                behaviorRuleName:application.dataProvider.getOccurrenceDefaultRuleName()
            },
            selected:false,
            type:'occurrence'
        };
    }

    if (typeof extension === "undefined" || extension === null) {
        extension = this.makeDefaultJSON();
    }

    this.id = extension.id;
    this.idPrefix = "occurrence_";
    this.CSSClassBase = "occurrence_control";
    this.selected = extension.selected;
    this.behaviorRuleName = extension.values.behaviorRuleName;
    this.maxOccurrence = extension.values.maxOccurrence;
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