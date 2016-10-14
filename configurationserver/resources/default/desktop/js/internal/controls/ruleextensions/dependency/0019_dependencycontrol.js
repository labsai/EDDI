function DependencyControl(model) {
    var referenceCSSPostfix = '_reference';
    var referenceTextPostfix = '_referencetext';
    var referenceValuePostfix = '_referencevalue';
    var footerCSSClassPostfix = '_footer';

    var instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteExtension'));
                break;
        }
    });

    for (var i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    this.createRepresentation = function () {
        var representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">\
            <div class="' + model.CSSClassBase + referenceCSSPostfix + '">'
            + '<div class="' + model.CSSClassBase + referenceTextPostfix + '">' + window.lang.convert('DEPENDENCY_REFERENCE') + '</div>'
            + '<div id="' + model.idPrefix + model.id + referenceValuePostfix + '" class="' + model.CSSClassBase + referenceValuePostfix + '"></div>'
            + '<div class="clear"></div></div>';

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
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    }

    this.registerButtonEvents = function () {
        var that = this;
        $('#' + model.idPrefix + model.id + referenceValuePostfix).dropdown({
            value:model.reference,
            possibleValues:application.jsonRepresentationManager.getRuleNames(),
            valueChanged:function (value, oldValue) {
                var editableEvent = new Event(that, 'ValueChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.reference';
                editableEvent.mappingPropertyControl = 'reference';

                that.observable.notify(editableEvent);
            }
        });

        for (var i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for(var i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}