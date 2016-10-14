function TestCaseContentModel(dataProvider, actionHandler) {
    this.getErrorOutputHtmlString = function (expectedValue, actualValue) {
        return window.lang.convert('TESTCASE_FAILURE_PREFIX_EXPECTED') + '<b>"' + expectedValue + '"</b>' + '&nbsp;&nbsp;&nbsp;&nbsp;' + window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + '<b>"' + actualValue + '"</b>' + '<br/>';
    }

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        var models = [];

        var testCase = dataProvider.readActiveTestCase();
        var expectedResult = testCase.expected;
        var actualResult = testCase.actual;

        var conversationStepColumns = [
            new TableControlColumnModel({columnIdentifier:'selection'}, window.lang.convert('MONITOR_CONVERSATION_TABLE_COL_SELECTION'), 'table_col_selection', false, false),
            new TableControlColumnModel({columnIdentifier:'input'}, window.lang.convert('MONITOR_CONVERSATION_TABLE_COL_INPUT'), 'table_col_input', false, false),
            new TableControlColumnModel({columnIdentifier:'output'}, window.lang.convert('TESTCASE_TABLE_COL_PREFIX_EXPECTED') + window.lang.convert('MONITOR_CONVERSATION_TABLE_COL_OUTPUT'), 'table_col_output', false, false),
            new TableControlColumnModel({columnIdentifier:'actualValues'}, window.lang.convert('TESTCASE_TABLE_COL_ACTUALVALUES'), 'table_col_actualvalues', false, false)
        ];

        var packageTableControls = [];
        var packageColumns = [
            new TableControlColumnModel({columnIdentifier:'selection'}, window.lang.convert('MONITOR_PACKAGE_TABLE_COL_SELECTION'), 'table_col_selection', false, false),
            new TableControlColumnModel({columnIdentifier:'name'}, window.lang.convert('TESTCASE_TABLE_COL_PREFIX_EXPECTED') + window.lang.convert('MONITOR_PACKAGE_TABLE_COL_NAME'), 'table_col_name', false, false),
            new TableControlColumnModel({columnIdentifier:'duration'}, window.lang.convert('MONITOR_PACKAGE_TABLE_COL_DURATION'), 'table_col_duration', false, false),
            new TableControlColumnModel({columnIdentifier:'count'}, window.lang.convert('TESTCASE_TABLE_COL_PREFIX_EXPECTED') + window.lang.convert('MONITOR_PACKAGE_TABLE_COL_COUNT'), 'table_col_count', false, false),
            new TableControlColumnModel({columnIdentifier:'actualValues'}, window.lang.convert('TESTCASE_TABLE_COL_ACTUALVALUES'), 'table_col_actualvalues', false, false)
        ];

        var lifeCycleTaskTableControls = [];
        var lifeCycleTaskColumns = [
            new TableControlColumnModel({columnIdentifier:'key'}, window.lang.convert('TESTCASE_TABLE_COL_PREFIX_EXPECTED') + window.lang.convert('MONITOR_LIFECYCLETASK_TABLE_COL_KEY'), 'table_col_key', false, false),
            new TableControlColumnModel({columnIdentifier:'result'}, window.lang.convert('TESTCASE_TABLE_COL_PREFIX_EXPECTED') + window.lang.convert('MONITOR_LIFECYCLETASK_TABLE_COL_RESULT'), 'table_col_result', false, false),
            new TableControlColumnModel({columnIdentifier:'actualValues'}, window.lang.convert('TESTCASE_TABLE_COL_ACTUALVALUES'), 'table_col_actualvalues', false, false)
        ];

        var conversationStepData = [];
        var packageData = [];
        var lifeCycleTaskData = [];

        var conversationStepErrors = [];
        var conversationStepColumnErrors = [];

        var packageErrors = [];
        var packageColumnErrors = [];

        var lifeCycleTaskErrors = [];
        var lifeCycleTaskColumnErrors = [];

        var expectedConversationStep;
        var actualConversationStep;
        var expectedConversationStepError;
        var expectedConversationStepErrorString;
        var input;
        var inputError;
        var output;
        var outputError;
        //var date;
        //var dateError;

        for (var i = 0; i < expectedResult.conversationSteps.length; i++) {
            lifeCycleTaskTableControls = [];
            packageData = [];
            packageErrors = [];
            packageColumnErrors = [];
            expectedConversationStepError = false;
            expectedConversationStepErrorString = '';
            input = null;
            inputError = false;
            output = null;
            outputError = false;
            //date = null;
            //dateError = false;

            expectedConversationStep = expectedResult.conversationSteps[i];
            if (actualResult.conversationSteps.length > i) {
                actualConversationStep = actualResult.conversationSteps[i];
            } else {
                // step does not exist in actual result.
                actualConversationStep = null;
                expectedConversationStepError = true;
                expectedConversationStepErrorString += window.lang.convert('TESTCASE_FAILURE_CONVERSATION_STEP_LENGTH') + '<br/>';
            }

            var expectedPackagesArray = expectedConversationStep.packages;
            var actualPackagesArray;
            if (actualConversationStep != null && typeof actualConversationStep.packages !== 'undefined') {
                actualPackagesArray = actualConversationStep.packages;
            } else {
                actualPackagesArray = null;
                expectedConversationStepError = true;
                expectedConversationStepErrorString += window.lang.convert('TESTCASE_FAILURE_CONVERSATION_STEP_PACKAGES') + '<br/>';
            }
            var expectedPackage;
            var actualPackage;
            var expectedLifeCycleTasksArray;
            var actualLifeCycleTasksArray;
            var expectedPackageError;
            var expectedPackageErrorString;
            var name;
            var nameError;
            var duration;
            //var durationError;
            var count;
            var countError;
            for (var j = 0; j < expectedPackagesArray.length; j++) {
                lifeCycleTaskData = [];
                lifeCycleTaskErrors = [];
                lifeCycleTaskColumnErrors = [];
                expectedPackageError = false;
                expectedPackageErrorString = '';
                nameError = false;
                //durationError = false;
                countError = false;

                expectedPackage = expectedPackagesArray[j];
                if (actualPackagesArray != null && actualPackagesArray.length > j) {
                    actualPackage = actualPackagesArray[j];
                } else {
                    actualPackage = null;
                    expectedPackageError = true;
                    expectedPackageErrorString += window.lang.convert('TESTCASE_FAILURE_PACKAGE_LENGTH') + '<br/>';
                }

                expectedLifeCycleTasksArray = expectedPackage.lifecycleTasks;
                if (actualPackage != null && typeof actualPackage.lifecycleTasks !== 'undefined') {
                    actualLifeCycleTasksArray = actualPackage.lifecycleTasks;
                } else {
                    actualLifeCycleTasksArray = null;
                    expectedPackageError = true;
                    expectedPackageErrorString += window.lang.convert('TESTCASE_FAILURE_PACKAGE_LIFECYCLETASKS') + '<br/>';
                }

                name = expectedPackage.context;
                if (actualPackage != null && actualPackage.context != name) {
                    nameError = true;
                    expectedPackageErrorString += this.getErrorOutputHtmlString(name, actualPackage.context);//window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + window.lang.convert('MONITOR_PACKAGE_TABLE_COL_NAME') + ' = "' + actualPackage.context + '"<br/>';
                }
                count = expectedLifeCycleTasksArray.length;
                if (actualLifeCycleTasksArray != null && actualLifeCycleTasksArray.length != count) {
                    countError = true;
                    expectedPackageErrorString += this.getErrorOutputHtmlString(count, actualLifeCycleTasksArray.length);//window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + window.lang.convert('MONITOR_PACKAGE_TABLE_COL_COUNT') + ' = "' + actualLifeCycleTasksArray.length + '"<br/>';
                }
                duration = expectedLifeCycleTasksArray.length > 0 ? expectedLifeCycleTasksArray[expectedLifeCycleTasksArray.length - 1].timestamp - expectedLifeCycleTasksArray[0].timestamp : 0;

                var expectedLifeCycleTask;
                var actualLifeCycleTask;
                var expectedLifeCycleTaskError;
                var expectedLifeCycleTaskErrorString;
                var key;
                var keyError;
                var result;
                var possibleResults;
                var resultError;
                var outputFoundInThisPackagesLifeCycleTaskArray = false;
                for (var k = 0; k < expectedLifeCycleTasksArray.length; k++) {
                    expectedLifeCycleTaskError = false;
                    expectedLifeCycleTaskErrorString = '';
                    keyError = false;
                    resultError = false;

                    expectedLifeCycleTask = expectedLifeCycleTasksArray[k];
                    if (actualLifeCycleTasksArray != null && actualLifeCycleTasksArray.length > k) {
                        actualLifeCycleTask = actualLifeCycleTasksArray[k];
                    } else {
                        actualLifeCycleTask = null;
                        expectedLifeCycleTaskError = true;
                        expectedLifeCycleTaskErrorString += window.lang.convert('TESTCASE_FAILURE_LIFECYCLETASK_LENGTH') + '<br/>';
                    }

                    key = expectedLifeCycleTask.key;
                    if (actualLifeCycleTask != null && actualLifeCycleTask.key != key) {
                        keyError = true;
                        expectedLifeCycleTaskErrorString += this.getErrorOutputHtmlString(key, actualLifeCycleTask.key);//window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + window.lang.convert('MONITOR_LIFECYCLETASK_TABLE_COL_KEY') + ' = "' + actualLifeCycleTask.key + '"<br/>';
                    }

                    result = expectedLifeCycleTask.result;
                    possibleResults = expectedLifeCycleTask.possibleResults;
                    if (actualLifeCycleTask != null && actualLifeCycleTask.possibleResults.toString() != possibleResults.toString()) {
                        resultError = true;
                        expectedLifeCycleTaskErrorString += this.getErrorOutputHtmlString(possibleResults, actualLifeCycleTask.possibleResults);//window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + window.lang.convert('MONITOR_LIFECYCLETASK_TABLE_COL_RESULT') + ' = "' + actualLifeCycleTask.result + '"<br/>';
                    }

//                    if (actualLifeCycleTask != null && actualLifeCycleTask.result.toString() != result.toString()) {
//                        resultError = true;
//                        expectedLifeCycleTaskErrorString += this.getErrorOutputHtmlString(result, actualLifeCycleTask.result);//window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + window.lang.convert('MONITOR_LIFECYCLETASK_TABLE_COL_RESULT') + ' = "' + actualLifeCycleTask.result + '"<br/>';
//                    }

                    lifeCycleTaskData.push([key, result, expectedLifeCycleTaskErrorString]);
                    // if a property of a lifeCycleTask is marked as error, we mark the whole lifeCycleTask as error.
                    expectedLifeCycleTaskError = expectedLifeCycleTaskError ? expectedLifeCycleTaskError : (keyError || resultError);
                    lifeCycleTaskErrors.push(expectedLifeCycleTaskError);
                    // if a lifeCyleTask as a whole or a property of a lifeCycleTask is marked as error, we mark the whole package as error.
                    expectedPackageError = expectedPackageError ? expectedPackageError : expectedLifeCycleTaskError;
                    lifeCycleTaskColumnErrors.push([keyError, resultError, false]);

                    if (input == null && expectedLifeCycleTask.key.indexOf('input') == 0) {
                        input = expectedLifeCycleTask.result;
                        if (actualLifeCycleTask != null && actualLifeCycleTask.result != input) {
                            inputError = true;
                            expectedConversationStepErrorString += this.getErrorOutputHtmlString(input, actualLifeCycleTask.result);//window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + window.lang.convert('MONITOR_CONVERSATION_TABLE_COL_INPUT') + ' = "' + actualLifeCycleTask.result + '"<br/>';
                        }
                        //date = (new Date(expectedLifeCycleTask.timestamp)).toLocaleTimeString();
                    }

                    //output for conversationStep = the last lifeCycleElement which name begins with 'output' IN the first package which contains a lifecycle-element with a name beginning with 'output'
                    if ((output == null || outputFoundInThisPackagesLifeCycleTaskArray) && expectedLifeCycleTask.key.indexOf('output') == 0) {
                        output = expectedLifeCycleTask.result;
                        outputFoundInThisPackagesLifeCycleTaskArray = true;
                        if (actualLifeCycleTask != null && actualLifeCycleTask.result != output) {
                            outputError = true;
                            expectedConversationStepErrorString += this.getErrorOutputHtmlString(output, actualLifeCycleTask.result);//window.lang.convert('TESTCASE_FAILURE_PREFIX_EXPECTED') + '<b>"' + output + '"</b>'+ '&nbsp;&nbsp;&nbsp;&nbsp;' + window.lang.convert('TESTCASE_FAILURE_PREFIX_ACTUAL') + '<b>"' + actualLifeCycleTask.result + '"</b>' + '<br/>';
                        }
                    }
                }

                var lifeCycleTaskTableDataModel = new TableControlDataModel({dataType:'lifeCycleTask'}, lifeCycleTaskData);
                lifeCycleTaskTableDataModel.rowErrors = lifeCycleTaskErrors;
                lifeCycleTaskTableDataModel.rowColumnErrors = lifeCycleTaskColumnErrors;
                var lifeCycleTaskTableModel = new TableControlModel(0, 'lifecycletask_table_', true, true, lifeCycleTaskColumns, lifeCycleTaskTableDataModel);
                lifeCycleTaskTableModel.setShowControlHeaders(false);

                lifeCycleTaskTableControls.push(new TableControl(lifeCycleTaskTableModel));

                packageData.push([ '<img class="dataTables_dotbutton" src="/binary/default/desktop/images/dotbutton.png"/>', name, duration, count, expectedPackageErrorString ]);
                expectedPackageError = expectedPackageError ? expectedPackageError : (nameError || countError);
                packageErrors.push(expectedPackageError);
                // if a lifeCycleTask as a whole or a property of a lifeCycleTask is marked as error, we mark the whole package as error.
                expectedConversationStepError = expectedConversationStepError ? expectedConversationStepError : expectedPackageError;
                packageColumnErrors.push([false, nameError, false, countError, false]);
            }

            var packageTableDataModel = new TableControlDataModel({dataType:'package'}, packageData);
            packageTableDataModel.rowErrors = packageErrors;
            packageTableDataModel.rowColumnErrors = packageColumnErrors;
            packageTableDataModel.setDetailRowsTableControls(lifeCycleTaskTableControls);
            var packageTableModel = new TableControlModel(0, 'package_table_', true, true, packageColumns, packageTableDataModel);
            packageTableModel.setShowControlHeaders(false);

            packageTableControls.push(new TableControl(packageTableModel));

            conversationStepData.push([ '<img class="dataTables_dotbutton" src="/binary/default/desktop/images/dotbutton.png"/>', input, output, expectedConversationStepErrorString ]);
            expectedConversationStepError = expectedConversationStepError ? expectedConversationStepError : (inputError || outputError);
            conversationStepErrors.push(expectedConversationStepError);
            conversationStepColumnErrors.push([false, false, inputError, outputError, false]);
        }

        var tableDataModel = new TableControlDataModel({dataType:'testcase'}, conversationStepData);
        tableDataModel.rowErrors = conversationStepErrors;
        tableDataModel.rowColumnErrors = conversationStepColumnErrors;
        tableDataModel.setDetailRowsTableControls(packageTableControls);
        var tableModel = new TableControlModel(0, 'testcase_table_', true, true, conversationStepColumns, tableDataModel);
        tableModel.setShowControlHeaders(false);


        var tableControl = new TableControl(tableModel);
        tableControl.observable.addObserver(actionHandler.observer);
        models.push(tableControl);
        return models;
    }
}