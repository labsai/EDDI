function ConnectorModel(extension) {
    this.makeDefaultJSON = function () {
        return {
            id: application.dataProvider.getNextIdForBehaviorRuleExtension(),
            values: {
                operator: 'AND'
            },
            selected: false,
            children: [],
            type: 'connector'
        };
    };

    if (typeof extension === "undefined" || extension === null) {
        extension = this.makeDefaultJSON();
    }

    this.id = extension.id;
    this.idPrefix = "connector_";
    this.CSSClassBase = "connector_control";
    this.selected = extension.selected;
    this.operator = extension.values.operator;
    this.backingData = extension;

    let footerControls = [];
    let footerModel = new FooterControlModel(this.id, this.idPrefix + 'footer_', false);
    footerControls.push(new FooterControl(footerModel, 'footercontrol_small'));

    this.footerControls = footerControls;

    this.removeChild = function (child) {
        if (this.hasOwnProperty('children')) {
            this.children.removeElement(child);
        }
    };

    this.additionalClasses = [];

    this.addClass = function (className) {
        if (this.additionalClasses.indexOf() === -1) {
            this.additionalClasses.push(className);
        }
    };

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