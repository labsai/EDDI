function JSONRepresentationManager() {
    let backingData;

    this.clone = function () {
        let retVal = new JSONRepresentationManager();

        retVal.setBackingData(jQuery.extend(true, [], backingData.children));

        return retVal;
    };

    this.getRuleSetView = function () {
        let retVal = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRuleConfigurationSet');

        retVal.behaviorGroups = backingData.children;

        for (let i = 0; i < retVal.behaviorGroups.length; ++i) {
            retVal.behaviorGroups[i].behaviorRules = retVal.behaviorGroups[i].children;
        }

        return retVal;
    };

    function NoSuchElementException(msg) {
        this.message = msg;
    }

    this.setBackingData = function (backingDataIn) {
        backingData = {id: undefined, children: backingDataIn};
    };

    this.getBackingData = function () {
        return backingData;
    };

    this.makeCleanBehaviorGroup = function (behaviorGroup) {
        let retVal = jQuery.extend(true, {}, behaviorGroup);

        delete retVal.id;
        delete retVal.selected;
        delete retVal.opened;
        delete retVal.children;
        delete retVal.editable;

        return retVal;
    };

    this.makeCleanBehaviorRule = function (behaviorRule) {
        let cleanElement = function (element) {
            if (element.hasOwnProperty('children')) {
                for (let i = 0; i < element.children.length; ++i) {
                    cleanElement(element.children[i]);
                }
            }

            delete element.id;
            delete element.selected;
            delete element.opened;
            delete element.editable;
        };

        let retVal = jQuery.extend(true, {}, behaviorRule);

        cleanElement(retVal);

        for (let i = 0; i < retVal.groups.length; ++i) {
            let group = retVal.groups[i];
            cleanElement(group);
        }

        return retVal;
    };

    let getElementAndParentWithId = function (id, parent, data) {
        if (data.hasOwnProperty('children')) {
            for (let i = 0; i < data.children.length; ++i) {
                try {
                    return getElementAndParentWithId(id, data, data.children[i]);
                } catch (ex) {
                    if (ex instanceof NoSuchElementException) {
                        /** Keep searching. */
                        continue;
                    }
                }
            }
        }

        if (data.id === id) {
            return {parent: parent, element: data};
        }

        throw new NoSuchElementException('No such element with id: ' + id);
    };

    this.deleteElementWithIdFromSet = function (id, dataset) {
        let result = getElementAndParentWithId(id, undefined, dataset);

        let index = result.parent.children.indexOf(result.element);

        if (index !== -1) {
            result.parent.children.splice(index, 1);
        } else {
            console.log('Inconsistency Error: Could not find a computed child element in the children array of element: ' + result.parent);
        }
    };

    this.deleteElementWithId = function (id) {
        this.deleteElementWithIdFromSet(id, backingData);
    };

    this.getElementWithIdInSet = function (id, dataset) {
        let result = getElementAndParentWithId(id, undefined, dataset);

        return result.element;
    };

    this.getElementWithId = function (id) {
        return this.getElementWithIdInSet(id, backingData);
    };

    this.copyElementWithId = function (id) {
        let result = getElementAndParentWithId(id, undefined, backingData);

        let retVal = jQuery.extend(true, {}, result.element);
        return retVal;
    };

    this.getParentWithId = function (id) {
        let result = getElementAndParentWithId(id, undefined, backingData);

        return result.parent;
    };

    this.updateGroupName = function (oldName, newName) {
        let mainGroup = this.getGroupWithName(oldName);
        mainGroup.name = newName;
    };

    this.updateElementWithId = function (id, propertyName, propertyValue) {
        let result = getElementAndParentWithId(id, undefined, backingData);

        let properties = propertyName.split('.');

        let element = result.element;
        for (let i = 0; i < properties.length; ++i) {
            if (i === properties.length - 1) {
                element[properties[i]] = propertyValue;
            } else {
                element = element[properties[i]];
            }
        }
    };

    this.addRootElement = function (element) {
        backingData.children.push(element);
    };

    this.addRootElementAtIndex = function (element, index) {
        backingData.children.splice(index, 0, element);
    };

    this.addChildElementAtIdAndIndexInSet = function (id, index, element, dataset) {
        let parent = this.getElementWithIdInSet(id, dataset);

        if (!parent.hasOwnProperty('children')) {
            parent.children = [];
        }

        parent.children.splice(index, 0, element);
    };

    this.addChildElementAtIdInSet = function (id, element, dataset) {
        let parent = this.getElementWithIdInSet(id, dataset);

        if (!parent.hasOwnProperty('children')) {
            parent.children = [];
        }

        parent.children.push(element);
    };

    this.addChildElementAtIdAndIndex = function (id, index, element) {
        this.addChildElementAtIdAndIndexInSet(id, index, element, backingData);
    };

    this.addChildElementAtId = function (id, element) {
        this.addChildElementAtIdInSet(id, element, backingData);
    };

    this.hasGroupWithName = function (name) {
        for (let i = 0; i < backingData.children.length; ++i) {
            if (backingData.children[i].name === name) {
                return true;
            }
        }

        return false;
    };

    this.getGroupWithName = function (name) {
        for (let i = 0; i < backingData.children.length; ++i) {
            if (backingData.children[i].name === name) {
                return backingData.children[i];
            }
        }

        throw new NoSuchElementException('No group exists for name ' + name + '.');
    };

    this.hasRuleWithName = function (name) {
        for (let i = 0; i < backingData.children.length; ++i) {
            let group = backingData.children[i];

            if (group.hasOwnProperty('children')) {
                for (let j = 0; j < group.children.length; ++j) {
                    let rule = group.children[j];

                    if (rule.name === name) {
                        return true;
                    }
                }
            }
        }

        return false;
    };

    this.getRuleNames = function () {
        let ruleNames = [];

        for (let i = 0; i < backingData.children.length; ++i) {
            let group = backingData.children[i];

            if (group.hasOwnProperty('children')) {
                for (let j = 0; j < group.children.length; ++j) {
                    let rule = group.children[j];

                    if (ruleNames.indexOf(rule.name) === -1) {
                        ruleNames.push(rule.name);
                    }
                }
            }
        }

        return ruleNames.sort();
    }
}