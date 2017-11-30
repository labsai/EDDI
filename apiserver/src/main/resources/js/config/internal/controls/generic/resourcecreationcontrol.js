function ResourceCreationControl(model) {
    let headerTextPostfix = '_headertext';
    let footerPostfix = '_footer';
    let acceptPostfix = '_accept';
    let cancelPostfix = '_cancel';
    let inputRowPostfix = '_inputrow';
    let inputTextPostfix = '_inputtext';
    let inputFieldPostfix = '_inputfield';
    let autoUpdatePostfix = '_autoupdate';

    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">';

        representation += '<div id="' + model.idPrefix + model.id + headerTextPostfix + '" class="' + model.CSSClassBase + headerTextPostfix + '">'
            + window.lang.convert('CREATE_RESOURCE') + '</div>';

        representation += '<div id="' + model.idPrefix + model.id + inputRowPostfix + '" class="' + model.CSSClassBase + inputRowPostfix + '">';

        representation += '<div id="' + model.idPrefix + model.id + inputTextPostfix + '" class="' + model.CSSClassBase + inputTextPostfix + '">'
            + window.lang.convert('RESOURCE_NAME')
            + '</div>';
        representation += '<div id="' + model.idPrefix + model.id + inputFieldPostfix + '" class="' + model.CSSClassBase + inputFieldPostfix + '"></div>';


        representation += '<div class="clear"></div></div>';

        representation += '<div id="' + model.idPrefix + model.id + footerPostfix + '" class="' + model.CSSClassBase + footerPostfix + '">';

        if (model.showsCheckbox) {
            representation += '<div class="' + model.CSSClassBase + autoUpdatePostfix + '">' +
                '<input type="checkbox" checked="true" id="' + model.idPrefix + model.id + autoUpdatePostfix + '"/>' +
                window.lang.convert('AUTO_UPDATE_REFERENCE') +
                '</div>';
        }

        representation += '<div id="' + model.idPrefix + model.id + cancelPostfix + '" class="' + model.CSSClassBase + cancelPostfix + '"></div>';
        representation += '<div id="' + model.idPrefix + model.id + acceptPostfix + '" class="' + model.CSSClassBase + acceptPostfix + '"></div>';

        representation += '<div class="clear"></div></div>';
        representation += '</div>';

        return representation;
    };

    this.registerButtonEvents = function () {
        $('#' + model.idPrefix + model.id + cancelPostfix).click(function () {
            $(window).unbind('keypress');
            model.completion(false, $('#' + model.idPrefix + model.id + autoUpdatePostfix).attr('checked'));

        });

        $('#' + model.idPrefix + model.id + acceptPostfix).click(function () {
            $(window).unbind('keypress');
            model.completion(true, $('#' + model.idPrefix + model.id + autoUpdatePostfix).attr('checked'));
        });

        $(window).unbind('keypress');
        $(window).bind('keypress', function (e) {
            if (e.keyCode === 13) /** ENTER */ {
                $(window).unbind('keypress');
                model.completion(true, $('#' + model.idPrefix + model.id + autoUpdatePostfix).attr('checked'));
            }
        });

        $('#' + model.idPrefix + model.id + inputFieldPostfix).editable(function (value, settings) {
            model.currentValue = value;

            $(window).bind('keypress', function (e) {
                if (e.keyCode === 13) /** ENTER */ {
                    $(window).unbind('keypress');
                    model.completion(true, $('#' + model.idPrefix + model.id + autoUpdatePostfix).attr('checked'));
                }
            });

            return application.bindingManager.bindFromString(value);
        }, {
            tooltip: window.lang.convert('RESOURCE_NAME_PLACEHOLDER'),
            type: 'text',
            style: 'inherit',
            submit: window.lang.convert('EDITABLE_OK'),
            cancel: window.lang.convert('EDITABLE_CANCEL'),
            placeholder: window.lang.convert('EDITABLE_PLACEHOLDER'),
            data: function (value, settings) {
                /** Unescape innerHtml before editing. */
                return application.bindingManager.bindToString(value);
            }
        }).click(function () {
            $(window).unbind('keypress');
        });
    };

    this.getModel = function () {
        return model;
    }
}

function ResourceCreationModel(id, idPrefix, cssClassBase, type, completion, showsCheckbox) {
    this.id = id;
    this.idPrefix = idPrefix;
    this.CSSClassBase = cssClassBase;
    this.type = type;
    this.completion = completion;
    this.currentValue = '';
    this.showsCheckbox = showsCheckbox;
}