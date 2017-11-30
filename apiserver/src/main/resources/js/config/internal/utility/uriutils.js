function SLSUriParser(uri) {
    let query = $.url.parse(uri);
    let path = query.path;

    let parts = path.split("/");

    if (parts.length < 4 /*|| typeof query.params['version'] === 'undefined'*/) {
        console.log(uri);
        throw new MalformedURLException('Missing path and id in URL. Length was ' + parts.length + '.');
    }

    let id = decodeURIComponent(parts[3]);
    let version = 1;
    if (typeof  query.params !== 'undefined' && typeof query.params['version'] !== 'undefined') {
        version = parseInt(decodeURIComponent(query.params['version']));
    }

    return {id: id, version: version, host: query.host}
}