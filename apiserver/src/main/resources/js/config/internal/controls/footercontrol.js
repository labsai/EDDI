function FooterControl(model, CSSClassBaseCtr) {
    var footerControlModel = model;
    var CSSClassBase = CSSClassBaseCtr;
    this.observable = new Observable();

    this.createRepresentation = function () {
        var representation = '';

        representation += '<div class="' + CSSClassBase + '" id="' + footerControlModel.idPrefix + footerControlModel.id + '"></div>';

        return representation;
    }

    this.registerButtonEvents = function () {
        var that = this;

        $('#' + footerControlModel.idPrefix + footerControlModel.id).click(function () {
            that.observable.notify(new Event(footerControlModel, 'FooterClicked'));

            return false;
        });
    }

    this.getModel = function () {
        return footerControlModel;
    }
}

function FooterControlModel(id, idPrefix, showOpenOnly) {
    this.id = id;
    this.idPrefix = idPrefix;
    this.showOpenOnly = showOpenOnly;
}