const eddi = {};

class Message {
    constructor(text, message_side) {
        this.text = text;
        this.message_side = message_side;
    }

    get draw() {
        return function () {
            const $message = $($('.message_template').clone().html());
            $message.addClass(this.message_side).find('.text').html(this.text);
            $('.messages').append($message);
            return setTimeout(function () {
                return $message.addClass('appeared');
            }, 0);
        };
    }
}

class QuickReply {
    constructor(text, message_side) {
        this.text = text;
        this.message_side = message_side;
    }

    get draw() {
        return function () {
            const _this = this;
            const $quickReply = $('<button/>', {
                text: this.text,
                click: function () {
                    eddi.submitUserMessage(_this.text);
                    eddi.displayMessage(_this.text, 'right');
                    $('.quick_reply').remove();
                }
            });
            $quickReply.addClass(this.message_side);
            $quickReply.addClass('quick_reply');
            $('.messages').append($quickReply);
            return setTimeout(function () {
                return $quickReply.addClass('appeared');
            }, 0);
        };
    }
}

$(function () {
    let message_side = 'right';

    function getMessageText() {
        const $message_input = $('.message_input');
        return $message_input.val();
    }

    eddi.displayMessage = function (text, side) {
        let $messages, message;
        if (text.trim() === '') {
            return;
        }
        $('.message_input').val('');
        $messages = $('.messages');
        message_side = side ? 'right' : 'left';
        message = new Message(text, side);
        message.draw();
        return $messages.animate({scrollTop: $messages.prop('scrollHeight')}, 300);
    };

    function displayQuickReplies(quickReplies) {
        let $messages, quickReply;
        if (quickReplies.length === 0) {
            return;
        }

        $messages = $('.messages');
        for (let i = 0; i < quickReplies.length; i++) {
            quickReply = new QuickReply(quickReplies[i].value, 'left');
            quickReply.draw();
        }
        return $messages.animate({scrollTop: $messages.prop('scrollHeight')}, 300);
    }

    $('.send_message').click(function () {
        const userMessage = getMessageText();
        eddi.submitUserMessage(userMessage);
        return eddi.displayMessage(userMessage, 'right');
    });
    $('.message_input').keyup(function (e) {
        if (e.which === 13) {
            const userMessage = getMessageText();
            eddi.submitUserMessage(userMessage);
            return eddi.displayMessage(userMessage, 'right');
        }
    });

    eddi.submitUserMessage = function (userMessage) {
        $.ajax({
            type: "POST",
            url: "/bots/" + eddi.environment + "/" + eddi.botId + "/" + eddi.conversationId,
            data: userMessage,
            contentType: "text/plain",
            success: function () {
                setTimeout(loadConversationLog, 2000);
            }
        });
    };

    const loadConversationLog = function () {
        $.get("/bots/" + eddi.environment + "/" + eddi.botId + "/" + eddi.conversationId).done(function (data) {
            refreshConversationLog(data);
        });
    };

    const refreshConversationLog = function (conversationMemory) {
        const conversationState = conversationMemory.conversationState;

        if (conversationState === 'ERROR') {
            log('ERROR', "An Error has occurred. Please contact the administrator!");
            return;
        }

        if (conversationState === 'IN_PROGRESS') {
            setTimeout(loadConversationLog, 1000);
            return;
        }

        let ioList = [];
        for (let i = 0; i < conversationMemory.conversationSteps.length; i++) {
            let step = conversationMemory.conversationSteps[i];

            let input = null;
            let output = null;
            let media = null;
            let action = null;
            let quickReplies = [];
            for (let x = 0; x < step.data.length; x++) {
                let obj = step.data[x];
                if (obj.key.indexOf('input') === 0) {
                    input = obj.value;
                } else if (obj.key.indexOf('media') === 0) {
                    media = obj.value;
                } else if (obj.key.indexOf('actions') === 0) {
                    action = obj.value;
                } else if (obj.key.indexOf('output:quickreply') === 0) {
                    pushArray(quickReplies, obj.value);
                } else if (obj.key.indexOf('output') === 0) {
                    output = obj.value;
                }
            }

            ioList.push({input: input, output: output, media: media, quickReplies: quickReplies});
        }

        if (ioList.length > 0) {
            let latestInteraction = ioList[ioList.length - 1];
            if (latestInteraction === 'undefined') {
                latestInteraction = {};
            }

            if (latestInteraction.output === null) {
                latestInteraction.output = '';
            }

            eddi.displayMessage(latestInteraction.output, 'left');
            displayQuickReplies(latestInteraction.quickReplies);

            $('.message_input').focus();
        }
    };

    const pushArray = function (arr, arr2) {
        arr.push.apply(arr, arr2);
    };

    const deployBot = function (environment, botId, botVersion) {
        $.post("/administration/" + environment + "/deploy/" + botId + "?version=" + botVersion).done(function () {
            checkBotDeploymentStatus();
        });
    };

    const checkBotDeploymentStatus = function () {
        $.get("/administration/" + eddi.environment + "/deploymentstatus/" + eddi.botId + "?version=" + eddi.botVersion).done(function (data) {
            if (data === 'IN_PROGRESS') {
                setTimeout(checkBotDeploymentStatus, 1000);
            } else if (data === 'ERROR') {
                alert('An error occurred while bot deployment');
            } else if (data === 'READY') {
                proceedConversation();
            }
        });
    };

    const createConversation = function (environment, botId) {
        $.post("/bots/" + environment + "/" + botId).done(function (data, status, xhr) {
            const conversationUriArray = xhr.getResponseHeader('Location').split("/");
            eddi.conversationId = conversationUriArray[conversationUriArray.length - 1];
            proceedConversation();
        });
    };

    const checkConversationStatus = function (environment, botId, conversationId) {
        $.get("/bots/" + environment + "/" + botId + "/" + conversationId).always(function (data, status) {
            if (status === "error") {
                alert("Checking conversation has yield into an error.. ");
            } else if (status === "success") {
                eddi.conversationState = data.conversationState;
                if (eddi.conversationState !== 'READY') {
                    alert("Conversation is not Ready... (state=" + eddi.conversationState + ")");
                }

                loadConversationLog();
            }
        });
    };

    const getQueryParts = function (href) {
        const query = $.url.parse(href);
        const path = query.path;

        const parts = path.split("/");

        let environment = null;
        let botId = null;
        let conversationId = null;
        let botVersion = null;

        environment = typeof parts[2] !== 'undefined' ? decodeURIComponent(parts[2]) : environment;
        botId = typeof parts[3] !== 'undefined' ? decodeURIComponent(parts[3]) : botId;
        conversationId = typeof parts[4] !== 'undefined' ? decodeURIComponent(parts[4]) : conversationId;
        if (query.params && query.params.version) {
            botVersion = query.params.version;
        }

        return {conversationId: conversationId, environment: environment, botId: botId, botVersion: botVersion};
    };

    const proceedConversation = function () {
        if (!eddi.conversationId) {
            createConversation(eddi.environment, eddi.botId);
        } else {
            checkConversationStatus(eddi.environment, eddi.botId, eddi.conversationId);
        }
    };

    const checkBotDeployment = function () {
        //check if bot is deployed
        $.get("/administration/" + eddi.environment + "/deploymentstatus/" + eddi.botId + "?version=" + eddi.botVersion)
            .done(function (data) {
                if (data === 'NOT_FOUND') {
                    if (confirm('Bot is not deployed at the moment.. Deploy latest version NOW?')) {
                        deployBot(eddi.environment, eddi.botId, eddi.botVersion);
                    }
                }

                if (data === 'ERROR') {
                    alert('Bot encountered an server error :-(');
                }

                if (data === 'IN_PROGRESS') {
                    alert('Bot is still warming up...');
                }

                if (data === 'READY') {
                    proceedConversation();
                }
            });
    };

    $(document).ready(function () {
        const extractedParams = getQueryParts(window.location.href);
        //extract environment from URL
        if (extractedParams.environment !== null) {
            eddi.environment = extractedParams.environment;
        }

        //extract botId from URL
        if (extractedParams.botId !== null) {
            eddi.botId = extractedParams.botId;
        }

        //extract conversationId
        if (extractedParams.conversationId !== null) {
            eddi.conversationId = extractedParams.conversationId;
        }

        //extract conversationId
        if (extractedParams.botVersion !== null) {
            eddi.botVersion = extractedParams.botVersion;
            checkBotDeployment();
        } else {
            $.get("/botstore/bots/" + eddi.botId + "/currentversion", function (data) {
                eddi.botVersion = data;
                checkBotDeployment();
            });
        }
    });
});