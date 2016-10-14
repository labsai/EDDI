function PatchInstructionCache(dataProvider) {
    var instructionCache = [];

    var addInstructionsToCache = function(patchInstructions, completion) {
        for(var i = 0; i < patchInstructions.length; ++i) {
            instructionCache.push(patchInstructions[i]);
        }

        var id = application.dataProvider.dataProviderState.getActiveId();
        var version = application.dataProvider.dataProviderState.getActiveVersion();

        var uri = application.url.getUriForResource(id, version);

        completion(200, {responseText:""}, uri);
    }

    this.patchActiveOutputSet = function(patchInstructions, completion) {
        addInstructionsToCache(patchInstructions, completion);
    }

    this.patchActiveRegularDictionary = function(patchInstructions, completion) {
        addInstructionsToCache(patchInstructions, completion);
    }

    this.flushCache = function(completion) {
        if(application.url.getCurrentPage() == 'dictionaries') {
            dataProvider.patchActiveRegularDictionary(instructionCache, completion);
        } else if(application.url.getCurrentPage() == 'outputs') {
            dataProvider.patchActiveOutputSet(instructionCache, completion);
        }
    }

    this.updateActiveVersion = function() {
        /** NOP */
    }
}