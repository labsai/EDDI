/*
 * EDDI login theme — accessibility wiring.
 *
 * This theme is otherwise pure CSS. This file exists because CSS cannot add ARIA
 * attributes, and two gaps in Keycloak's rendered markup can only be closed by
 * setting them:
 *
 *  1. Field errors are rendered as <span id="input-error-<field>"> and the field
 *     is marked aria-invalid="true", but aria-describedby is never set. The
 *     message is announced once through the live region at page load; a screen
 *     reader user who then tabs to the field hears "invalid entry" with no
 *     reason. Associating the two makes the error available whenever the field
 *     has focus (WCAG 3.3.1).
 *
 *  2. Untouched fields carry aria-invalid="" — an empty value is not valid ARIA
 *     and is treated as true by some assistive technology, which would announce
 *     a valid field as invalid.
 *
 * No event handlers, no network access, no user input is read. It runs once.
 */
(function () {
    "use strict";

    var ERROR_ID_PREFIX = "input-error-";

    function associateErrorsWithFields() {
        var errors = document.querySelectorAll('[id^="' + ERROR_ID_PREFIX + '"]');

        for (var i = 0; i < errors.length; i++) {
            var error = errors[i];
            var field = document.getElementById(error.id.slice(ERROR_ID_PREFIX.length));
            if (!field) {
                continue;
            }

            var describedBy = (field.getAttribute("aria-describedby") || "")
                .split(/\s+/)
                .filter(Boolean);

            if (describedBy.indexOf(error.id) === -1) {
                describedBy.push(error.id);
                field.setAttribute("aria-describedby", describedBy.join(" "));
            }
        }
    }

    function dropEmptyAriaInvalid() {
        var fields = document.querySelectorAll('[aria-invalid=""]');
        for (var i = 0; i < fields.length; i++) {
            fields[i].removeAttribute("aria-invalid");
        }
    }

    function wire() {
        associateErrorsWithFields();
        dropEmptyAriaInvalid();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", wire);
    } else {
        wire();
    }
})();
