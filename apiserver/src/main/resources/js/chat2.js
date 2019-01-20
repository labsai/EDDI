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
        $("#chat-container").show();
    });
    $("#about").click(function () {
        $("#chat-container").hide();
        $("#about-container").show();
    });
    $("#debug").click(function () {
        if ($("#debug-container").is(":visible")) {
            $("#debug-container").hide();
        } else {
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
let isFirstMessage = true;

function createMessage(outputArray, quickRepliesArray, i) {

    // i is optional - i is the current message in the array the system is displaying
    i = typeof i !== 'undefined' ? i : 0;

    // If this message is not the first, use the previous to calculate a delay, otherwise use a number
    let messageObject = outputArray[i];
    let message = null;
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
            case 'quickReplies':
                quickRepliesArray.push({value: messageObject.value, expressions: messageObject.expressions});
                break;
            default:
                console.log('output type is not recognized ' + messageObject.type);
        }
    }

    let delay = (i > 0 && i < outputArray.length) ? calculateDelay(message) : 1000;

    // delay override - Make first responses quick
    if (isFirstMessage && i === 0) {
        delay = 50;
        isFirstMessage = false;
    }

    let typingIndicator = new Message('<img src="/binary/img/typing-indicator.svg" style="width: 50px" />');
    typingIndicator.draw();

    setTimeout(function () {
        function smoothScrolling() {
            //Special case for chat
            if ($(".active").attr('id') === "chat") {
                smoothScrollBottom();
            }
        }

        typingIndicator.remove();

        if (message != null) {
            const msg = new Message(message);
            msg.draw();
        }

        smoothScrolling();
        if (i + 1 < outputArray.length) {
            createMessage(outputArray, quickRepliesArray, ++i);
        } else {
            if (!displayQuickReplies(quickRepliesArray)) {
                createAnswerField();
                smoothScrolling();
            }
        }
    }, delay);
}

// Creates an answer input bubble
function createAnswerField() {
    let htmlAnswerField = "<div id=\"answer-container\" class=\"line\"><form action=\"#\" onsubmit=\"return false;\"><input required=\"required\" novalidate=\"novalidate\" autocomplete=\"on\" min=\"2\" max=\"40\" formnovalidate=\"formnovalidate\" autofocus=\"autofocus\" type=\"text\" name=\"answer\" id=\"answer\" class=\"message message-right animated fadeInUp\" value=\"\" placeholder=\"Type your response…\"></form><div class=\"clear\"></div></div>";

    $('#chat-container').append(htmlAnswerField);

    $('#answer').keyup(function (event) {
        if (event.keyCode === 13) {
            let answer = $.trim($('#answer').val());
            if (answer !== "") {
                $('#answer-container').remove();
                createAnswerMessage(answer);
                eddi.submitUserMessage(answer);
            } else {
                $('#answer').removeClass('shake').removeClass('fadeInUp');
                $('#answer').addClass('shake');
                $('#answer').one('webkitAnimationEnd mozAnimationEnd MSAnimationEnd oanimationend animationend',
                    function () {
                        $(this).removeClass('shake').removeClass('fadeInUp');
                    });
            }
        }
    });

    $('#answer').focus();

    //Special case for chat
    if ($(".active").attr('id') === "chat") {
        smoothScrollBottom();
    }
}

function createAnswerMessage(answer) {
    let htmlWrapperBeginning = "<div class=\"line\"><div class=\"message message-right animated fadeInUp bubbleRight\">",
        htmlWrapperEnding = "</div></div><div class=\"clear\"></div>";

    $('.quickReply').remove();
    $('#chat-container').append(htmlWrapperBeginning + answer + htmlWrapperEnding);
}

// Calculates the delay based on whatever string you give it
function calculateDelay(text) {
    let delayPerWord = 120;
    let delay = text.trim().split(" ").length * delayPerWord;
    delay = (delay < delayPerWord * 3) ? delay + 250 : delay;
    return delay;
}

function smoothScrollBottom() {
    $('html,body').animate({scrollTop: $(document).height()}, 1000);
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

function tabHandler() {
    $tab = $('#menu ul li');
    $content = $('.content');
    $defaultTab = $('#chat');
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
            if ($(".active").attr('id') === "about") {
                $('html,body').animate({scrollTop: 0}, 0);
            }
        }
    });
}


// })(); END GLOBAL

$(document).ready(function () {
    tabHandler();
});
