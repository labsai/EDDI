function NegationControl(model) {
    var leftSidePostfix = '_left';
    var rightSidePostfix = '_right';
    var operatorIdPrefix = 'operator_';
    var selectIdPrefix = 'select_';
    var footerCSSClassPostfix = '_footer';
    var sortableInnerMinHeight = 80;

    var instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteExtension'));
                break;
            case 'HasBeenRemoved':
                var index = instance.getModel().children.indexOf(event.sender);

                if (index != -1) {
                    var item = instance.getModel().children.splice(index, 1)[0];

                    item.observable.removeObserver(instance.observer);
                } else {
                    console.log('Element was not found');
                }
            case 'SizeChanged':
                instance.adjustHeight();
                break;
            case 'SortUpdatePackageInner':
                break;
        }
    });

    for (var i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    /** If any children are resize-able, receive their resize-events. */
    for (var i = 0; i < model.children.length; ++i) {
        if (model.children[i].hasOwnProperty('observable')) {
            model.children[i].observable.addObserver(this.observer);
        }
    }

    this.createRepresentation = function () {
        var representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">';

        representation += '<div id="' + operatorIdPrefix + model.idPrefix + model.id + '" class="' + model.CSSClassBase + leftSidePostfix + '">' +
            model.operator + '</div>\
            <div id="' + application.configuration.referencePrefix + model.idPrefix + model.id + '" class="' + model.CSSClassBase + rightSidePostfix + '">';

        for (var i = 0; i < model.children.length; ++i) {
            representation += model.children[i].createRepresentation();
        }

        representation += '</div><div class="clear"></div>';

        representation += '<div class="' + model.CSSClassBase + footerCSSClassPostfix + '">';

        for (var i = 0; i < model.footerControls.length; ++i) {
            representation += model.footerControls[i].createRepresentation();
        }

        representation += '<div class="clear"></div></div></div>';

        return representation;
    }

    this.getModel = function () {
        return model;
    }

    this.getHeight = function () {
        var totalHeight = 0;
        for (var i = 0; i < model.children.length; ++i) {
            totalHeight += model.children[i].getHeight();
        }

        return totalHeight + 8 * 2;
        /** + padding * 2 */
    }

    this.adjustHeight = function () {
        /** NOTE: Don't include margin in height, because it'd add previously added margin-top's. */
        var ownHeight = $('#' + operatorIdPrefix + model.idPrefix + model.id).outerHeight();
        var newVal = this.getHeight() / 2 - ownHeight / 2;

        var minHeight = sortableInnerMinHeight - ownHeight / 2;

        if (newVal < minHeight / 2) {
            newVal = minHeight / 2;
        }

        $('#' + operatorIdPrefix + model.idPrefix + model.id).css('margin-top', newVal);

        this.observable.notify(new Event(this, 'SizeChanged'));
    }

    this.registerButtonEvents = function () {
        var that = this;
        $('#' + selectIdPrefix + model.idPrefix + model.id).change(function () {
            that.observable.notify(new Event(that, 'OperatorChanged'));
        });

        this.adjustHeight();

        for (var i = 0; i < model.children.length; ++i) {
            model.children[i].registerButtonEvents();
        }

        for (var i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for (var i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}