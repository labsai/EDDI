function ConnectorControl(model) {
    let leftSidePostfix = '_left';
    let rightSidePostfix = '_right';
    let operatorIdPrefix = 'operator_';
    let selectPostfix = '_select';
    let footerCSSClassPostfix = '_footer';
    let sortableInnerMinHeight = 80;

    let instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteExtension'));
                break;
            case 'HasBeenRemoved':
                let index = instance.getModel().children.indexOf(event.sender);

                if (index !== -1) {
                    let item = instance.getModel().children.splice(index, 1)[0];

                    item.observable.removeObserver(instance.observer);
                } else {
                    console.log('Element was not found');
                }
                break;
            case 'SizeChanged':
                instance.adjustHeight();
                break;
            case 'SortUpdatePackageInner':
                break;
        }
    });

    for (let i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    /** If any children are resize-able, receive their resize-events. */
    for (let i = 0; i < model.children.length; ++i) {
        if (model.children[i].hasOwnProperty('observable')) {
            model.children[i].observable.addObserver(this.observer);
        }
    }

    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">';

        representation += '<div id="' + operatorIdPrefix + model.idPrefix + model.id + '" class="' + model.CSSClassBase + leftSidePostfix + '">' +
            '<div id="' + model.idPrefix + model.id + selectPostfix + '" class="' + model.CSSClassBase + selectPostfix + '"></div></div>\
            <div id="' + application.configuration.referencePrefix + model.idPrefix + model.id + '" class="' + model.CSSClassBase + rightSidePostfix + '">';

        for (let i = 0; i < model.children.length; ++i) {
            representation += model.children[i].createRepresentation();
        }

        representation += '</div><div class="clear"></div>';

        representation += '<div class="' + model.CSSClassBase + footerCSSClassPostfix + '">';

        for (let i = 0; i < model.footerControls.length; ++i) {
            representation += model.footerControls[i].createRepresentation();
        }

        representation += '<div class="clear"></div></div></div>';

        return representation;
    };

    this.getModel = function () {
        return model;
    };

    this.getHeight = function () {
        let totalHeight = 0;
        for (let i = 0; i < model.children.length; ++i) {
            totalHeight += model.children[i].getHeight();
        }

        return totalHeight + 8 * 2;
        /** + padding * 2 */
    };

    this.adjustHeight = function () {
        /** NOTE: Don't include margin in height, because it'd add previously added margin-top's. */
        let ownHeight = $('#' + operatorIdPrefix + model.idPrefix + model.id).outerHeight();
        let newVal = this.getHeight() / 2 - ownHeight / 2;

        let minHeight = sortableInnerMinHeight - ownHeight / 2;

        if (newVal < minHeight / 2) {
            newVal = minHeight / 2;
        }

        $('#' + operatorIdPrefix + model.idPrefix + model.id).css('margin-top', newVal);

        this.observable.notify(new Event(this, 'SizeChanged'));
    };

    this.registerButtonEvents = function () {
        let that = this;
        $('#' + model.idPrefix + model.id + selectPostfix).dropdown({
            value: model.operator,
            possibleValues: application.dataProvider.getConnectorDefaultOperators(),
            valueChanged: function (value, oldValue) {
                let editableEvent = new Event(that, 'ValueChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.operator';
                editableEvent.mappingPropertyControl = 'operator';

                that.observable.notify(editableEvent);
            }
        });

        this.adjustHeight();

        for (let i = 0; i < model.children.length; ++i) {
            model.children[i].registerButtonEvents();
        }

        for (let i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for (let i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}