javascript:(function () {
    window.botmarklet = {css:{}, js:{}, jQuery:false, launch:function (file) {
        if (!file) {
            return false;
        }
        this.loadJS(file, function () {
            var options = window.botmarklet.options || {};
            window.botmarklet.execute(options);
        });
    }, execute:function (options) {
        if (typeof(options.css) !== "object") {
            if (options.css) {
                options.css = [options.css];
            } else {
                options.css = [];
            }
        }
        if (typeof(options.js) !== "object") {
            if (options.js) {
                options.js = [options.js];
            } else {
                options.js = [];
            }
        }
        if (options.css.length) {
            var i;
            for (i in options.css) {
                window.botmarklet.loadCSS(options.css[i]);
            }
        }
        if (options.jquery) {
            options.js.unshift(options.jquery);
        }
        window.botmarklet.loadMultipleJS(options.js, function () {
            if (options.jquery) {
                if (!window.botmarklet.jQuery) {
                    window.botmarklet.jQuery = window.jQuery.noConflict(true);
                }
                window.botmarklet.jQuery(options.ready);
            } else {
                options.ready();
            }
        });
    }, loadMultipleJS:function (files, onload) {
        if (files.length === 0) {
            if (onload) {
                onload();
            }
            return true;
        }
        this.loadJS(files.shift(), function () {
            window.botmarklet.loadMultipleJS(files, onload);
        });
    }, loadJS:function (file, onload) {
        var element = this.loadedJS(file);
        if (element) {
            if (typeof onload === "function") {
                onload.call(element);
            }
            return false;
        }
        element = document.createElement("script");
        element.type = "text/javascript";
        element.setAttribute("charset", "UTF-8");
        element.src = file;
        if (!document.attachEvent) {
            element.onload = onload;
        } else {
            if (typeof onload === "function") {
                element.onreadystatechange = function () {
                    if (element.readyState === "complete" || element.readyState === "loaded") {
                        onload.call(element);
                        element.onreadystatechange = null;
                    }
                };
            }
        }
        document.body.appendChild(element);
        this.js[file] = element;
        return element;
    }, loadCSS:function (file) {
        if (this.loadedCSS(file)) {
            return false;
        }
        var element = document.createElement("link");
        element.setAttribute("rel", "stylesheet");
        element.setAttribute("type", "text/css");
        element.setAttribute("href", file);
        document.getElementsByTagName("head")[0].appendChild(element);
        this.css[file] = element;
        return element;
    }, loadedJS:function (file) {
        if (this.js[file]) {
            return this.js[file];
        }
        return false;
    }, loadedCSS:function (file) {
        if (this.css[file]) {
            return this.css[file];
        }
        return false;
    }, die:function () {
        var i;
        for (i in this.js) {
            this.js[i].parentNode.removeChild(this.js[i]);
        }
        for (i in this.css) {
            this.css[i].parentNode.removeChild(this.css[i]);
        }
        this.js = {};
        this.css = {};
        this.jQuery = false;
    }};
    window.eddi = {};
    window.eddi.baseUrl = "%s";
    window.eddi.environment = "%s";
    window.eddi.botId = "%s";
    window.botmarklet.execute({jquery:"http://ajax.googleapis.com/ajax/libs/jquery/1/jquery.min.js",
        js:[%s], 
        css:[%s], ready:function ($) {
    	window.eddi.jquery = $.noConflict(true);
        window.eddi.init();
    }});
}());
