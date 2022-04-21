/** --------------------------------------------------------------------------
 *    jQuery URL Decoder
 *    Version 1.0
 *    Parses URL and return its components. Can also build URL from components
 *
 * ---------------------------------------------------------------------------
 *    HOW TO USE:
 *
 *    $.url.decode('http://username:password@hostname/path?arg1=value%40+1&arg2=touch%C3%A9#anchor')
 *    // returns
 *    // http://username:password@hostname/path?arg1=value@ 1&arg2=touchÃ©#anchor
 *    // Note: "%40" is replaced with "@", "+" is replaced with " " and "%C3%A9" is replaced with "Ã©"
 *
 *    $.url.encode('file.htm?arg1=value1 @#456&amp;arg2=value2 touchÃ©')
 *    // returns
 *    // file.htm%3Farg1%3Dvalue1%20%40%23456%26arg2%3Dvalue2%20touch%C3%A9
 *    // Note: "@" is replaced with "%40" and "Ã©" is replaced with "%C3%A9"
 *
 *    $.url.parse('http://username:password@hostname/path?arg1=value%40+1&arg2=touch%C3%A9#anchor')
 *    // returns
 *    {
 *        source: 'http://username:password@hostname/path?arg1=value%40+1&arg2=touch%C3%A9#anchor',
 *        protocol: 'http',
 *        authority: 'username:password@hostname',
 *        userInfo: 'username:password',
 *        user: 'username',
 *        password: 'password',
 *        host: 'hostname',
 *        port: '',
 *        path: '/path',
 *        directory: '/path',
 *        file: '',
 *        relative: '/path?arg1=value%40+1&arg2=touch%C3%A9#anchor',
 *        query: 'arg1=value%40+1&arg2=touch%C3%A9',
 *        anchor: 'anchor',
 *        params: {
 *            'arg1': 'value@ 1',
 *            'arg2': 'touchÃ©'
 *        }
 *    }
 *
 *    $.url.build({
 *        protocol: 'http',
 *        username: 'username',
 *        password: 'password',
 *        host: 'hostname',
 *        path: '/path',
 *        query: 'arg1=value%40+1&arg2=touch%C3%A9',
 *        // or
 *        //params: {
 *        //    'arg1': 'value@ 1',
 *        //    'arg2': 'touchÃ©'
 *        //}
 *        anchor: 'anchor',
 *    })
 *    // returns
 *    // http://username:password@hostname/path?arg1=value%40+1&arg2=touch%C3%A9#anchor
 *
 * ---------------------------------------------------------------------------
 * OTHER PARTIES' CODE:
 *
 * Parser based on the Regex-based URI ai.labs.parser by Steven Levithan.
 * For more information visit http://blog.stevenlevithan.com/archives/parseuri
 *
 * Deparam taken from jQuery BBQ by Ben Alman. Dual licensed under the MIT and GPL licenses (http://benalman.com/about/license/)
 * http://benalman.com/projects/jquery-bbq-plugin/
 *
 * ---------------------------------------------------------------------------

 */
jQuery.url = function () {

    /**
     * private function to encode URL
     *
     * @param {String} string //required
     * @return {String}
     */
    function utf8_encode(string) {
        string = string.replace(/\r\n/g, "\n");
        var utftext = "";

        for (var n = 0; n < string.length; n++) {

            var c = string.charCodeAt(n);

            if (c < 128) {
                utftext += String.fromCharCode(c);
            } else if ((c > 127) && (c < 2048)) {
                utftext += String.fromCharCode((c >> 6) | 192);
                utftext += String.fromCharCode((c & 63) | 128);
            } else {
                utftext += String.fromCharCode((c >> 12) | 224);
                utftext += String.fromCharCode(((c >> 6) & 63) | 128);
                utftext += String.fromCharCode((c & 63) | 128);
            }

        }

        return utftext;
    }

    /**
     * private function to decode URL
     *
     * @param {String} utftext //required
     * @return {String}
     */
    function utf8_decode(utftext) {
        var string = "";
        var i = 0;
        var c = 0;
        var c2 = 0;

        while (i < utftext.length) {

            c = utftext.charCodeAt(i);

            if (c < 128) {
                string += String.fromCharCode(c);
                i++;
            } else if ((c > 191) && (c < 224)) {
                c2 = utftext.charCodeAt(i + 1);
                string += String.fromCharCode(((c & 31) << 6) | (c2 & 63));
                i += 2;
            } else {
                c2 = utftext.charCodeAt(i + 1);
                c3 = utftext.charCodeAt(i + 2);
                string += String.fromCharCode(((c & 15) << 12) | ((c2 & 63) << 6) | (c3 & 63));
                i += 3;
            }

        }

        return string;
    }


    /**
     * private function to convert urlencoded query string to javascript object
     *
     * @param {String} params //required
     * @param {Boolean} coerce //optional
     * @return {Object}
     *
     * @author Ben Alman
     */
    function deparam(params, coerce) {
        var obj = {},
            coerce_types = {
                'true': !0,
                'false': !1,
                'null': null
            };

        // Iterate over all name=value pairs.
        $.each(params.replace(/\+/g, ' ').split('&'), function (j, v) {
            var param = v.split('=');
            /* edited: hd: to avoid false query params added to the params array. */
            if (param.length !== 2 || !param[0] || !param[1]) {
                return true;
            }

            var key = decode(param[0]),
                val, cur = obj,
                i = 0,

                // If key is more complex than 'foo', like 'a[]' or 'a[b][c]', split it
                // into its component parts.
                keys = key.split(']['),
                keys_last = keys.length - 1;

            // If the first keys part contains [ and the last ends with ], then []
            // are correctly balanced.
            if (/\[/.test(keys[0]) && /\]$/.test(keys[keys_last])) {
                // Remove the trailing ] from the last keys part.
                keys[keys_last] = keys[keys_last].replace(/\]$/, '');

                // Split first keys part into two parts on the [ and add them back onto
                // the beginning of the keys array.
                keys = keys.shift().split('[').concat(keys);

                keys_last = keys.length - 1;
            } else {
                // Basic 'foo' style key.
                keys_last = 0;
            }

            // Are we dealing with a name=value pair, or just a name?
            if (param.length === 2) {
                val = decode(param[1]);

                // Coerce values.
                if (coerce) {
                    val = val && !isNaN(val) ? +val // number
                        : val === 'undefined' ? undefined // undefined
                            : coerce_types[val] !== undefined ? coerce_types[val] // true, false, null
                                : val; // string
                }

                if (keys_last) {
                    // Complex key, build deep object structure based on a few rules:
                    // * The 'cur' pointer starts at the object top-level.
                    // * [] = array push (n is set to array length), [n] = array if n is
                    //   numeric, otherwise object.
                    // * If at the last keys part, set the value.
                    // * For each keys part, if the current level is undefined create an
                    //   object or array based on the type of the next keys part.
                    // * Move the 'cur' pointer to the next level.
                    // * Rinse & repeat.
                    for (; i <= keys_last; i++) {
                        key = keys[i] === '' ? cur.length : keys[i];
                        cur = cur[key] = i < keys_last ? cur[key] || (keys[i + 1] && isNaN(keys[i + 1]) ? {} : []) : val;
                    }

                } else {
                    // Simple key, even simpler rules, since only scalars and shallow
                    // arrays are allowed.
                    if ($.isArray(obj[key])) {
                        // val is already an array, so push on the next value.
                        obj[key].push(val);

                    } else if (obj[key] !== undefined) {
                        // val isn't an array, but since a second value has been specified,
                        // convert val into an array.
                        obj[key] = [obj[key], val];

                    } else {
                        // val is a scalar.
                        obj[key] = val;
                    }
                }

            } else if (key) {
                // No value was defined, so set something meaningful.
                obj[key] = coerce ? undefined : '';
            }
        });

        return obj;
    }

    /**
     * private function to parse URL to components
     *
     * @param {String} url_str //optional, if omited using current location
     * @return {Object}
     */
    function parse(url_str) {
        url_str = url_str || window.location;

        /**
         * @author of RegExp Steven Levithan
         */
        var re = /^(?:(?![^:@]+:[^:@\/]*@)([^:\/?#.]+):)?(?:\/\/)?((?:(([^:@]*):?([^:@]*))?@)?([^:\/?#]*)(?::(\d*))?)(((\/(?:[^?#](?![^?#\/]*\.[^?#\/.]+(?:[?#]|$)))*\/?)?([^?#\/]*))(?:\?([^#]*))?(?:#(.*))?)/;

        var keys = ["source", "protocol", "authority", "userInfo", "user", "password", "host", "port", "relative", "path", "directory", "file", "query", "anchor"];

        var m = re.exec(url_str);
        var uri = {};
        var i = keys.length;

        while (i--) {
            uri[keys[i]] = m[i] || "";
        }
        /*
         uri.params = {};

         uri.query.replace( /(?:^|&)([^&=]*)=?([^&]*)/g, function ( $0, $1, $2 ) {
         if ($1) {
         uri.params[decode($1)] = decode($2);
         }
         });
         */
        if (uri.query) {
            uri.params = deparam(uri.query, true);
        }

        return uri;
    }


    /**
     * private function to build URL string from components
     *
     * @param {Object} url_obj //required
     * @return {String}
     */
    function build(url_obj) {

        if (url_obj.source) {
            return encodeURI(url_obj.source);
        }

        var resultArr = [];

        if (url_obj.protocol) {
            if (url_obj.protocol == 'file') {
                resultArr.push('file:///');
            } else if (url_obj.protocol == 'mailto') {
                resultArr.push('mailto:');
            } else {
                resultArr.push(url_obj.protocol + '://');
            }
        }

        if (url_obj.authority) {
            resultArr.push(url_obj.authority);
        } else {
            if (url_obj.userInfo) {
                resultArr.push(url_obj.userInfo + '@');
            } else if (url_obj.user) {
                resultArr.push(url_obj.user);
                if (url_obj.password) {
                    resultArr.push(':' + url_obj.password);
                }
                resultArr.push('@');
            }

            if (url_obj.host) {
                resultArr.push(url_obj.host);
                if (url_obj.port) {
                    resultArr.push(':' + url_obj.port);
                }
            }
        }

        if (url_obj.path) {
            resultArr.push(url_obj.path);
        } else {
            if (url_obj.directory) {
                resultArr.push(url_obj.directory);
            }
            if (url_obj.file) {
                resultArr.push(url_obj.file);
            }

        }

        if (url_obj.query) {
            resultArr.push('?' + url_obj.query);
        } else if (url_obj.params) {
            resultArr.push('?' + $.param(url_obj.params));
        }

        if (url_obj.anchor) {
            resultArr.push('#' + url_obj.anchor);
        }

        return resultArr.join('');
    }

    /**
     * wrapper around encoder
     *
     * @param {String} string //required
     * @return {String}
     */
    function encode(string) {
        //return build(parse(string));
        //return escape(utf8_encode(string));
        return encodeURIComponent(string);
    }

    /**
     * wrapper around decoder
     *
     * @param {String} string //optional, if omited using current location
     * @return {String}
     */
    function decode(string) {
        string = string || window.location.toString();
        return utf8_decode(unescape(string.replace(/\+/g, ' ')));
    }

    /**
     * public functions
     *
     * @see #encode
     * @see #decode
     * @see #parse
     * @see #build
     *
     * @return {Object}
     */
    return {
        encode: encode,
        decode: decode,
        parse: parse,
        build: build
    };
}();
