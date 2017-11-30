function DialogControl(model) {
    let contentPostfix = '_content';
    let buttonBoxPostfix = '_buttons';
    let okButtonPostfix = '_okbutton';
    let cancelButtonPostfix = '_cancelbutton';
    let formBoxPostfix = '_formBox';
    let formPostfix = '_form';
    let formIdPostfix = '_form_0';
    let documentDescriptionPostfix = '_documentDescription';
    let addButtonPostfix = '_addbutton';
    let addContainerPostfix = '_addcontainer';

    let selectedDocumentDescriptionValueIndex;
    let resourceCreationControl = null;

    this.observable = new Observable();
    let instance = this;

    let showAddMenu = function () {
        let type = 'ai.labs.package';
        let resourceCreationModel = new ResourceCreationModel(application.dataProvider.getNextIdGlobal(), 'resourcecreation_',
            'resourcecreation',
            type,
            function (success) {
                if (success) {
                    if (resourceCreationControl.getModel().currentValue !== "") {
                        let last = type.split('.').last();

                        let newUri;
                        /** Create a new resource. */
                        switch (last) {
                            case 'bot':
                                newUri = application.dataProvider.createBot(application.jsonBlueprintFactory.makeBlueprintForObjectType('Bot'));
                                break;
                            case 'package':
                                newUri = application.dataProvider.createPackage(application.jsonBlueprintFactory.makeBlueprintForObjectType('Package'));
                                break;
                            case 'output':
                                newUri = application.dataProvider.createOutputSet(application.jsonBlueprintFactory.makeBlueprintForObjectType('OutputConfigurationSet'));
                                break;
                            case 'behavior':
                                newUri = application.dataProvider.createBehaviorRuleSet(application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRuleConfigurationSet'));
                                break;
                            case 'regulardictionary':
                                newUri = application.dataProvider.createRegularDictionary(application.jsonBlueprintFactory.makeBlueprintForObjectType('RegularDictionaryConfiguration'));
                                break;
                            default:
                                break;
                        }

                        let patch = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');

                        let params = SLSUriParser(newUri);

                        patch.document = application.dataProvider.readDocumentDescription(params.id, params.version);
                        patch.document.name = resourceCreationControl.getModel().currentValue;

                        $('.' + model.CSSClassBase).showLoadingIndicator();
                        application.dataProvider.patchDocumentDescription(params.id,
                            params.version,
                            patch,
                            function (httpCode) {
                                if (!application.httpCodeManager.successfulRequest(httpCode)) {
                                    let dcm = new DialogControlModel(window.lang.convert('ERROR_CREATE_RESOURCE'), function () {
                                        },
                                        window.lang.convert("OK_BUTTON"));
                                    let dc = new DialogControl(dcm);
                                    dc.showDialog();
                                } else {
                                    model.formElements = application.dataProvider.readDocumentDescriptions('ai.labs.package', 0, 0, '', 'asc');

                                    if (model.context) {
                                        model.context["preselectedUri"] = newUri;
                                    }

                                    instance.showDialog();
                                }

                                $('.' + model.CSSClassBase).hideLoadingIndicator();
                            });
                    } else {
                        let dcm = new DialogControlModel(window.lang.convert('ERROR_NO_RESOURCE_NAME'), function () {
                            },
                            window.lang.convert("OK_BUTTON"));
                        let dc = new DialogControl(dcm);
                        dc.showDialog();
                    }
                } else {
                    hideAddMenu();
                }
            });

        resourceCreationControl = new ResourceCreationControl(resourceCreationModel);

        $('.' + model.CSSClassBase + addContainerPostfix).after(resourceCreationControl.createRepresentation());
        resourceCreationControl.registerButtonEvents();

        $('.' + model.CSSClassBase + addButtonPostfix).fadeOut();

        $('#' + resourceCreationControl.getModel().idPrefix + resourceCreationControl.getModel().id).hide().animate({
            height: 'toggle'
        }, {
            duration: 500
        });

        let totalHeight = $('.' + model.CSSClassBase + buttonBoxPostfix).outerHeight() + $('.' + model.CSSClassBase + formBoxPostfix).outerHeight()
            + $('.' + model.CSSClassBase + contentPostfix).outerHeight()
            + 40;

        $('#simplemodal-container').animate({height: totalHeight * 1.15 + 'px'}, {duration: 500});
    };

    let hideAddMenu = function () {
        $('#' + resourceCreationControl.getModel().idPrefix + resourceCreationControl.getModel().id).animate({
            height: 'toggle'
        }, {
            duration: 500,
            complete: function () {
                $('#' + resourceCreationControl.getModel().idPrefix + resourceCreationControl.getModel().id).remove();
                $('.' + model.CSSClassBase + addButtonPostfix).fadeIn();
            }
        });

        let totalHeight = $('.' + model.CSSClassBase + buttonBoxPostfix).outerHeight() + $('.' + model.CSSClassBase + formBoxPostfix).outerHeight()
            + $('.' + model.CSSClassBase + contentPostfix).outerHeight() - 40;
        $('#simplemodal-container').animate({height: totalHeight * 1.15 + 'px'}, {duration: 500});
    };

    this.createRepresentation = function () {
        let representation = '<div class="' + model.CSSClassBase + '"><div class="' + model.CSSClassBase + contentPostfix + '">' + model.text + '</div>';

        representation += '<div class="' + model.CSSClassBase + formBoxPostfix + '">';

        let renderingType = 'form';
        if (typeof model.formElements !== 'undefined' && model.formElements !== false && model.formElements !== null) {
            if (typeof model.context === 'undefined' || typeof model.context.dialogType === 'undefined') {
                renderingType = 'form';
            }
            else {
                switch (model.context.dialogType) {
                    case 'table':
                        renderingType = 'form';
                        break;
                    case 'documentDescription':
                        renderingType = 'documentDescription';
                        break;
                    case 'textFields':
                        renderingType = 'textFields';
                        break;
                }
            }

            switch (renderingType) {
                case 'form':
                case 'textFields':
                    representation += '<form id="' + model.CSSClassBase + formIdPostfix + '" class="' + model.CSSClassBase + formPostfix + '" action="#">';

                    for (let i = 0; i < model.formElements.length; ++i) {
                        if (model.formElements[i].hasOwnProperty('createRepresentation')) {
                            representation += model.formElements[i].createRepresentation();
                        } else {
                            representation += model.formElements[i];
                        }
                    }

                    representation += '</form>';
                    break;
                case 'documentDescription':
                    representation += '<div class="' + model.CSSClassBase + documentDescriptionPostfix + '" />';
                    break;
            }
        }

        if (model.context && model.context.inlineResourceCreation === true) {
            representation += '<div class="' + model.CSSClassBase + addContainerPostfix + '">';
            representation += '<div class="' + model.CSSClassBase + addButtonPostfix + '"></div>';
            representation += '<div class="clear"></div></div>';
        }

        representation += '</div><div class="' + model.CSSClassBase + buttonBoxPostfix + '">';

        if (model.okButtonText !== false && typeof model.okButtonText !== "undefined" && model.okButtonText !== null) {
            representation += '<div href="#" class="' + model.CSSClassBase + okButtonPostfix + '">' + model.okButtonText + '</div>';
        }

        if (model.cancelButtonText !== false && typeof model.cancelButtonText !== "undefined" && model.cancelButtonText !== null) {
            representation += '<div href="#" class="' + model.CSSClassBase + cancelButtonPostfix + '">' + model.cancelButtonText + '</div>';
        }

        representation += '<div class="clear"></div></div></div>';

        return representation;
    };

    this.registerButtonEvents = function () {
        let instance = this;

        function split(val) {
            return val.split(/,\s*/);
        }

        function extractLast(term) {
            return split(term).pop();
        }

        $('#' + model.CSSClassBase + formIdPostfix + ' input[name="exp"]').autocomplete({
            source: function (request, response) {
                let expressions = application.dataProvider.readExpressions(
                    application.dataProvider.dataProviderState.getActiveId(),
                    application.dataProvider.dataProviderState.getActiveVersion(),
                    extractLast(request.term)
                );

                response(expressions);
            },
            search: function () {
                // custom minLength
                let term = extractLast(this.value);
                if (term.length < 1) {
                    return false;
                }
            },
            focus: function () {
                // prevent value inserted on focus
                return false;
            },
            select: function (event, ui) {
                let terms = split(this.value);
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
        });

        $('#' + model.CSSClassBase + formIdPostfix + ' input[name="key"]').autocomplete({
            source: function (request, response) {
                let anchors = application.url.serializeAnchors();

                let outputKeys;
                if (anchors.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
                    anchors.hasOwnProperty(application.configuration.packageParentVersionHashKey)) {
                    outputKeys = application.dataProvider.readOutputKeysPackage(
                        anchors[application.configuration.packageParentIdHashKey],
                        anchors[application.configuration.packageParentVersionHashKey],
                        request.term
                    );
                } else {
                    outputKeys = application.dataProvider.readOutputKeys(
                        application.dataProvider.dataProviderState.getActiveId(),
                        application.dataProvider.dataProviderState.getActiveVersion(),
                        request.term
                    );
                }

                response(outputKeys);
            },
            minLength: 1,
            open: function () {
                $('.ui-autocomplete-category').next('.ui-menu-item').addClass('ui-first');
            }
        });

        if ($('#auto_generate_expression').exists()) {
            $('#' + model.CSSClassBase + formIdPostfix + ' input[type="text"]:eq(1)').focus(
                function () {
                    if ($('#auto_generate_expression').attr('checked')) {
                        let input = $('#' + model.CSSClassBase + formIdPostfix + ' input[type="text"]:eq(0)').val();
                        let expr = application.expressionHelper.convertToExpression(input, "unused");

                        $('#' + model.CSSClassBase + formIdPostfix + ' input[type="text"]:eq(1)').val(expr);
                    }

                    if ($('#auto_to_lower').attr('checked')) {
                        let input = $('#' + model.CSSClassBase + formIdPostfix + ' input[type="text"]:eq(0)').val();

                        let output = input.toLowerCase();

                        console.log(output);

                        $('#' + model.CSSClassBase + formIdPostfix + ' input[type="text"]:eq(0)').val(output);
                    }
                }
            )
        }

        $(window).bind('keypress', function (e) {
            if (e.keyCode === 13) /** ENTER */ {
                $(window).unbind('keypress');
                $('.' + model.CSSClassBase + okButtonPostfix).trigger('click');
            }
        });

        $('.' + model.CSSClassBase + addButtonPostfix).click(function () {
            showAddMenu();
        });

        // Rendering Event for document description selection
        if (typeof model.formElements !== 'undefined' && model.formElements !== false && model.formElements !== null) {
            if (typeof model.context !== 'undefined' && typeof model.context.dialogType !== 'undefined') {
                switch (model.context.dialogType) {
                    case 'documentDescription':
                        let documentSelectionValues = [];
                        let preselectedDocument = null;
                        for (let i = 0; i < model.formElements.length; ++i) {
                            documentSelectionValues.push(model.formElements[i].name);

                            /**
                             * The dialog was opened requesting to have a specific value preselected.
                             * If it doesn't exist, however,
                             * the request cannot be fulfilled and the first item is preselected by default.
                             * */
                            if (model.context &&
                                model.context.preselectedUri &&
                                model.formElements[i].resource === model.context.preselectedUri) {
                                preselectedDocument = model.formElements[i].name;
                            }
                        }

                        let currentValue;

                        if (preselectedDocument !== null) {
                            currentValue = preselectedDocument;
                        } else {
                            currentValue = documentSelectionValues[0];
                        }

                        let selector = $('.' + model.CSSClassBase + documentDescriptionPostfix);
                        if (documentSelectionValues.length > 0) {
                            selectedDocumentDescriptionValueIndex = 0;
                            selector.dropdown({
                                value: currentValue,
                                possibleValues: documentSelectionValues,
                                displayInline: true,
                                valueChanged: function (value, oldValue, valueIndex) {
                                    selectedDocumentDescriptionValueIndex = valueIndex;
                                }
                            });
                        } else {
                            selectedDocumentDescriptionValueIndex = -1;
                            selector.text(window.lang.convert('DIALOG_DOCUMENTDESCRIPTION_NO_VALUES_FOUND'));
                        }
                        break;
                }
            }
        }

        // Events
        $('.' + model.CSSClassBase + okButtonPostfix).click(function () {
            let event;

            if (typeof model.formElements !== 'undefined' && model.formElements !== false && model.formElements !== null) {
                if (typeof model.context === 'undefined' || typeof model.context.dialogType === 'undefined') {
                    event = new Event(instance, $('#' + model.CSSClassBase + formIdPostfix + ' input[type="radio"]:checked').val());
                } else {
                    switch (model.context.dialogType) {
                        case 'table':
                            event = new Event(instance, 'Submit');
                            event.newRowData = {};
                            $('#' + model.CSSClassBase + formIdPostfix + ' input').each(function () {
                                event.newRowData[$(this).attr('name')] = $(this).val();
                            });
                            break;
                        case 'documentDescription':
                            event = new Event(instance, 'Submit');
                            if (selectedDocumentDescriptionValueIndex !== -1) {
                                event.documentDescription = model.formElements[selectedDocumentDescriptionValueIndex];
                            }
                            break;
                        case 'textFields':
                            event = new Event(instance, 'Submit');

                            event.map = {};

                            let textSelector = $('#' + model.CSSClassBase + formIdPostfix + ' input[type="text"]');
                            for (let i = 0; i < textSelector.length; ++i) {
                                let item = $('#' + model.CSSClassBase + formIdPostfix + ' input[type="text"]:eq(' + i + ')');
                                event.map[item.attr('name')] = item.val();
                            }

                            for (let i = 0; i < model.formElements.length; ++i) {
                                if (model.formElements[i].hasOwnProperty('getValue')) {
                                    event.map[model.formElements[i].getKey()] = model.formElements[i].getValue();
                                }
                            }

                            break;
                    }
                }
            } else {
                event = new Event(instance, 'Submit');
            }

            $.modal.close();
            model.callback(true, event);
        });

        $('.' + model.CSSClassBase + cancelButtonPostfix).click(function () {
            $.modal.close();
            model.callback(false);
        });

        let hasResourceSelector = false
        if (model.formElements) {
            for (let i = 0; i < model.formElements.length; ++i) {
                if (model.formElements[i].hasOwnProperty('registerButtonEvents')) {
                    model.formElements[i].registerButtonEvents();
                    hasResourceSelector = true;
                }
            }
        }

        let totalWidth = $('.' + model.CSSClassBase + okButtonPostfix).outerWidth() + $('.' + model.CSSClassBase + cancelButtonPostfix).outerWidth();

        if (model.cancelButtonText !== false) {
            totalWidth += 15;
        }

        $('.' + model.CSSClassBase + buttonBoxPostfix).width(totalWidth);

        let totalHeight = $('.' + model.CSSClassBase + buttonBoxPostfix).outerHeight() + $('.' + model.CSSClassBase + formBoxPostfix).outerHeight()
            + $('.' + model.CSSClassBase + contentPostfix).outerHeight();
        $('#simplemodal-container').height(totalHeight * 1.15);

        let sizes = [$('.' + model.CSSClassBase + contentPostfix).outerWidth(),
            $('.' + model.CSSClassBase + buttonBoxPostfix).outerWidth(),
            $('.' + model.CSSClassBase + formBoxPostfix).outerWidth()];

        let maxWidth = 0;

        $(sizes).each(function () {
            if (this > maxWidth) {
                maxWidth = this;
            }
        });

        if (resourceCreationControl !== null) {
            resourceCreationControl.registerButtonEvents();
        }

        $('#simplemodal-container').width(maxWidth * 1.25);
    };

    this.showDialog = function () {
        let callback = function () {
            $.modal.close();
            model.callback(false);
        };

        /** Just in case it was opened before. Overwrite with new content. */
        $.modal.close();
        $.modal(this.createRepresentation());
        this.registerButtonEvents();
    };

    this.getModel = function () {
        return model;
    }
}

function DialogControlModel(text, callback, okButtonText, cancelButtonText, formElements, context) {
    this.text = text;
    this.CSSClassBase = 'dialogcontrol';
    this.callback = callback;
    this.okButtonText = okButtonText;
    this.cancelButtonText = cancelButtonText;
    this.formElements = formElements;
    this.context = context;
}
