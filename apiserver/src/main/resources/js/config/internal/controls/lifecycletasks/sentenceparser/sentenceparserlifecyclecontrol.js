function SentenceparserLifecycleControl(model) {
    let groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';
    let correctionIdPrefix = groupControlIdPrefix + 'correction_';
    let correctionBlockIdPrefix = correctionIdPrefix + 'block_';
    let dictionaryIdPrefix = groupControlIdPrefix + 'dictionary_';
    let dictionaryBlockIdPrefix = dictionaryIdPrefix + 'block_';
    let lifecycleGroupControlCSSClass = 'lifecyclegroup';

    this.observable = new Observable();

    this.getLifeCycleTaskPlugin = function (lifecycleTaskType) {
        if (application.pluginManager.plugins.lifecycletaskhandlers.hasOwnProperty(lifecycleTaskType)) {
            return application.pluginManager.plugins.lifecycletaskhandlers[lifecycleTaskType];
        } else {
            throw new UnknownLifecycleTaskException("Cannot display lifecycle task of type: " + lifecycleTaskType + ".");
        }
    };

    let groupControlBuilder = new GroupControlBuilder();
    let groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        window.lang.convert('SENTENCEPARSERLIFECYCLE_NAME'), true, true, false, 'packagecontrol', false);

    let instance = this;
    /** Configure the SentenceparserLifecycleControl to act as a transparent proxy for its backing groupControl. */
    this.observer = new Observer(function (event) {
        let newEvent = jQuery.extend(true, {}, event);
        newEvent.sender = instance;
        instance.observable.notify(newEvent);
    });
    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

    let correctionControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, correctionIdPrefix,
        window.lang.convert('SENTENCEPARSERLIFECYCLE_CORRECTION_NAME'), false, false, true, lifecycleGroupControlCSSClass, true);

    correctionControl.getModel().context = {namespace: 'ai.labs.parser.corrections'};

    for (let i = 0; i < model.correction.length; ++i) {
        let correction = model.correction[i];

        let plugin = this.getLifeCycleTaskPlugin(correction.type);

        let blockControlModel = new plugin.model(correction);
        let blockControl = new plugin.control(blockControlModel);

        blockControl.observable.addObserver(application.actionHandler.observer);

        correctionControl.getModel().addChild(blockControl);
    }

    let dictionaryControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, dictionaryIdPrefix,
        window.lang.convert('SENTENCEPARSERLIFECYCLE_DICTIONARY_NAME'), false, false, true, lifecycleGroupControlCSSClass, true);

    dictionaryControl.getModel().context = {namespace: 'ai.labs.parser.dictionaries'};

    for (let i = 0; i < model.dictionaries.length; ++i) {
        let dictionary = model.dictionaries[i];

        let plugin = this.getLifeCycleTaskPlugin(dictionary.type);

        let blockControlModel = new plugin.model(dictionary);
        let blockControl = new plugin.control(blockControlModel);

        blockControl.observable.addObserver(application.actionHandler.observer);

        dictionaryControl.getModel().addChild(blockControl);
    }

    groupControl.getModel().addChild(correctionControl);
    groupControl.getModel().addChild(dictionaryControl);

    model.backingGroupControl = groupControl;
    model.children.push(groupControl);

    this.createRepresentation = function () {
        return '<div id="' + model.idPrefix + model.id + '">' + groupControl.createRepresentation() + '<div class="clear"></div></div>';
    };

    this.getModel = function () {
        return model;
    };

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    };

    this.registerButtonEvents = function () {
        groupControl.registerButtonEvents();
    }
}