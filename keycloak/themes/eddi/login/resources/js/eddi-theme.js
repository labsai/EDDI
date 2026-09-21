/*
 * EDDI login theme — colour scheme control.
 *
 * The Manager and Workforce UIs both offer a theme switcher on every page, so
 * the login page offers one too. It has three states, matching theirs:
 *
 *   system (default)  follow prefers-color-scheme
 *   light             force light
 *   dark              force dark
 *
 * "system" removes the attribute so the CSS media query governs; the other two
 * set data-eddi-theme on <html>, which the stylesheet keys off. That ordering
 * matters: with no JavaScript, or before this file runs, the page still follows
 * the operating system, so the control is a genuine enhancement rather than a
 * dependency.
 *
 * The choice is stored per origin. It deliberately does NOT sync with the
 * Manager's own setting — Keycloak is served from a different origin, so the
 * Manager's localStorage is unreadable from here. A visitor who has set a theme
 * in the Manager and lands on a login page with a different OS preference will
 * see the OS one until they choose here.
 *
 * The button is icon-only: any visible text would be English on a page that is
 * translated into 30 languages. Its accessible name is English for the same
 * reason Keycloak has no message key for it — flagged rather than
 * machine-translated.
 */
(function () {
    "use strict";

    var STORAGE_KEY = "eddi-login-theme";
    var ORDER = ["system", "light", "dark"];

    var ICONS = {
        /* half-filled circle — "follow the system" */
        system: '<path d="M8 1a7 7 0 100 14A7 7 0 008 1zm0 1.4V13.6a5.6 5.6 0 010-11.2z"/>',
        /* sun */
        light: '<path d="M8 5.2A2.8 2.8 0 108 10.8 2.8 2.8 0 008 5.2zm0-4.2a.7.7 0 01.7.7v1.4a.7.7 0 11-1.4 0V1.7A.7.7 0 018 1zm0 12a.7.7 0 01.7.7v1.4a.7.7 0 11-1.4 0v-1.4A.7.7 0 018 13zM15 8a.7.7 0 01-.7.7h-1.4a.7.7 0 110-1.4h1.4A.7.7 0 0115 8zM3.1 8a.7.7 0 01-.7.7H1a.7.7 0 110-1.4h1.4a.7.7 0 01.7.7zm9.8-4.9a.7.7 0 010 1l-1 1a.7.7 0 11-1-1l1-1a.7.7 0 011 0zM4.1 11.9a.7.7 0 010 1l-1 1a.7.7 0 11-1-1l1-1a.7.7 0 011 0zm8.8 2a.7.7 0 01-1 0l-1-1a.7.7 0 111-1l1 1a.7.7 0 010 1zM4.1 4.1a.7.7 0 01-1 0l-1-1a.7.7 0 011-1l1 1a.7.7 0 010 1z"/>',
        /* moon */
        dark: '<path d="M13.5 10.6A5.9 5.9 0 015.4 2.5a6.3 6.3 0 108.1 8.1z"/>'
    };

    var LABELS = {
        system: "Colour theme: system",
        light: "Colour theme: light",
        dark: "Colour theme: dark"
    };

    function read() {
        try {
            var stored = window.localStorage.getItem(STORAGE_KEY);
            return ORDER.indexOf(stored) === -1 ? "system" : stored;
        } catch (e) {
            return "system"; // private mode / storage disabled
        }
    }

    function persist(value) {
        try {
            if (value === "system") {
                window.localStorage.removeItem(STORAGE_KEY);
            } else {
                window.localStorage.setItem(STORAGE_KEY, value);
            }
        } catch (e) {
            /* Not being able to remember the choice is survivable. */
        }
    }

    function apply(value) {
        if (value === "system") {
            document.documentElement.removeAttribute("data-eddi-theme");
        } else {
            document.documentElement.setAttribute("data-eddi-theme", value);
        }
    }

    function render(button, value) {
        button.innerHTML =
            '<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false" fill="currentColor">' +
            ICONS[value] + "</svg>";
        button.setAttribute("aria-label", LABELS[value]);
        button.setAttribute("title", LABELS[value]);
    }

    /*
     * Keycloak only renders the header-utilities container when it has a locale
     * switcher to put in it. A realm with a single supported locale — the admin
     * console's `master` realm, for one — has no switcher and therefore no
     * container, which left the toggle with nowhere to go and silently absent.
     * Create the container in that case; the stylesheet already styles it.
     */
    function utilitiesHost() {
        var existing = document.querySelector(".pf-v5-c-login__main-header-utilities");
        if (existing) {
            return existing;
        }

        var header = document.querySelector(".pf-v5-c-login__main-header");
        if (!header) {
            return null;
        }

        var created = document.createElement("div");
        created.className = "pf-v5-c-login__main-header-utilities";
        header.appendChild(created);
        return created;
    }

    function build() {
        var host = utilitiesHost();
        if (!host || host.querySelector(".eddi-theme-toggle")) {
            return;
        }

        var current = read();
        apply(current);

        var button = document.createElement("button");
        button.type = "button";
        button.className = "eddi-theme-toggle";
        render(button, current);

        button.addEventListener("click", function () {
            current = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length];
            apply(current);
            persist(current);
            render(button, current);
        });

        host.insertBefore(button, host.firstChild);
    }

    // Apply the stored choice as early as possible, so an explicit preference
    // that differs from the OS does not flash the wrong scheme before the
    // control is built.
    apply(read());

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", build);
    } else {
        build();
    }
})();
