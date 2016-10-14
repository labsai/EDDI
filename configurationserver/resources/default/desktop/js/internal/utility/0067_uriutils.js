function SLSUriParser(uri) {
    var query = $.url.parse(uri);
    var path = query.path;

    var parts = path.split("/");

    if (parts.length < 4 /*|| typeof query.params['version'] === 'undefined'*/) {
        console.log(uri);
        throw new MalformedURLException('Missing path and id in URL. Length was ' + parts.length + '.');
    }

    var id = decodeURIComponent(parts[3]);
    if (typeof  query.params !== 'undefined' && typeof query.params['version'] !== 'undefined') {
        var version = decodeURIComponent(query.params['version']);
    }

    return { id:id, version:version, host:query.host}
}