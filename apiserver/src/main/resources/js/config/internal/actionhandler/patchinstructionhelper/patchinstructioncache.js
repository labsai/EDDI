function PatchInstructionCache(dataProvider) {
    let instructionCache = [];

    let addInstructionsToCache = function (patchInstructions, completion) {
        for (let i = 0; i < patchInstructions.length; ++i) {
            instructionCache.push(patchInstructions[i]);
        }

        let id = application.dataProvider.dataProviderState.getActiveId();
        let version = application.dataProvider.dataProviderState.getActiveVersion();

        let uri = application.url.getUriForResource(id, version);

        completion(200, {responseText: ""}, uri);
    };

    this.patchActiveOutputSet = function (patchInstructions, completion) {
        addInstructionsToCache(patchInstructions, completion);
    };

    this.patchActiveRegularDictionary = function (patchInstructions, completion) {
        addInstructionsToCache(patchInstructions, completion);
    };

    this.flushCache = function (completion) {
        if (application.url.getCurrentPage() === 'dictionaries') {
            dataProvider.patchActiveRegularDictionary(instructionCache, completion);
        } else if (application.url.getCurrentPage() === 'outputs') {
            dataProvider.patchActiveOutputSet(instructionCache, completion);
        }
    };

    this.updateActiveVersion = function () {
        /** NOP */
    }
}