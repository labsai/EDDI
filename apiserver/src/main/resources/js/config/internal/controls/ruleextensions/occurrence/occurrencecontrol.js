function OccurrenceControl(model) {
    var ruleRowCSSPostfix = '_rulerow';
    var ruleNamePostfix = '_rulename';
    var ruleNameDescriptorCSSPostfix = '_rulenamedescriptor'
    var contextPostfix = '_context';
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
        var representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">'
            + window.lang.convert('TIMES_OCCURRED') +
            '<div id="' + model.idPrefix + model.id + contextPostfix + '" class="' + model.CSSClassBase + contextPostfix + '"></div><div class="clear"></div>\
            <div class="' + model.CSSClassBase + ruleRowCSSPostfix + '">\
            <div class="' + model.CSSClassBase + ruleNameDescriptorCSSPostfix + '">' + window.lang.convert('OCCURRING_BEHAVIOR_RULE_NAME') + '</div>\
            <div id="' + model.idPrefix + model.id + ruleNamePostfix + '" class="' + model.CSSClassBase + ruleNamePostfix + '">'
            + model.behaviorRuleName + '</div><div class="clear"></div>\
            </div>';

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
        $('#' + model.idPrefix + model.id + contextPostfix).dropdown({
            value: model.maxOccurrence,
            possibleValues: application.dataProvider.getPossibleValuesForOccurrence(),
            valueChanged: function (value, oldValue) {
                console.log('New value is: ' + value);
                var editableEvent = new Event(that, 'ValueChanged');

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
                var editableEvent = new Event(instance, 'ValueChanged');
                editableEvent.value = value;
                editableEvent.oldValue = instance.getModel().behaviorRuleName;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.behaviorRuleName';
                editableEvent.mappingPropertyControl = 'behaviorRuleName';

                instance.observable.notify(editableEvent);
            }
        });

        for (var i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for (var i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}