// <![CDATA[
$(document).ready(function () {
    $("#welcomeMsg").ready(function () {
        $("#chat-container").show();

    });
});

// ]]>

/**
 Display Menu when JS is enabled in browser
 **/
// <![CDATA[
$(document).ready(function () {
    $("#menu").show();
    $("div").removeClass("displayNone");

});

// ]]>

// <![CDATA[
$(document).ready(function () {
    $("#debug-container").hide();

    $("#chat").click(function () {
        $("#about-container").hide();
        $("#debug-container").hide();
        $("#chat-container").show();
    });
    $("#about").click(function () {
        $("#about-container").show();
        $("#debug-container").hide();
        $("#chat-container").hide();
    });
    $("#debug").click(function () {
        if (!$("#debug-container").is(":visible")) {
            $("#debug-container").show();
        }
    });
});

// ]]>

//Greetings End

function displayQuickReplies(quickReplies) {
    if (typeof quickReplies === 'undefined' || quickReplies.length === 0) {
        return false;
    }

    let quickReply;
    for (let i = 0; i < quickReplies.length; i++) {
        quickReply = new QuickReply(quickReplies[i].value);
        quickReply.draw();
    }

    return true;
}

// Recursive function that goes through the set of messages it is given
function createMessage(outputArray, quickRepliesArray, hasConversationEnded, i) {
    // i is optional - i is the current message in the array the system is displaying
    i = typeof i !== 'undefined' ? i : 0;

    if (outputArray.length > 0) {
        // If this message is not the first, use the previous to calculate a delay, otherwise use a number
        let messageObject = outputArray[i];
        let message = null;
        let inputField = null;
        let button = null;
        if (typeof messageObject === 'string') {
            message = messageObject;
        } else if (typeof messageObject === 'object') {
            switch (messageObject.type) {
                case 'text':
                case 'title':
                    message = messageObject.text;
                    break;
                case 'image':
                    message = '<img src="' + messageObject.uri + '" width="100%" alt="image send by the bot" />';
                    preLoadImage(messageObject.uri);
                    break;
                case 'textInput':
                    inputField = messageObject;
                    break;
                case 'button':
                    button = messageObject;
                    break;
                case 'quickReplies':
                    quickRepliesArray.push({value: messageObject.value, expressions: messageObject.expressions});
                    break;
                default:
                    console.log('output type is not recognized ' + messageObject.type);
            }
        }

        // delay override - Make first responses quick
        let delay;
        if (eddi.skipDelay || message == null || (i === 0 && eddi.isFirstMessage)) {
            delay = 50;
            eddi.isFirstMessage = false;
        } else {
            delay = calculateDelay(message);
        }

        let typingIndicator = new Message('<img src="/binary/img/typing-indicator.svg" />');
        typingIndicator.draw();
        smoothScrolling();

        setTimeout(function () {

            typingIndicator.remove();

            if (message != null) {
                const msg = new Message(message);
                msg.draw();
            }

            if (inputField != null) {
                createAnswerField(inputField);
            }

            if (button != null) {
                createButton(button);
            }

            smoothScrolling();
            if (i + 1 < outputArray.length) {
                createMessage(outputArray, quickRepliesArray, hasConversationEnded, ++i);
            } else {
                if (!hasConversationEnded && !displayQuickReplies(quickRepliesArray)) {
                    if (inputField == null && button == null) {
                        createAnswerField();
                        smoothScrolling();
                    }
                }

                if (hasConversationEnded) {
                    new ConversationEnd('CONVERSATION ENDED').draw();
                    eddi.createConversation(eddi.environment, eddi.botId);
                }
            }
        }, delay);
    } else {
        createAnswerField();
    }
}

function createButton(button) {
    let buttonObj = new Button(button);
    buttonObj.draw();
    smoothScrolling();
}

// Creates an answer input bubble
function createAnswerField(inputField) {
    let inputFieldObj;

    if (typeof inputField !== 'undefined') {
        inputFieldObj = new InputField(inputField.placeholder, inputField.defaultValue);
    } else {
        inputFieldObj = new InputField();
    }

    inputFieldObj.draw();
    smoothScrolling();
}

// Calculates the delay based on whatever string you give it
function calculateDelay(text) {
    let delay;
    if (text.startsWith('<')) {
        delay = 50;
    } else {
        let delayPerWord = 218;
        delay = text.split(" ").length * delayPerWord;
        delay = (delay < delayPerWord * 3) ? delay + 400 : delay;

        if (delay > 3000) {
            delay = 3000;
        }
    }
    return delay;
}

function smoothScrolling() {
    //Special case for chat
    if ($(".active").attr('id') === "chat") {
        smoothScrollBottom();
    }
}

function smoothScrollBottom() {
    $('html,body').animate({
        scrollTop: $(document).height()
    }, eddi.skipDelay ? 50 : 1000);
}

function preLoadImage(photoUrl) {
    //create image to preload:
    let imgPreload = new Image();
    $(imgPreload).attr({
        src: photoUrl
    });

    if (!imgPreload.complete && imgPreload.readyState === 2) {
        $(imgPreload).load(function (response, status, xhr) {
            // don't do anything
        });
    }
}

// Tabs
let $defaultTab = $('#chat');
let $tab;
let $content;

function tabHandler() {
    $tab = $('#menu ul li');
    $content = $('.content');
    let animationOver = true;

    $defaultTab.addClass('active');
    $("#" + $defaultTab.attr('data-content')).addClass('activeContent');

    $tab.click(function () {
        // If Active when you click
        if (!$(this).hasClass('active')) {
            animationOver = false;
            let tabContent = "#" + $(this).attr('data-content');

            // Make tab active
            $tab.removeClass('active');
            $(this).addClass('active');

            // Remove old content
            if ($('.activeContent') !== $(tabContent)) {
                $('.activeContent').hide().removeClass('activeContent');
            }

            // Make content active
            $(tabContent).show().addClass('activeContent');

            //Special case for chat
            if ($(".active").attr('id') === "chat") {
                smoothScrollBottom();
                $(".message").each(function () {
                    $(this).removeClass('tada').removeClass('fadeInUp').addClass('fadeIn');
                });
            }

            //Special case for about
            if ($(".active").attr('id') === "about" || $(".active").attr('id') === "debug") {
                $('html,body').animate({scrollBottom: 0}, 0);
            }
        }
    });
}


// })(); END GLOBAL

$(document).ready(function () {
    tabHandler();
});
