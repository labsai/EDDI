function NetworkCacheManager() {
    let cache = {};
    let idGenerator = new UIDGenerator();

    this.getNextCacheId = function () {
        return idGenerator.next();
    };

    this.clearCache = function () {
        cache = {};
    };

    this.cachedNetworkCall = function (id, callee, call, arguments) {
        let idString = id.toString();

        if (cache.hasOwnProperty(idString)) {
            /** Return cached value if one exists. */
            return cache[idString];
        } else {
            /** Perform call if value has not been cached. */
            let tmp = call.apply(callee, arguments);

            /** Cache the return value */
            cache[idString] = tmp;

            return tmp;
        }
    }
}