function InputmatcherControl(model) {
    var expressionRowPostfix = '_expressionrow';
    var expressionDescriptorPostfix = '_expressiondescriptor';
    var expressionPostfix = '_expression';
    var occurrencePostfix = '_occurrence';
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
            <div id=' + model.idPrefix + model.id + occurrencePostfix + ' class="' + model.CSSClassBase + occurrencePostfix + '"></div>\
            <div class="clear"></div>\
            <div class="' + model.CSSClassBase + expressionRowPostfix + '">\
            <div class="' + model.CSSClassBase + expressionDescriptorPostfix + '">' + window.lang.convert('INPUT_RULE') + '</div>\
            <div id="' + model.idPrefix + model.id + expressionPostfix + '" class="' + model.CSSClassBase + expressionPostfix + '">' + model.expressions + '</div>\
            <div class="clear"></div>\
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

        function split(val) {
            return val.split(/,\s*/);
        }

        function extractLast(term) {
            return split(term).pop();
        }

        $('#' + model.idPrefix + model.id + occurrencePostfix).dropdown({
            value: model.occurrence,
            possibleValues: application.dataProvider.getPossibleValuesForInputmatcherOccurrence(),
            valueChanged: function (value, oldValue) {
                console.log('New value is: ' + value);

                var editableEvent = new Event(that, 'ValueChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.occurrence';
                editableEvent.mappingPropertyControl = 'occurrence';

                that.observable.notify(editableEvent);
            }
        });

        $('#' + model.idPrefix + model.id + expressionPostfix).editable(function (value, settings) {
            var editableEvent = new Event(instance, 'ValueChanged');
            editableEvent.value = value;
            editableEvent.settings = settings;
            editableEvent.oldValue = instance.getModel().expressions;
            editableEvent.editable = $(this);
            editableEvent.isUserInput = true;
            editableEvent.mappingPropertyJSON = 'values.expressions';
            editableEvent.mappingPropertyControl = 'expressions';

            instance.observable.notify(editableEvent);

            return application.bindingManager.bindFromString(value);
        }, {
            type: 'autocomplete',
            style: 'inherit',
            onblur: 'submit',
            data: function (value, settings) {
                /** Unescape innerHtml before editing. */
                return application.bindingManager.bindToString(value);
            },
            autocomplete: {
                options: {
                    source: function (request, response) {
                        var anchors = application.url.serializeAnchors();
                        if (anchors.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
                            anchors.hasOwnProperty(application.configuration.packageParentVersionHashKey)) {
                            var expressions = application.dataProvider.readPackageExpressions(
                                anchors[application.configuration.packageParentIdHashKey],
                                anchors[application.configuration.packageParentVersionHashKey],
                                extractLast(request.term)
                            );

                            response(expressions);
                        }
                    },
                    search: function () {
                        // custom minLength
                        var term = extractLast(this.value);
                        if (term.length < 1) {
                            return false;
                        }
                    },
                    focus: function () {
                        // prevent value inserted on focus
                        return false;
                    },
                    select: function (event, ui) {
                        var terms = split(this.value);
                        // remove the current input
                        terms.pop();
                        // add the selected item
                        terms.push(ui.item.value);
                        // add placeholder to get the comma-and-space at the end
                        //terms.push("");
                        this.value = terms.join(", ");
                        return false;
                    },
                    minLength: 1,
                    open: function () {
                        $('.ui-autocomplete-category').next('.ui-menu-item').addClass('ui-first');
                    }
                }
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