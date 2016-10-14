function JSONBuilderHelper() {
    this.getExtensionCacheId = function (type) {
        return 'CACHE_EXTENSION_' + type + '_';
    };

    this.getDefinitionCacheId = function (type) {
        return 'CACHE_DEFINITION_' + type + '_';
    };

    this.fetchExtension = function (type) {
        var definitionCacheId = this.getDefinitionCacheId(type);
        var extensionCacheId = this.getExtensionCacheId(type);

        var filterString = type.split('//')[1].split('?')[0];
        var resource = application.networkCacheManager.cachedNetworkCall(definitionCacheId, application.dataProvider,
            application.dataProvider.readExtensionDefinitions, [filterString])[0].resource;

        var retVal = application.url.getVersionAndIdForResource(resource);

        return application.networkCacheManager.cachedNetworkCall(extensionCacheId, application.dataProvider,
            application.dataProvider.readExtension, [retVal.id, retVal.version]);
    };

    this.makeDefaultJSONFromExtension = function (type) {
        var extension = this.fetchExtension(type);

        var retVal = {type:type};

        console.log(extension)

        if (extension.configDefinition) {
            retVal.config = {};
            for (key in extension.configDefinition) {
                retVal.config[key] = extension.configDefinition[key].defaultValue;
            }
        }

        if (extension.extensionPoints) {
            retVal.extensions = {};
            for (var i = 0; i < extension.extensionPoints.length; ++i) {
                var splitStrings = extension.extensionPoints[i].namespace.split('.');

                var relativeNS = splitStrings[splitStrings.length - 1];

                retVal.extensions[relativeNS] = [];
            }
        }

        console.log(retVal)
        return retVal;
    }
}