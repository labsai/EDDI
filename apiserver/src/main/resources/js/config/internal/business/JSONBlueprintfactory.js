function JSONBlueprintFactory() {
    this.makeBlueprintForObjectType = function (objectType) {
        switch (objectType) {
            case 'BehaviorGroup':
                var behaviorGroupName = behaviorGroupNameDefault = window.lang.convert('BEHAVIORGROUP_DEFAULT_NAME');

                var i = 1;
                while (application.jsonRepresentationManager.hasGroupWithName(behaviorGroupName)) {
                    behaviorGroupName = behaviorGroupNameDefault + ' ' + i++;
                }

                return {
                    name: behaviorGroupName,
                    children: []
                };
            case 'BehaviorRule':
                var behaviorRuleName = behaviorRuleNameDefault = window.lang.convert('BEHAVIORRULE_DEFAULT_NAME');

                var i = 1;
                while (application.jsonRepresentationManager.hasRuleWithName(behaviorRuleName)) {
                    behaviorRuleName = behaviorRuleNameDefault + ' ' + i++;
                }

                return {
                    default: false,
                    children: [],
                    actions: [],
                    name: behaviorRuleName,
                    producesOutput: true,
                    sequenceNumber: 0
                };
            case 'PatchInstruction':
                return {
                    operation: 0,
                    document: {}
                };
                break;
            case 'RegularDictionaryConfiguration':
                return {
                    language: '',
                    words: [],
                    phrases: []
                };
                break;
            case 'phrases':
                return {
                    phrase: '',
                    exp: ''
                };
                break;
            case 'words':
                return {
                    word: '',
                    exp: '',
                    frequency: ''
                };
                break;
            case 'OutputConfigurationSet':
                return {
                    outputSet: []
                };
                break;
            case 'outputSet':
                return {
                    action: '',
                    outputs: [],
                    timesOccurred: 0
                };
                break;
            case 'Bot':
                return {
                    packages: []
                }
                break;
            case 'Package':
                return {
                    packageExtensions: []
                };
                break;
            case 'BehaviorRuleConfigurationSet':
                return {
                    behaviorGroups: []
                };
                break;
            case 'SimpleDocumentDescriptor':
                return {
                    name: '',
                    description: ''
                }
                break;
            case 'TestCaseDescriptor':
                return {
                    name: '',
                    description: ''
                }
                break;
        }

        for (var key in application.pluginManager.plugins.behaviorruleextensionhandlers) {
            if (objectType == key) {
                var model = new application.pluginManager.plugins.behaviorruleextensionhandlers[key].model();

                var defaultExtension = model.makeDefaultJSON();

                return defaultExtension;
            }
        }
    }
}