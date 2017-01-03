function SentenceparserLifecycleControl(model) {
    var groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';
    var correctionIdPrefix = groupControlIdPrefix + 'correction_';
    var correctionBlockIdPrefix = correctionIdPrefix + 'block_';
    var dictionaryIdPrefix = groupControlIdPrefix + 'dictionary_';
    var dictionaryBlockIdPrefix = dictionaryIdPrefix + 'block_';
    var lifecycleGroupControlCSSClass = 'lifecyclegroup';

    this.observable = new Observable();

    this.getLifeCycleTaskPlugin = function (lifecycleTaskType) {
        if (application.pluginManager.plugins.lifecycletaskhandlers.hasOwnProperty(lifecycleTaskType)) {
            return application.pluginManager.plugins.lifecycletaskhandlers[lifecycleTaskType];
        } else {
            throw new UnknownLifecycleTaskException("Cannot display lifecycle task of type: " + lifecycleTaskType + ".");
        }
    }

    var groupControlBuilder = new GroupControlBuilder();
    var groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        window.lang.convert('SENTENCEPARSERLIFECYCLE_NAME'), true, true, false, 'packagecontrol', false);

    var instance = this;
    /** Configure the SentenceparserLifecycleControl to act as a transparent proxy for its backing groupControl. */
    this.observer = new Observer(function (event) {
        var newEvent = jQuery.extend(true, {}, event);
        newEvent.sender = instance;
        instance.observable.notify(newEvent);
    });
    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

    var correctionControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, correctionIdPrefix,
        window.lang.convert('SENTENCEPARSERLIFECYCLE_CORRECTION_NAME'), false, false, true, lifecycleGroupControlCSSClass, true);

    correctionControl.getModel().context = {namespace: 'io.sls.ai.labs.parser.corrections'};

    for (var i = 0; i < model.correction.length; ++i) {
        var correction = model.correction[i];

        var plugin = this.getLifeCycleTaskPlugin(correction.type);

        var blockControlModel = new plugin.model(correction);
        var blockControl = new plugin.control(blockControlModel);

        blockControl.observable.addObserver(application.actionHandler.observer);

        correctionControl.getModel().addChild(blockControl);
    }

    var dictionaryControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, dictionaryIdPrefix,
        window.lang.convert('SENTENCEPARSERLIFECYCLE_DICTIONARY_NAME'), false, false, true, lifecycleGroupControlCSSClass, true);

    dictionaryControl.getModel().context = {namespace: 'io.sls.ai.labs.parser.dictionaries'};

    for (var i = 0; i < model.dictionaries.length; ++i) {
        var dictionary = model.dictionaries[i];

        var plugin = this.getLifeCycleTaskPlugin(dictionary.type);

        var blockControlModel = new plugin.model(dictionary);
        var blockControl = new plugin.control(blockControlModel);

        blockControl.observable.addObserver(application.actionHandler.observer);

        dictionaryControl.getModel().addChild(blockControl);
    }

    groupControl.getModel().addChild(correctionControl);
    groupControl.getModel().addChild(dictionaryControl);

    model.backingGroupControl = groupControl;
    model.children.push(groupControl);

    this.createRepresentation = function () {
        var representation = '<div id="' + model.idPrefix + model.id + '">' + groupControl.createRepresentation() + '<div class="clear"></div></div>';

        return representation;
    }

    this.getModel = function () {
        return model;
    }

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    }

    this.registerButtonEvents = function () {
        groupControl.registerButtonEvents();
    }
}