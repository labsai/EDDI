// --------------------------------------- //

// Recursive function that goes through the set of messages it is given
function createMessage(messagesArray, i, response) {

    // i is optional - i is the current message in the array the system is displaying
    i = typeof i !== 'undefined' ? i : 0;

    // response is optional - response is a boolean that refers to whether the set of messages is a response to a question or the question itself
    response = typeof response !== 'undefined' ? response : 0;

    // add this HTML to the front and back of the message for #style
    const htmlWrapperBeginning = "<div class=\"line\"><div class=\"message message-left animated fadeInUp bubbleLeft\">",
        htmlWrapperEnding = "</div></div>";

    // If this message is not the first, use the previous to calculate a delay, otherwise use a number
    const delay = (i > 0) ? calculateDelay(messagesArray[i - 1]) : 1000;


    // delay override - Make first responses quick
    /*if (!response && questions[currentQuestion].intro && i === 0) {
        delay = 50;
    }*/

    setTimeout(function () {
        // if it's the last message in the series
        if (i < messagesArray.length) {
            let message = '';

            let messageElement = messagesArray[i];
            if (typeof messageElement === 'string') {
                message = messageElement;
            } else if (typeof messageElement === 'object') {
                let type = messageElement.type;
                switch (type) {
                    case 'html':
                    case 'text':
                        message = messageElement.text;
                        break;
                    case 'image':
                }
            }

            $('#chat-container').append(htmlWrapperBeginning + message + htmlWrapperEnding);
            //Special case for chat
            if ($('.active').attr('id') === "chat") {
                smoothScrollBottom();
            }
            i++;
            createMessage(messagesArray, i, response);
        }
        // if it's not the last message, display the next one
        else {
            createAnswerField(delay);
            return 1;
        }
    }, delay);
}

// Creates an answer input bubble
function createAnswerField() {
    const htmlAnswerField = "<div id=\"answer-container\" class=\"line\">" +
        "<form action=\"#\" onsubmit=\"return false;\">" +
        "<input type=\"text\" name=\"answer\" id=\"answer\" class=\"message message-right animated fadeInUp\" value=\"\" placeholder=\"Type a message…\">" +
        "</form><div class=\"clear\"></div></div>";

    /*if (questions[currentQuestion].ending) {
        return 1;
    }*/

    $('#chat-container').append(htmlAnswerField);

    $('#answer').keyup(function (event) {
        if (event.keyCode === 13) {
            let answer = $.trim($('#answer').val());
            if (answer !== "") {
                $('#answer-container').remove();
                createAnswerMessage(answer);
            } else {
                $('#answer').removeClass('shake').removeClass('fadeInUp');
                $('#answer').addClass('shake');
                $('#answer').one('webkitAnimationEnd mozAnimationEnd MSAnimationEnd oanimationend animationend', function () {
                    $(this).removeClass('shake').removeClass('fadeInUp');
                });
            }
        }
    });

    $('#answer').focus();

    //Special case for chat
    if ($('.active').attr('id') === "chat") {
        smoothScrollBottom();
    }
}

function createAnswerMessage(answer) {
    const htmlWrapperBeginning = "<div class=\"line\">" +
        "<div class=\"message message-right animated fadeInUp bubbleRight\">",
        htmlWrapperEnding = "</div></div><div class=\"clear\"></div>";

    $('#chat-container').append(htmlWrapperBeginning + answer + htmlWrapperEnding);
}

// Calculates the delay based on whatever string you give it
function calculateDelay(string) {
    // 275 words per minute in milliseconds plus a constant
    const delayPerWord = 218;
    let delay = string.split(" ").length * delayPerWord;
    delay = (delay < delayPerWord * 3) ? delay + 400 : delay;
    return delay;
}

function smoothScrollBottom() {
    $('html,body').animate({scrollTop: $(document).height()}, 1000);
}

// Tabs
function tabHandler() {
    $tab = $('#menu ul li');
    $content = $('.content');
    $defaultTab = $('#chat');
    var animationOver = true;

    $defaultTab.addClass('active');
    $("#" + $defaultTab.attr('data-content')).addClass('activeContent');

    $tab.click(function () {
        // If Active when you click
        if (!$(this).hasClass('active')) {
            animationOver = false;
            var tabContent = "#" + $(this).attr('data-content');

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
            if ($('.active').attr('id') === "chat") {
                smoothScrollBottom();
                $(".message").each(function () {
                    $(this).removeClass('tada').removeClass('fadeInUp').addClass('fadeIn');
                });
            }

            //Special case for about
            if ($('.active').attr('id') === "about") {
                $('html,body').animate({scrollTop: 0}, 0);
            }
        }
    });
}

// })(); END GLOBAL
$(document).ready(function () {
    new tabHandler();
});
