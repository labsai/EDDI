/**
 * The DataProvider object that supplies REST data objects.
 *
 * @author Patrick Schwab
 */
function DataProvider() {
    let nextId = 10001;
    let uid = new UIDGenerator();

    this.dataProviderState = new DataProviderState();

//    console.log('The active document ID is ' + this.dataProviderState.getActiveId() + '.');
//    console.log('The active document version is ' + this.dataProviderState.getActiveVersion() + '.');

    this.setActiveVersion = function (version) {
        console.log('current version is: ' + version);
        this.dataProviderState.setActiveVersion(version);
    };

    /* Resources */
    this.getI18nResource = function (language, location, completion) {
        IRestI18n.getI18nResource({language: language, location: location, $callback: completion});
    };

    /* Document Descriptions */
    this.readDocumentDescriptions = function (type, limit, index, filter, order) {
        let request = {};
        if (typeof type !== 'undefined') {
            request.type = type;
        }
        request.limit = limit;
        if (typeof index !== 'undefined' && index > 0) {
            request.index = index;
        }
        if (typeof filter !== 'undefined' && filter.length > 0) {
            request.filter = filter;
        }
        if (typeof order !== 'undefined' && order.length > 0) {
            request.order = order;
        }

        return IRestDocumentDescriptorStore.readDescriptors(request);
    };

    this.readDocumentDescription = function (id, version) {
        return IRestDocumentDescriptorStore.readDescriptor({id: id, version: version});
    };

    let descriptorCache = null;
    this.readActiveDocumentDescription = function () {
        if (descriptorCache === null) {
            return descriptorCache = this.readDocumentDescription(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion());
        } else {
            return descriptorCache;
        }
    };

    this.patchDocumentDescription = function (id, version, patchInstructions, completion) {
        IRestDocumentDescriptorStore.patchDescriptor({
            id: id,
            version: version,
            $entity: patchInstructions,
            $callback: completion
        });
    };

    /* Bots */
    this.getNextIdForBots = function () {
        return nextId++;
    };

    this.readBot = function (id, version) {
        return IRestBotStore.readBot({id: id, version: version});
    };

    this.readActiveBot = function () {
        return this.readBot(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion());
    };

    this.updateBot = function (id, version, bot, completion) {
        IRestBotStore.updateBot({id: id, version: version, $entity: bot, $callback: completion});
    };

    this.updateActiveBot = function (bot, completion) {
        this.updateBot(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), bot, completion);
    };

    this.createBot = function (bot) {
        return IRestBotStore.createBot({$entity: bot});
    };

    this.deleteBot = function (id, version) {
        IRestBotStore.deleteBot({id: id, version: version});
    };

    this.updateResourceInBot = function (id, version, uri, completion) {
        return IRestBotStore.updateResourceInBot({id: id, version: version, $entity: uri, $callback: completion});
    };

    this.setBehaviorRule = function (behaviorRuleDescription, completion) {
        IRestBehaviorStore.setBehaviorRule({
            id: this.dataProviderState.getActiveId(),
            $callback: completion,
            $entity: behaviorRuleDescription
        });
    };

    let groupExistsWithId = function (groups, id) {
        for (let i = 0; i < groups.length; ++i) {
            if (groups[i].id === id) {
                return true;
            }
        }

        return false;
    };

    let containsGroup = function (groups, group) {
        for (let i = 0; i < groups.length; ++i) {
            if (groups[i].groupName === group.groupName) {
                return true;
            }
        }

        return false;
    };

    let makeGroupList = function (behaviorRuleSet) {
        let groupList = [];
        let hasLostGroup = false;

        for (let i = 0; i < behaviorRuleSet.length; ++i) {
            let groups = behaviorRuleSet[i].groups;
            if (groups.length > 0) {
                for (let j = 0; j < groups.length; ++j) {
                    groupList.push(groups[j]);
                }
            } else {
                if (!hasLostGroup) {
                    groupList.push({groupName: application.configuration.lostAndFoundGroupName, rankingId: 0});
                }
            }
        }

        return groupList;
    };

    let makeBehaviorGroupIds = function (groups) {
        let isFirst = true;

        for (let i = 0; i < groups.length; ++i) {
            groups[i].id = uid.next();

            if (isFirst) {
                groups[i].selected = true;
                groups[i].opened = true;

                isFirst = false;
            } else {
                groups[i].selected = false;
                groups[i].opened = false;
            }

            groups[i].editable = true;
        }
    };

    this.getBehaviorRuleSet = function () {
        let tmp = IRestBehaviorStore.readBehaviorRuleSet({
            id: this.dataProviderState.getActiveId(),
            version: this.dataProviderState.getActiveVersion()
        });

        console.log(tmp);
        return tmp;
    };

    this.updateBehaviorRuleSet = function (behaviorRuleSet, completion) {
        IRestBehaviorStore.updateBehaviorRuleSet({
            id: this.dataProviderState.getActiveId(),
            version: this.dataProviderState.getActiveVersion(),
            $entity: behaviorRuleSet,
            $callback: completion
        });
    };

    this.createBehaviorRuleSet = function (behaviorRuleConfigurationSet) {
        return IRestBehaviorStore.createBehaviorRuleSet({$entity: behaviorRuleConfigurationSet});
    };

    this.deleteBehaviorRuleSet = function (id, version) {
        IRestBehaviorStore.deleteBehaviorRuleSet({id: id, version: version});
    };

    let getGroupForName = function (groupList, name) {
        for (let i = 0; i < groupList.length; ++i) {
            if (groupList[i].groupName === name) {
                return groupList[i];
            }
        }

        return undefined;
    };

    let addBehaviorRuleExtensions = function (groupList, behaviorRuleSet) {
        let isFirst = true;

        for (let i = 0; i < behaviorRuleSet.length; ++i) {
            let rule = behaviorRuleSet[i];

            /** Add id. */
            let prepareRule = function (input) {
                for (let i = 0; i < input.children.length; ++i) {
                    prepareRule(input.children[i]);
                }

                input.id = uid.next();

                if (isFirst) {
                    input.selected = true;
                    input.opened = true;
                    isFirst = false;
                } else {
                    input.selected = false;
                    input.opened = false;
                }
            };

            prepareRule(rule);

            let groups = rule.groups;
            if (groups.length > 0) {
                for (let j = 0; j < groups.length; ++j) {
                    let group = getGroupForName(groupList, groups[j].groupName);

                    if (!group.children) {
                        group.children = [];
                    }

                    group.children.push(rule);
                }
            } else {
                let group = getGroupForName(groupList, application.configuration.lostAndFoundGroupName);

                if (!group.children) {
                    group.children = [];
                }

                group.children.push(rule);
            }
        }
    };

    let prepareRules = function (groupList) {
        let isFirst = true;

        let prepareRule = function (input) {
            for (let i = 0; i < input.children.length; ++i) {
                prepareRule(input.children[i]);
            }

            input.id = uid.next();

            if (isFirst) {
                input.selected = true;
                input.opened = true;
                isFirst = false;
            } else {
                input.selected = false;
                input.opened = false;
            }
        };

        for (let j = 0; j < groupList.length; ++j) {
            groupList[j].children = groupList[j].behaviorRules;

            for (let k = 0; k < groupList[j].children.length; ++k) {
                if (groupList[j].children[k].actions === null) {
                    groupList[j].children[k].actions = [];
                }

                prepareRule(groupList[j].children[k]);
            }
        }
    };

    this.getBehaviorGroups = function () {
        let behaviorRuleSet = this.getBehaviorRuleSet();

        let groupList;
        groupList = behaviorRuleSet.behaviorGroups;
        makeBehaviorGroupIds(groupList);
        prepareRules(groupList);

        return groupList;
    };

    this.getConnectorDefaultOperators = function () {
        return ['AND', 'OR'];
    };

    this.getNextIdForBehaviorGroup = function () {
        return uid.next();
    };

    this.getNextIdForBehaviorRule = function () {
        return uid.next();
    };

    this.getNextIdForBehaviorRuleExtension = function () {
        return uid.next();
    };

    this.getNextIdGlobal = function () {
        return uid.next();
    };

    this.getInputMatcherDefaultExpression = function () {
        return '*'
    };

    this.getMaxOccurrenceDefaultContext = function () {
        return 'ever';
    };

    this.removeBehaviorRule = function (behaviorRuleName, completion) {
        IRestBehaviorStore.removeBehaviorRule({
            id: this.dataProviderState.getActiveId(),
            ruleName: behaviorRuleName,
            $callback: completion
        });
    };

    this.getDefaultDependencyReference = function () {
        let rulesNames = application.jsonRepresentationManager.getRuleNames();
        if (rulesNames.length > 0) {
            return rulesNames[0];
        } else {
            throw new InconsistentStateDetectedException('No ruleNames exist to be referenced.');
        }
    };

    this.getOccurrenceDefaultRuleName = function () {
        return this.getDefaultDependencyReference();
    };

    this.getResultSizeDefaultMin = function () {
        return 3;
    };

    this.getResultSizeDefaultMax = function () {
        return 17;
    };

    this.getOutputReferenceDefaultInputValue = function () {
        return 'part';
    };

    this.getOutputReferenceDefaultFilter = function () {
        return 'property(*)';
    };

    this.getPossibleValuesForOccurrence = function () {
        return ['ever', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30];
    };

    this.getPossibleValuesForOutputReferenceInputValue = function () {
        return ['part', 'equal'];
    };

    this.getInputMatcherDefaultOccurrence = function () {
        return 'currentStep';
    };

    this.getPossibleValuesForInputmatcherOccurrence = function () {
        return ['currentStep', 'lastStep', 'anyStep', 'never'];
    };

    /* RegularDictionary */
    this.readRegularDictionary = function (id, version, limit, index, filter, order) {
        let request = {id: id, version: version};
        request.limit = limit;
        if (typeof index !== 'undefined' && index > 0) {
            request.index = index;
        }
        if (typeof filter !== 'undefined' && filter.length > 0) {
            request.filter = filter;
        }
        if (typeof order !== 'undefined' && order.length > 0) {
            request.order = order;
        }
        let regularDictionaryConfiguration = IRestRegularDictionaryStore.readRegularDictionary(request);

        this.dataProviderState.setRegularDictionaryConfigurationLanguage(regularDictionaryConfiguration.language);

        return regularDictionaryConfiguration;
    };

    this.readActiveRegularDictionary = function (limit, index, filter, order) {
        return this.readRegularDictionary(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), limit, index, filter, order);
    };

    this.patchRegularDictionary = function (id, version, patchInstructions, completion) {
        IRestRegularDictionaryStore.patchRegularDictionary({
            id: id,
            version: version,
            $entity: patchInstructions,
            $callback: completion
        });
    };

    this.patchActiveRegularDictionary = function (patchInstructions, completion) {
        this.patchRegularDictionary(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), patchInstructions, completion);
    };

    this.createRegularDictionary = function (regularDictionaryConfiguration) {
        return IRestRegularDictionaryStore.createRegularDictionary({$entity: regularDictionaryConfiguration});
    };

    this.deleteRegularDictionary = function (id, version) {
        IRestRegularDictionaryStore.deleteRegularDictionary({id: id, version: version});
    };

    /* Packages */
    this.readActivePackage = function () {
        let tmp = IRestPackageStore.readPackage({
            id: this.dataProviderState.getActiveId(),
            version: this.dataProviderState.getActiveVersion()
        });

        if (tmp.packageExtensions === null) {
            tmp.packageExtensions = [];
        }

        return tmp;
    };

    this.updateActivePackage = function (packageJSON, completion) {
        IRestPackageStore.updatePackage({
            id: this.dataProviderState.getActiveId(),
            version: this.dataProviderState.getActiveVersion(),
            $entity: packageJSON,
            $callback: completion
        });
    };

    this.createPackage = function (package) {
        return IRestPackageStore.createPackage({$entity: package});
    };

    this.deletePackage = function (id, version) {
        IRestPackageStore.deletePackage({id: id, version: version});
    };

    this.updateResourceInPackage = function (id, version, uri, completion) {
        return IRestPackageStore.updateResourceInPackage({
            id: id,
            version: version,
            $entity: uri,
            $callback: completion
        });
    };

    /* Output */
    this.readOutputSet = function (id, version, limit, index, filter, order) {
        let request = {id: id, version: version};
        request.limit = limit;
        if (typeof index !== 'undefined' && index > 0) {
            request.index = index;
        }
        if (typeof filter !== 'undefined' && filter.length > 0) {
            request.filter = filter;
        }
        if (typeof order !== 'undefined' && order.length > 0) {
            request.order = order;
        }

        return IRestOutputStore.readOutputSet(request);
    };

    this.readActiveOutputSet = function (limit, index, filter, order) {
        return this.readOutputSet(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), limit, index, filter, order);
    };

    this.patchOutputSet = function (id, version, patchInstructions, completion) {
        IRestOutputStore.patchOutputSet({id: id, version: version, $entity: patchInstructions, $callback: completion});
    };

    this.patchActiveOutputSet = function (patchInstructions, completion) {
        this.patchOutputSet(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), patchInstructions, completion);
    };

    this.createOutputSet = function (outputConfigurationSet) {
        return IRestOutputStore.createOutputSet({$entity: outputConfigurationSet});
    };

    this.deleteOutputSet = function (id, version) {
        IRestOutputStore.deleteOutputSet({id: id, version: version});
    };

    /** Extensions */
    this.readExtensionDefinitions = function (namespace) {
        return IRestExtensionStore.readExtensionDescriptors({filter: namespace, limit: 0});
    };

    this.readExtension = function (id, version) {
        return IRestExtensionStore.readExtension({id: id, version: version});
    };

    /** Administration */
    this.deployBot = function (environment, id, version, completion) {

        IRestBotAdministration.deployBot({
            $apiURL: REST.apiURL,
            environment: environment,
            botId: id,
            version: version,
            $callback: completion
        });
    };

    this.getDeploymentStatus = function (environment, id, version, completion) {
        return IRestBotAdministration.getDeploymentStatus({
            $apiURL: REST.apiURL,
            environment: environment,
            botId: id,
            version: version,
            $callback: completion
        });
    };

    /** Version info */
    this.getCurrentVersionForResource = function (uri) {
        let params = SLSUriParser(uri);
        let retVal;

        switch (params.host) {
            case 'ai.labs.package':
                retVal = IRestPackageStore.getCurrentVersion({id: params.id});
                break;
            case 'ai.labs.bot':
                retVal = IRestBotStore.getCurrentVersion({id: params.id});
                break;
            case 'ai.labs.behavior':
                retVal = IRestBehaviorStore.getCurrentVersion({id: params.id});
                break;
            case 'ai.labs.output':
                retVal = IRestOutputStore.getCurrentVersion({id: params.id});
                break;
            case 'ai.labs.regulardictionary':
                retVal = IRestRegularDictionaryStore.getCurrentVersion({id: params.id});
                break;
            default:
                break;
        }

        return retVal;
    };

    /** User */
    this.readUser = function (id) {
        return IRestUserStore.readUser({userId: id});
    };

    /** Monitor */
    this.readConversationDescriptors = function (botId, botVersion, conversationState, viewState, limit, index, filter, order) {
        let request = {};
        request.limit = limit;
        if (typeof index !== 'undefined' && index > 0) {
            request.index = index;
        }
        if (typeof filter !== 'undefined' && filter.length > 0) {
            request.filter = filter;
        }
        if (typeof order !== 'undefined' && order.length > 0) {
            request.order = order;
        }
        if (typeof botId !== 'undefined' && botId.length > 0) {
            request.botId = botId;
        }
        if (typeof botVersion !== 'undefined' && botVersion.length > 0) {
            request.botVersion = botVersion;
        }
        if (typeof conversationState !== 'undefined' && conversationState.length > 0) {
            request.conversationState = conversationState;
        }
        if (typeof viewState !== 'undefined' && viewState.length > 0) {
            request.viewState = viewState;
        }

        return IRestMonitorStore.readConversationDescriptors(request);
    };

    this.readActiveConversationLog = function () {
        return IRestMonitorStore.readConversationLog({
            conversationId: this.dataProviderState.getActiveId(),
            view: "FULL"
        });
    };

    /* Testing */
    this.readActiveTestCase = function () {
        return IRestTestCaseStore.readTestCase({id: this.dataProviderState.getActiveId()});
    };

    this.readTestCaseDescriptors = function (botId, botVersion, limit, index, filter, order) {
        let request = {};

        request.limit = limit;
        if (typeof index !== 'undefined' && index > 0) {
            request.index = index;
        }
        if (typeof filter !== 'undefined' && filter.length > 0) {
            request.filter = filter;
        }
        if (typeof order !== 'undefined' && order.length > 0) {
            request.order = order;
        }
        if (typeof botId !== 'undefined' && botId.length > 0) {
            request.botId = botId;
        }
        if (typeof botVersion !== 'undefined' && botVersion.length > 0) {
            request.botVersion = botVersion;
        }

        return IRestTestCaseStore.readTestCaseDescriptors(request);
    };

    this.patchTestCaseDescription = function (id, version, patchInstructions, completion) {
        IRestTestCaseStore.patchDescriptor({
            id: id,
            version: version,
            $entity: patchInstructions,
            $callback: completion
        });
    };

    this.deleteTestCase = function (id, version) {
        IRestTestCaseStore.deleteTestCase({id: id, version: version});
    };

    this.createTestCase = function (conversationId) {
        IRestTestCaseStore.createTestCase({$entity: conversationId});
    };

    this.runTestCase = function (id, version, completion) {
        IRestTestCaseRuntime.runTestCase({id: id, version: version, $callback: completion});
    };

    this.getRunTestCaseStatus = function (id, version, completion) {
        return IRestTestCaseRuntime.getTestCaseState({id: id, version: version, $callback: completion});
    };

    this.readExpressions = function (id, version, filter) {
        return IRestRegularDictionaryStore.readExpressions({id: id, version: version, filter: filter});
    };

    this.readPackageExpressions = function (packageId, packageVersion, filter) {
        return IRestExpressions.readExpressions({packageId: packageId, packageVersion: packageVersion, filter: filter});
    };

    this.readOutputKeys = function (id, version, filter) {
        return IRestOutputStore.readOutputKeys({id: id, version: version, filter: filter});
    };

    this.readOutputKeysPackage = function (packageId, packageVersion, filter) {
        return IRestOutputKeys.readOutputKeys({packageId: packageId, packageVersion: packageVersion, filter: filter});
    }
}