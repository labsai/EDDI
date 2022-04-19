const eddi = {};
eddi.isFirstConversation = true;
eddi.isFirstMessage = true;
eddi.skipDelay = false;

class Message {
    constructor(text) {
        this.text = text;
        this.element = $('<div>');
    }

    get draw() {
        return function () {
            this.element = $('<div>');
            this.element.addClass('line');
            const $message = $('<div>');
            this.element.append($message);
            $message.addClass('message message-left animated fadeInUp bubbleLeft');
            $message.html(this.text.replace('\n', '<br>'));
            $('#chat-container').append(this.element);
            return setTimeout(function () {
                return $message.addClass('appeared');
            }, 0);
        };
    }

    remove() {
        this.element.remove();
    }
}

class InputField {
    constructor(placeholder, defaultValue, isPassword) {
        this.placeholder = typeof placeholder !== 'undefined' ? placeholder : 'Type your response…';
        this.defaultValue = typeof defaultValue !== 'undefined' ? defaultValue : '';
        this.isPassword = typeof isPassword !== 'undefined' ? isPassword : false;
        this.element = $('<div>');
    }

    get draw() {
        return function () {
            this.element = $('<div>');
            this.element.addClass('line textInputContainer');
            const $inputField = $('<input type="' + (this.isPassword ? 'password' : 'text') + '" name="answer" required="required" autocomplete="off" autofocus="autofocus">');
            if (this.placeholder !== '') {
                $inputField.attr('placeholder', this.placeholder);
            }

            if (this.defaultValue !== '') {
                $inputField.attr('value', this.defaultValue);
            }

            $inputField.addClass('textInput answer message message-right animated fadeInUp');
            this.element.append($inputField);
            $('#chat-container').append(this.element);

            let _this = this;
            $inputField.keyup(function (event) {
                if (event.keyCode === 13) {
                    $('.answer').each(function () {
                        let answer = $(this).val();

                        if (answer !== "") {
                            _this.remove();
                            if (_this.isPassword) {
                                createAnswerMessage('*****');
                            } else {
                                createAnswerMessage(answer);
                            }
                            eddi.submitUserMessage(answer);
                        } else {
                            $(this).removeClass('shake').removeClass('fadeInUp');
                            $(this).addClass('shake');
                            $(this).one('webkitAnimationEnd mozAnimationEnd MSAnimationEnd oanimationend animationend',
                                function () {
                                    $(this).removeClass('shake').removeClass('fadeInUp');
                                });
                        }
                    });
                }
            });

            $inputField.focus();

            return setTimeout(function () {
                return $inputField.addClass('appeared');
            }, 0);
        };
    }

    remove() {
        this.element.remove();
    }
}

class Button {
    constructor(buttonObj) {
        this.buttonObj = buttonObj;
    }

    get draw() {
        return function () {
            const _this = this;
            const $button = $('<button/>', {
                text: _this.buttonObj.label,
                click: function () {
                    let answer = '';
                    /** @namespace buttonObj.onPress.submitField */
                    let onPress = _this.buttonObj.onPress;
                    let submitField;
                    if (typeof onPress !== 'undefined') {
                        submitField = onPress.submitField;
                        if (typeof submitField === 'undefined') {
                            submitField = 'inputText';
                        }

                        $('.' + submitField).each(function () {
                            answer = answer + $(this).val();
                        });

                        answer = answer.trim();
                        _this.convertInputFieldsToUserMessage(answer);
                        if (typeof onPress !== 'undefined') {
                            eddi.submitUserMessage(answer, onPress.context);
                        } else {
                            eddi.submitUserMessage(answer);
                        }
                    } else {
                        $('.inputText').each(function () {
                            answer = answer + $(this).val();
                        });

                        answer = answer.trim();
                        _this.convertInputFieldsToUserMessage(answer);
                        eddi.submitUserMessage(answer);
                    }

                    $('.quick_reply').remove();
                }
            });

            $button.hide();
            $button.addClass('message message-left animated fadeInUp bubbleLeft');
            $button.addClass('quick_reply');
            const $container = $('<div class="line">');
            $container.append($button);
            $('#chat-container').append($container);
            $button.fadeIn({queue: false, duration: 1500});
            return setTimeout(function () {
                return $button.addClass('appeared');
            }, 0);
        };
    }

    convertInputFieldsToUserMessage(answer) {
        let $inputText = $('.textInputContainer');
        if ($inputText.length > 0) {
            createAnswerMessage(answer);
        }
        $inputText.remove();
    }
}


class QuickReply {
    constructor(text, shakeDelay) {
        this.text = text;
        this.shakeDelay = shakeDelay;
    }

    get draw() {
        return function () {
            const _this = this;
            const $quickReply = $('<button/>', {
                text: this.text,
                click: function () {
                    createAnswerMessage(_this.text);
                    eddi.submitUserMessage(_this.text);
                    $('.quick_reply').remove();
                }
            });

            $quickReply.hide();
            $quickReply.addClass('message message-left animated fadeInUp bubbleLeft');
            $quickReply.addClass('quick_reply');
            $('#chat-container').append($quickReply);
            $quickReply.fadeIn({queue: false, duration: 1500});
            let shakeDelay = this.shakeDelay;
            return setTimeout(function () {
                setTimeout(function () {
                    $quickReply.effect('shake', {times: 2}, 300);
                }, shakeDelay);

                return $quickReply.addClass('appeared');
            }, 0);
        };
    }
}

class ConversationEnd {
    get draw() {
        return function () {
            this.element = $('<div style="margin: 1em;">');
            this.element.addClass('line');
            const $message = $('<div style="color:darkgray;">*** CONVERSATION ENDED ***</div>');
            this.element.append($message);
            $message.html(this.text);
            $('#chat-container').append(this.element);
            return setTimeout(function () {
                return $message.addClass('appeared');
            }, 0);
        };
    }
}

function createAnswerMessage(answer) {
    let htmlWrapperBeginning = "<div class=\"line\"><div class=\"message message-right animated fadeInUp bubbleRight\">",
        htmlWrapperEnding = "</div></div><div class=\"clear\"></div>";

    $('.quickReply').remove();
    $('#chat-container').append(htmlWrapperBeginning + answer + htmlWrapperEnding);
}


$(function () {
    $("#skipDelay").change(function () {
        eddi.skipDelay = $("#skipDelay").is(':checked');
    });


    eddi.submitUserMessage = function (userMessage, context) {
        let requestBody = null;

        if (typeof context === 'undefined') {
            context = {};
        }

        context.url = {'type': 'object', 'value': $.url.parse(window.location.href)};

        let contextValue = $('#contextValue').val().trim();
        if (contextValue !== null && contextValue !== '') {
            let contextObj = JSON.parse(contextValue);
            if (typeof context !== 'undefined') {
                Object.assign(contextObj, context);
            }

            requestBody = {
                input: userMessage,
                context: contextObj
            }
        } else if (typeof context !== 'undefined') {
            requestBody = {
                input: userMessage,
                context: context
            }
        } else {
            requestBody = {
                input: userMessage
            }
        }

        $.ajax({
            type: 'POST',
            url: '/bots/' + eddi.environment + '/' + eddi.botId + '/' + eddi.conversationId,
            data: JSON.stringify(requestBody),
            contentType: 'application/json; charset=utf-8',
            dataType: 'json',
            success: function (conversationMemory) {
                refreshConversationLog(conversationMemory);
            }
        });
    };

    const loadConversationLog = function () {
        $.get('/bots/' + eddi.environment + '/' + eddi.botId + '/' + eddi.conversationId).done(
            function (conversationMemory) {
                refreshConversationLog(conversationMemory);
            });
    };

    const refreshConversationLog = function (conversationMemory) {
        const conversationState = conversationMemory.conversationState;

        if (conversationState === 'ERROR') {
            console.log('ERROR', 'An Error has occurred. Please contact the administrator!');
            return;
        }

        if (conversationState === 'IN_PROGRESS') {
            setTimeout(loadConversationLog, 1000);
            return;
        }

        /** @namespace conversationMemory.conversationOutputs */
        /** @namespace conversationOutput.quickReplies */
        let conversationOutput = conversationMemory.conversationOutputs[0];
        let actions = conversationOutput.actions ? conversationOutput.actions : [];
        let outputArray = conversationOutput.output ? conversationOutput.output : [];
        let quickReplyArray = conversationOutput.quickReplies ? conversationOutput.quickReplies : [];
        createMessage(outputArray, quickReplyArray, conversationState === 'ENDED', actions.includes("DISABLE_INPUT"));
    };

    const deployBot = function (environment, botId, botVersion) {
        $.post('/administration/' + environment + '/deploy/' + botId + '?version=' + botVersion).done(function () {
            checkBotDeploymentStatus();
        });
    };

    const checkBotDeploymentStatus = function () {
        $.get('/administration/' + eddi.environment + '/deploymentstatus/' + eddi.botId + '?version=' + eddi.botVersion).done(function (data) {
            if (data === 'IN_PROGRESS') {
                setTimeout(checkBotDeploymentStatus, 1000);
            } else if (data === 'ERROR') {
                alert('An error occurred while bot deployment');
            } else if (data === 'READY') {
                proceedConversation();
            }
        });
    };

    eddi.createConversation = function (environment, botId, empty) {
        if (empty) {
            $('.messages').empty();
        }
        $.post('/bots/' + environment + '/' + botId).done(function (data, status, xhr) {
            const conversationUriArray = xhr.getResponseHeader('Location').split('/');

            if (!eddi.isFirstMessage) {
                smoothScrolling();
                eddi.isFirstMessage = false;
            }

            if (eddi.conversationId) {
                $('#previousConversationId').text(eddi.conversationId);
                $('#previousConversationLink').attr('href', '/bots/' + eddi.environment + '/' + eddi.botId + '/' + eddi.conversationId + '?returnCurrentStepOnly=false&returnDetailed=true');
                $('#previousConversationLink').show();
            }

            eddi.conversationId = conversationUriArray[conversationUriArray.length - 1];

            $('#currentConversationId').text(eddi.conversationId);
            $('#currentConversationLink').attr('href', '/bots/' + eddi.environment + '/' + eddi.botId + '/' + eddi.conversationId + '?returnCurrentStepOnly=false&returnDetailed=true');
            proceedConversation();
        });
    };

    const checkConversationStatus = function (environment, botId, conversationId) {
        $.get('/bots/' + environment + '/' + botId + '/' + conversationId).always(function (data, status) {
            if (status === 'error') {
                alert('Checking conversation has yield into an error.. ');
            } else if (status === 'success') {
                eddi.conversationState = data.conversationState;
                if (eddi.conversationState !== 'READY') {
                    alert('Conversation is not Ready... (state=' + eddi.conversationState + ')');
                }

                loadConversationLog();
            }
        });
    };

    const getQueryParts = function (href) {
        const query = $.url.parse(href);
        const path = query.path;

        const parts = path.split('/');

        let environment = null;
        let botId = null;
        let conversationId = null;
        let botVersion = null;
        let skipDelay = null;

        environment = typeof parts[2] !== 'undefined' ? decodeURIComponent(parts[2]) : environment;
        botId = typeof parts[3] !== 'undefined' ? decodeURIComponent(parts[3]) : botId;
        conversationId = typeof parts[4] !== 'undefined' ? decodeURIComponent(parts[4]) : conversationId;

        if (query.params) {
            if (query.params.version) {
                botVersion = query.params.version;
            }

            if (query.params.skipDelay) {
                skipDelay = query.params.skipDelay;
            }
        }

        return {
            conversationId: conversationId, environment: environment,
            botId: botId, botVersion: botVersion, skipDelay: skipDelay
        };
    };

    const proceedConversation = function () {
        if (!eddi.conversationId) {
            eddi.createConversation(eddi.environment, eddi.botId);
        } else {
            checkConversationStatus(eddi.environment, eddi.botId, eddi.conversationId);
        }
    };

    const checkBotDeployment = function () {
        //check if bot is deployed
        if (eddi.environment === 'test') {
            $.get('/administration/' + eddi.environment + '/deploymentstatus/' + eddi.botId + '?version=' + eddi.botVersion)
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
        } else {
            proceedConversation();
        }
    };

    eddi.insertContextExample = function () {
        if ($('#contextValue').val() !== '') {
            alert('context textarea is not empty!');
            return;
        }

        $('#contextValue').val('{\n' +
            '  "userId": {\n' +
            '    "type": "string",\n' +
            '    "value": "cdec53d4-9826-4a81-2w2w2-7d184bd6063f"\n' +
            '  },\n' +
            '  "userInfo": {\n' +
            '    "type": "object",\n' +
            '    "value": {\n' +
            '      "username": "Tom"\n' +
            '    }\n' +
            '  },\n' +
            '  "properties": {\n' +
            '    "type": "expressions",\n' +
            '    "value": "property(category_1(value)), property(category_2(value))"\n' +
            '  }\n' +
            '}');
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
        if (extractedParams.skipDelay !== null) {
            eddi.skipDelay = extractedParams.skipDelay;
            $('#skipDelay').prop('checked', eddi.skipDelay);
        }

        //extract conversationId
        if (extractedParams.botVersion !== null) {
            eddi.botVersion = extractedParams.botVersion;
            checkBotDeployment();
        } else {
            if (eddi.environment === 'test') {
                $.get('/botstore/bots/' + eddi.botId + '/currentversion', function (data) {
                    eddi.botVersion = data;
                    checkBotDeployment();
                });
            } else {
                proceedConversation();
            }
        }
    });
});