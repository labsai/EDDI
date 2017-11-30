function DependencyControl(model) {
    let referenceCSSPostfix = '_reference';
    let referenceTextPostfix = '_referencetext';
    let referenceValuePostfix = '_referencevalue';
    let footerCSSClassPostfix = '_footer';

    let instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteExtension'));
                break;
        }
    });

    for (let i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">\
            <div class="' + model.CSSClassBase + referenceCSSPostfix + '">'
            + '<div class="' + model.CSSClassBase + referenceTextPostfix + '">' + window.lang.convert('DEPENDENCY_REFERENCE') + '</div>'
            + '<div id="' + model.idPrefix + model.id + referenceValuePostfix + '" class="' + model.CSSClassBase + referenceValuePostfix + '"></div>'
            + '<div class="clear"></div></div>';

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
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    };

    this.registerButtonEvents = function () {
        let that = this;
        $('#' + model.idPrefix + model.id + referenceValuePostfix).dropdown({
            value: model.reference,
            possibleValues: application.jsonRepresentationManager.getRuleNames(),
            valueChanged: function (value, oldValue) {
                let editableEvent = new Event(that, 'ValueChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.reference';
                editableEvent.mappingPropertyControl = 'reference';

                that.observable.notify(editableEvent);
            }
        });

        for (let i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for (let i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}