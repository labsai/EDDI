

/**
 * The DataProvider object that supplies REST data objects.
 *
 * @author Patrick Schwab
 */
function DataProvider() {
    var nextId = 10001;
    var uid = new UIDGenerator();

    this.dataProviderState = new DataProviderState();

//    console.log('The active document ID is ' + this.dataProviderState.getActiveId() + '.');
//    console.log('The active document version is ' + this.dataProviderState.getActiveVersion() + '.');

    this.setActiveVersion = function (version) {
        console.log('current version is: ' + version);
        this.dataProviderState.setActiveVersion(version);
    }

    /* Resources */
    this.getI18nResource = function (language, location, completion) {
        IRestI18n.getI18nResource({language:language, location:location, $callback:completion});
    }

    /* Document Descriptions */
    this.readDocumentDescriptions = function (type, limit, index, filter, order) {
        var request = {};
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
        var documentDescriptions = IRestDocumentDescriptorStore.readDescriptors(request);

        return documentDescriptions;
    }

    this.readDocumentDescription = function (id, version) {
        return IRestDocumentDescriptorStore.readDescriptor({id:id, version:version});
    }

    var descriptorCache = null;
    this.readActiveDocumentDescription = function () {
        if (descriptorCache === null) {
            return descriptorCache = this.readDocumentDescription(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion());
        } else {
            return descriptorCache;
        }
    }

    this.patchDocumentDescription = function (id, version, patchInstructions, completion) {
        IRestDocumentDescriptorStore.patchDescriptor({id:id, version:version, $entity:patchInstructions, $callback:completion});
    }

    /* Bots */
    this.getNextIdForBots = function () {
        return nextId++;
    }

    this.readBot = function (id, version) {
        return IRestBotStore.readBot({id:id, version:version});
    }

    this.readActiveBot = function () {
        return this.readBot(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion());
    }

    this.updateBot = function (id, version, bot, completion) {
        IRestBotStore.updateBot({id:id, version:version, $entity:bot, $callback:completion });
    }

    this.updateActiveBot = function (bot, completion) {
        this.updateBot(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), bot, completion);
    }

    this.createBot = function (bot) {
        return IRestBotStore.createBot({$entity:bot});
    }

    this.deleteBot = function (id, version) {
        IRestBotStore.deleteBot({id:id, version:version});
    }

    this.updateResourceInBot = function (id, version, uri, completion) {
        return IRestBotStore.updateResourceInBot({id:id, version:version, $entity:uri, $callback:completion});
    }

    /**
     this.getBehaviorRuleSet = function() {
     var configs = IRestPackageStore.getPackage({ packageId: 'package1' }).configurations;

     for(var i = 0; i < configs.length; ++i) {
     if(configs[i].type == "behavior") {
     var behaviorRulesText = configs[i].configurationDocument;
     var behaviorRules = jQuery.parseJSON(behaviorRulesText);

     return behaviorRules.behaviorRules;
     }
     }
     } */

    this.setBehaviorRule = function (behaviorRuleDescription, completion) {
        IRestBehaviorStore.setBehaviorRule({ id:this.dataProviderState.getActiveId(), $callback:completion, $entity:behaviorRuleDescription});
    }

    var groupExistsWithId = function (groups, id) {
        for (var i = 0; i < groups.length; ++i) {
            if (groups[i].id == id) {
                return true;
            }
        }

        return false;
    }

    var containsGroup = function (groups, group) {
        for (var i = 0; i < groups.length; ++i) {
            if (groups[i].groupName == group.groupName) {
                return true;
            }
        }

        return false;
    }

    var makeGroupList = function (behaviorRuleSet) {
        var groupList = [];
        var hasLostGroup = false;

        for (var i = 0; i < behaviorRuleSet.length; ++i) {
            var groups = behaviorRuleSet[i].groups;
            if (groups.length > 0) {
                for (var j = 0; j < groups.length; ++j) {
                    groupList.push(groups[j]);
                }
            } else {
                if (!hasLostGroup) {
                    groupList.push({groupName:application.configuration.lostAndFoundGroupName, rankingId:0});
                }
            }
        }

        return groupList;
    }

    var makeBehaviorGroupIds = function (groups) {
        var isFirst = true;

        for (var i = 0; i < groups.length; ++i) {
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
    }

    this.getBehaviorRuleSet = function () {
        var tmp = IRestBehaviorStore.readBehaviorRuleSet({id:this.dataProviderState.getActiveId(), version:this.dataProviderState.getActiveVersion()});

        console.log(tmp);
        return tmp;
    }

    this.updateBehaviorRuleSet = function (behaviorRuleSet, completion) {
        IRestBehaviorStore.updateBehaviorRuleSet({id:this.dataProviderState.getActiveId(), version:this.dataProviderState.getActiveVersion(), $entity:behaviorRuleSet, $callback:completion});
    }

    this.createBehaviorRuleSet = function (behaviorRuleConfigurationSet) {
        return IRestBehaviorStore.createBehaviorRuleSet({$entity:behaviorRuleConfigurationSet});
    }

    this.deleteBehaviorRuleSet = function (id, version) {
        IRestBehaviorStore.deleteBehaviorRuleSet({id:id, version:version});
    }

    var getGroupForName = function (groupList, name) {
        for (var i = 0; i < groupList.length; ++i) {
            if (groupList[i].groupName == name) {
                return groupList[i];
            }
        }

        return undefined;
    }

    var addBehaviorRuleExtensions = function (groupList, behaviorRuleSet) {
        var isFirst = true;

        for (var i = 0; i < behaviorRuleSet.length; ++i) {
            var rule = behaviorRuleSet[i];

            /** Add id. */
            var prepareRule = function (input) {
                for (var i = 0; i < input.children.length; ++i) {
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
            }

            prepareRule(rule);

            var groups = rule.groups;
            if (groups.length > 0) {
                for (var j = 0; j < groups.length; ++j) {
                    var group = getGroupForName(groupList, groups[j].groupName);

                    if (!group.children) {
                        group.children = [];
                    }

                    group.children.push(rule);
                }
            } else {
                var group = getGroupForName(groupList, application.configuration.lostAndFoundGroupName);

                if (!group.children) {
                    group.children = [];
                }

                group.children.push(rule);
            }
        }
    }

    var prepareRules = function (groupList) {
        var isFirst = true;

        var prepareRule = function (input) {
            for (var i = 0; i < input.children.length; ++i) {
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
        }

        for (var j = 0; j < groupList.length; ++j) {
            groupList[j].children = groupList[j].behaviorRules;

            for (var k = 0; k < groupList[j].children.length; ++k) {
                if (groupList[j].children[k].actions == null) {
                    groupList[j].children[k].actions = [];
                }

                prepareRule(groupList[j].children[k]);
            }
        }
    }

    this.getBehaviorGroups = function () {
        var behaviorRuleSet = this.getBehaviorRuleSet();

        var groupList;
        groupList = behaviorRuleSet.behaviorGroups;
        makeBehaviorGroupIds(groupList);
        prepareRules(groupList);

        return groupList;
    }

    this.getConnectorDefaultOperators = function () {
        return ['AND', 'OR'];
    }

    this.getNextIdForBehaviorGroup = function () {
        return uid.next();
    }

    this.getNextIdForBehaviorRule = function () {
        return uid.next();
    }

    this.getNextIdForBehaviorRuleExtension = function () {
        return uid.next();
    }

    this.getNextIdGlobal = function () {
        return uid.next();
    }

    this.getInputMatcherDefaultExpression = function () {
        return '*'
    }

    this.getMaxOccurrenceDefaultContext = function () {
        return 'ever';
    }

    this.removeBehaviorRule = function (behaviorRuleName, completion) {
        IRestBehaviorStore.removeBehaviorRule({id:this.dataProviderState.getActiveId(), ruleName:behaviorRuleName, $callback:completion});
    }

    this.getDefaultDependencyReference = function () {
        var rulesNames = application.jsonRepresentationManager.getRuleNames();
        if (rulesNames.length > 0) {
            return rulesNames[0];
        } else {
            throw InconsistentStateDetectedException('No ruleNames exist to be referenced.');
        }
    }

    this.getOccurrenceDefaultRuleName = function () {
        return this.getDefaultDependencyReference();
    }

    this.getResultSizeDefaultMin = function () {
        return 3;
    }

    this.getResultSizeDefaultMax = function () {
        return 17;
    }

    this.getOutputReferenceDefaultInputValue = function () {
        return 'part';
    }

    this.getOutputReferenceDefaultFilter = function () {
        return 'property(*)';
    }

    this.getPossibleValuesForOccurrence = function () {
        return ['ever', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30];
    }

    this.getPossibleValuesForOutputReferenceInputValue = function () {
        return ['part', 'equal'];
    }

    this.getInputMatcherDefaultOccurrence = function () {
        return 'current';
    }

    this.getPossibleValuesForInputmatcherOccurrence = function () {
        return ['current', 'past', 'last'];
    }

    /* RegularDictionary */
    this.readRegularDictionary = function (id, version, limit, index, filter, order) {
        var request = {id:id, version:version};
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
        var regularDictionaryConfiguration = IRestRegularDictionaryStore.readRegularDictionary(request);

        this.dataProviderState.setRegularDictionaryConfigurationLanguage(regularDictionaryConfiguration.language);

        return regularDictionaryConfiguration;
    }

    this.readActiveRegularDictionary = function (limit, index, filter, order) {
        return this.readRegularDictionary(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), limit, index, filter, order);
    }

    this.patchRegularDictionary = function (id, version, patchInstructions, completion) {
        IRestRegularDictionaryStore.patchRegularDictionary({id:id, version:version, $entity:patchInstructions, $callback:completion});
    }

    this.patchActiveRegularDictionary = function (patchInstructions, completion) {
        this.patchRegularDictionary(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), patchInstructions, completion);
    }

    this.createRegularDictionary = function (regularDictionaryConfiguration) {
        return IRestRegularDictionaryStore.createRegularDictionary({$entity:regularDictionaryConfiguration});
    }

    this.deleteRegularDictionary = function (id, version) {
        IRestRegularDictionaryStore.deleteRegularDictionary({id:id, version:version});
    }

    /* Packages */
    this.readActivePackage = function () {
        var tmp = IRestPackageStore.readPackage({id:this.dataProviderState.getActiveId(), version:this.dataProviderState.getActiveVersion()});

        if (tmp.packageExtensions === null) {
            tmp.packageExtensions = [];
        }

        return tmp;
    }

    this.updateActivePackage = function (packageJSON, completion) {
        IRestPackageStore.updatePackage({id:this.dataProviderState.getActiveId(),
            version:this.dataProviderState.getActiveVersion(),
            $entity:packageJSON,
            $callback:completion});
    }

    this.createPackage = function (package) {
        return IRestPackageStore.createPackage({$entity:package});
    }

    this.deletePackage = function (id, version) {
        IRestPackageStore.deletePackage({id:id, version:version});
    }

    this.updateResourceInPackage = function (id, version, uri, completion) {
        return IRestPackageStore.updateResourceInPackage({id:id, version:version, $entity:uri, $callback:completion});
    }

    /* Output */
    this.readOutputSet = function (id, version, limit, index, filter, order) {
        var request = {id:id, version:version};
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
        var outputConfigurationSet = IRestOutputStore.readOutputSet(request);

        return outputConfigurationSet;
    }

    this.readActiveOutputSet = function (limit, index, filter, order) {
        return this.readOutputSet(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), limit, index, filter, order);
    }

    this.patchOutputSet = function (id, version, patchInstructions, completion) {
        IRestOutputStore.patchOutputSet({id:id, version:version, $entity:patchInstructions, $callback:completion});
    }

    this.patchActiveOutputSet = function (patchInstructions, completion) {
        this.patchOutputSet(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion(), patchInstructions, completion);
    }

    this.createOutputSet = function (outputConfigurationSet) {
        return IRestOutputStore.createOutputSet({$entity:outputConfigurationSet});
    }

    this.deleteOutputSet = function (id, version) {
        IRestOutputStore.deleteOutputSet({id:id, version:version});
    }

    /** Extensions */
    this.readExtensionDefinitions = function (namespace) {
        return IRestExtensionStore.readExtensionDescriptors({filter:namespace, limit:0});
    }

    this.readExtension = function (id, version) {
        return IRestExtensionStore.readExtension({id:id, version:version});
    }

    /** Administration */
    this.deployBot = function (environment, id, version, completion) {
        keycloak.updateToken(30).success(function() {
            IRestBotAdministration.deployBot({$authorization:keycloak.token, $apiURL:uiEngineServer, environment:environment, botId:id, version:version, $callback:completion});
        }).error(function() {
            console.log('Failed to refresh token');
        });
    }

    this.getDeploymentStatus = function (environment, id, version, completion) {
        return IRestBotAdministration.getDeploymentStatus({$authorization:keycloak.token, $apiURL:uiEngineServer, environment:environment, botId:id, version:version, $callback:completion});
    }

    /** Version info */
    this.getCurrentVersionForResource = function (uri) {
        var params = SLSUriParser(uri);
        var retVal;

        switch (params.host) {
            case 'io.sls.package':
                retVal = IRestPackageStore.getCurrentVersion({id:params.id});
                break;
            case 'io.sls.bot':
                retVal = IRestBotStore.getCurrentVersion({id:params.id});
                break;
            case 'ai.labs.behavior':
                retVal = IRestBehaviorStore.getCurrentVersion({id:params.id});
                break;
            case 'ai.labs.output':
                retVal = IRestOutputStore.getCurrentVersion({id:params.id});
                break;
            case 'ai.labs.regulardictionary':
                retVal = IRestRegularDictionaryStore.getCurrentVersion({id:params.id});
                break;
            default:
                break;
        }

        return retVal;
    }

    /** User */
    this.readUser = function (id) {
        return IRestUserStore.readUser({userId:id});
    }

    /** Monitor */
    this.readConversationDescriptors = function (botId, botVersion, conversationState, viewState, limit, index, filter, order) {
        var request = {};
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

        var conversationLogs = IRestMonitorStore.readConversationDescriptors(request);

        return conversationLogs;
    }

    this.readActiveConversationLog = function () {
        return IRestMonitorStore.readConversationLog({conversationId:this.dataProviderState.getActiveId(), view:"FULL"});
    }

    /* Testing */
    this.readActiveTestCase = function () {
        //return { "expectedResult":{ "id":"50ab8a13c10a1e986609a070", "botId":"50a0de1ec10af51b48a6fd06", "botVersion":7, "conversationState":"READY", "conversationSteps":[ { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"", "timestamp":1353420379752 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"Hallo Harald!Expected", "timestamp":1353420379752 } ] } ] }, { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"input:initial", "result":"Hallo", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"greeting(hallo)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"", "timestamp":1353420379752 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"input:initial", "result":"Hallo", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"greeting(hallo)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"", "timestamp":1353420379752 } ] } ] }, { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"input:initial", "result":"Trottel", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(trottel)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379752 }, { "key":"output:simple", "result":"Das ist nicht nett!", "timestamp":1353420379752 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"input:initial", "result":"Trottel!", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(trottel)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379752 }, { "key":"output:simple", "result":"Das ist nicht nett!!", "timestamp":1353420379752 } ] } ] }, { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"input:initial", "result":"Dummkopf", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(dummkopf)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379753 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379753 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379753 }, { "key":"output:simple", "result":"Das will ich nicht mehr hÃ¶ren!", "timestamp":1353420379753 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"input:initial", "result":"Dummkopf", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(dummkopf)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion", "schwacheBeleidigungsReaktion1" ], "timestamp":1353420379753 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379753 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379753 }, { "key":"output:simple", "result":"Das will ich nicht mehr hÃ¶ren!", "timestamp":1353420379753 } ] } ] } ] }, "actualResult":{ "id":"50ab8a13c10a1e986609a070", "botId":"50a0de1ec10af51b48a6fd06", "botVersion":7, "conversationState":"READY", "conversationSteps":[ { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"", "timestamp":1353420379752 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"Hallo Harald", "timestamp":1353420379752 } ] } ] }, { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"input:initial", "result":"Hallo", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"greeting(hallo)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"", "timestamp":1353420379752 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"input:initial", "result":"Hallo", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"greeting(hallo)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"output:simple", "result":"", "timestamp":1353420379752 } ] } ] }, { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"input:initial", "result":"Trottel", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(trottel)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379752 }, { "key":"output:simple", "result":"Das ist nicht nett!", "timestamp":1353420379752 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"input:initial", "result":"Trottel", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(trottel)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379752 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379752 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379752 }, { "key":"output:simple", "result":"Das ist nicht nett!", "timestamp":1353420379752 } ] } ] }, { "packages":[ { "context":"Harald Pkg Nov 1", "lifecycleTasks":[ { "key":"input:initial", "result":"Dummkopf", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(dummkopf)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion" ], "timestamp":1353420379753 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379753 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379753 }, { "key":"output:simple", "result":"Das will ich nicht mehr hÃ¶ren!", "timestamp":1353420379753 } ] }, { "context":"Harald Pkg Nov 2", "lifecycleTasks":[ { "key":"input:initial", "result":"Dummkopf", "timestamp":1353420379752 }, { "key":"expressions:parsed", "result":"schwacheBeleidigung(dummkopf)", "timestamp":1353420379752 }, { "key":"behavior_rules:success", "result":[ "schwacheBeleidigungsReaktion", "schwacheBeleidigungsReaktion1", "schwacheBeleidigungsReaktion2" ], "timestamp":1353420379753 }, { "key":"behavior_rules:droppedSuccess", "result":[], "timestamp":1353420379753 }, { "key":"behavior_rules:fail", "result":[], "timestamp":1353420379753 }, { "key":"output:simple", "result":"Das will ich nicht mehr hÃ¶ren!", "timestamp":1353420379753 }, { "key":"output:email", "result":"Das will ich nicht mehr hÃ¶ren!", "timestamp":1353420379753 } ] } ] } ] } };
        return IRestTestCaseStore.readTestCase({id:this.dataProviderState.getActiveId()});
    }

    this.readTestCaseDescriptors = function (botId, botVersion, limit, index, filter, order) {
        var request = {};

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

//        return [
//            { "_id":{ "$oid":"50530211d2b8b3b97721495a" }, "name":"TestCase1", "resource":"resource://io.sls.testcases/testcasestore/testcases/50530311d2c8b3b97721495a?version=1", "lastModified":1347617297734, "description":"TestCase1 Desc.", "deleted":false, "author":"resource://io.sls.user/userstore/users/505301b9d2b8d8f272f92f19", "lastModifiedBy":"resource://io.sls.user/userstore/users/505301b9d2b8d8f272f92f19", "created":1347617297734, "lastRun":1347617307734, "lastRunResult":"SUCCESS", "_version":1 },
//            { "_id":{ "$oid":"50530211d2b8b3b97721495a" }, "name":"TestCase2", "resource":"resource://io.sls.testcases/testcasestore/testcases/50530311d2c8b3b97721495b?version=1", "lastModified":1347617297734, "description":"TestCase2 Desc.", "deleted":false, "author":"resource://io.sls.user/userstore/users/505301b9d2b8d8f272f92f19", "lastModifiedBy":"resource://io.sls.user/userstore/users/505301b9d2b8d8f272f92f19", "created":1347617297734, "lastRun":1347617307734, "lastRunResult":"SUCCESS", "_version":1 }
//        ];

        var testCaseDescriptors = IRestTestCaseStore.readTestCaseDescriptors(request);

        return testCaseDescriptors;
    }

//    this.readTestCaseDescription = function (id, version) {
//        return IRestTestCaseStore.readDescriptor({id:id, version:version});
//    }
//
//    var testCaseDescriptorCache = null;
//    this.readActiveTestCaseDescription = function () {
//        if (testCaseDescriptorCache === null) {
//            return testCaseDescriptorCache = this.readTestCaseDescription(this.dataProviderState.getActiveId(), this.dataProviderState.getActiveVersion());
//        } else {
//            return testCaseDescriptorCache;
//        }
//    }

    this.patchTestCaseDescription = function (id, version, patchInstructions, completion) {
        IRestTestCaseStore.patchDescriptor({id:id, version:version, $entity:patchInstructions, $callback:completion});
    }

    this.deleteTestCase = function (id, version) {
        IRestTestCaseStore.deleteTestCase({id:id, version:version});
    }

    this.createTestCase = function (conversationId) {
        IRestTestCaseStore.createTestCase({$entity:conversationId});
    }

    this.runTestCase = function (id, version, completion) {
        IRestTestCaseRuntime.runTestCase({id:id, version:version, $callback:completion});
    }

    this.getRunTestCaseStatus = function (id, version, completion) {
        return IRestTestCaseRuntime.getTestCaseState({id:id, version:version, $callback:completion});
    }

    this.readExpressions = function (id, version, filter) {
        return IRestRegularDictionaryStore.readExpressions({id:id, version:version, filter:filter});
    }

    this.readPackageExpressions = function (packageId, packageVersion, filter) {
        return IRestExpressions.readExpressions({packageId:packageId, packageVersion:packageVersion, filter:filter});
    }

    this.readOutputKeys = function (id, version, filter) {
        return IRestOutputStore.readOutputKeys({id:id, version:version, filter:filter});
    }

    this.readOutputKeysPackage = function (packageId, packageVersion, filter) {
        return IRestOutputKeys.readOutputKeys({packageId:packageId, packageVersion:packageVersion, filter:filter});
    }
}