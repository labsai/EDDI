function OccurrenceControl(model) {
    let ruleRowCSSPostfix = '_rulerow';
    let ruleNamePostfix = '_rulename';
    let ruleNameDescriptorCSSPostfix = '_rulenamedescriptor';
    let contextPostfix = '_context';
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
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">'
            + window.lang.convert('TIMES_OCCURRED') +
            '<div id="' + model.idPrefix + model.id + contextPostfix + '" class="' + model.CSSClassBase + contextPostfix + '"></div><div class="clear"></div>\
            <div class="' + model.CSSClassBase + ruleRowCSSPostfix + '">\
            <div class="' + model.CSSClassBase + ruleNameDescriptorCSSPostfix + '">' + window.lang.convert('OCCURRING_BEHAVIOR_RULE_NAME') + '</div>\
            <div id="' + model.idPrefix + model.id + ruleNamePostfix + '" class="' + model.CSSClassBase + ruleNamePostfix + '">'
            + model.behaviorRuleName + '</div><div class="clear"></div>\
            </div>';

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
        $('#' + model.idPrefix + model.id + contextPostfix).dropdown({
            value: model.maxOccurrence,
            possibleValues: application.dataProvider.getPossibleValuesForOccurrence(),
            valueChanged: function (value, oldValue) {
                console.log('New value is: ' + value);
                let editableEvent = new Event(that, 'ValueChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.maxOccurrence';
                editableEvent.mappingPropertyControl = 'maxOccurrence';

                that.observable.notify(editableEvent);
            }
        });

        $('#' + model.idPrefix + model.id + ruleNamePostfix).dropdown({
            value: model.behaviorRuleName,
            possibleValues: application.jsonRepresentationManager.getRuleNames(),
            valueChanged: function (value, oldValue) {
                let editableEvent = new Event(instance, 'ValueChanged');
                editableEvent.value = value;
                editableEvent.oldValue = instance.getModel().behaviorRuleName;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.behaviorRuleName';
                editableEvent.mappingPropertyControl = 'behaviorRuleName';

                instance.observable.notify(editableEvent);
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