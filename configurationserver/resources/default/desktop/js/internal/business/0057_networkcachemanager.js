function NetworkCacheManager() {
    var cache = {};
    var idGenerator = new UIDGenerator();

    this.getNextCacheId = function () {
        return idGenerator.next();
    }

    this.clearCache = function () {
        cache = {};
    }

    this.cachedNetworkCall = function (id, callee, call, arguments) {
        var idString = id.toString();

        if (cache.hasOwnProperty(idString)) {
            /** Return cached value if one exists. */
            return cache[idString];
        } else {
            /** Perform call if value has not been cached. */
            var tmp = call.apply(callee, arguments);

            /** Cache the return value */
            cache[idString] = tmp;

            return tmp;
        }
    }
}