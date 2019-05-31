{
	"behaviorGroups": [
		{
			"name": "Smalltalk",
			"behaviorRules": [
				{
					"name": "Welcome",
					"actions": [
						"welcome"
					],
					"conditions": [
						{
							"type": "occurrence",
							"configs": {
								"maxTimesOccurred": "0",
								"behaviorRuleName": "Welcome"
							}
						}
					]
				},
				{
					"name": "yourName",
					"actions": [
						"name"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "welcome",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "nameFallback",
					"actions": [
						"name_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "welcome",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarNameFallback",
					"actions": [
						"vulgar_name_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "welcome",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_name"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "welcome",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntroFallback",
					"actions": [
						"happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntro",
					"actions": [
						"happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntroFallback",
					"actions": [
						"happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntroFallback",
					"actions": [
						"happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntro",
					"actions": [
						"happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntro",
					"actions": [
						"happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyYesFallback",
					"actions": [
						"vulgar_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyYesFallback",
					"actions": [
						"vulgar_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyYesFallback",
					"actions": [
						"vulgar_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyYesFallback",
					"actions": [
						"vulgar_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntroFallback",
					"actions": [
						"happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntroFallback",
					"actions": [
						"happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntro",
					"actions": [
						"happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntro",
					"actions": [
						"happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntroFallback",
					"actions": [
						"happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntroFallback",
					"actions": [
						"happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntro",
					"actions": [
						"happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessIntro",
					"actions": [
						"happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyNoFallback",
					"actions": [
						"vulgar_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyNoFallback",
					"actions": [
						"vulgar_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyNoFallback",
					"actions": [
						"vulgar_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappyNoFallback",
					"actions": [
						"vulgar_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_name_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vfirstTipYesFallback",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vfirstTipYesFallback",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipYesFallback",
					"actions": [
						"first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipYesFallback",
					"actions": [
						"first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipYesFallback",
					"actions": [
						"first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipYesFallback",
					"actions": [
						"first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipYesFallback",
					"actions": [
						"first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipYesFallback",
					"actions": [
						"first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "VulgarFirstTipFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "VulgarFirstTipFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipYesFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipYesFallback",
					"actions": [
						"vulgar_first_tip_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "VulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "VulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTipNoFallback",
					"actions": [
						"first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "firstTip",
					"actions": [
						"first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarFirstTipNoFallback",
					"actions": [
						"vulgar_first_tip_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_first_tip_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyNoFallback",
					"actions": [
						"vulgar_supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyNoFallback",
					"actions": [
						"vulgar_supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposeHappyYesFallback",
					"actions": [
						"supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyYesFallback",
					"actions": [
						"vulgar_supposed_happy_yes_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_yes"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "SupposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "SupposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "SupposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "SupposeHappyNoFallback",
					"actions": [
						"supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "supposedHappy",
					"actions": [
						"supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyNoFallback",
					"actions": [
						"vulgar_supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyNoFallback",
					"actions": [
						"vulgar_supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyNoFallback",
					"actions": [
						"vulgar_supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarSupposeHappyNoFallback",
					"actions": [
						"vulgar_supposed_happy_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_supposed_happy_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_first_tip_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_yes_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFirstFallback",
					"actions": [
						"happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFirstFallback",
					"actions": [
						"vulgar_happiness_strategy_first_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_first"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_supposed_happy_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySecondFallback",
					"actions": [
						"happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySecondFallback",
					"actions": [
						"happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySecondFallback",
					"actions": [
						"happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySecondFallback",
					"actions": [
						"happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategySecondFallback",
					"actions": [
						"vulgar_happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategySecondFallback",
					"actions": [
						"vulgar_happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategySecondFallback",
					"actions": [
						"vulgar_happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategySecondFallback",
					"actions": [
						"vulgar_happiness_strategy_second_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_second"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_first_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyThirdFallback",
					"actions": [
						"happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyThirdFallback",
					"actions": [
						"happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyThirdFallback",
					"actions": [
						"happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyThirdFallback",
					"actions": [
						"happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyThirdFallback",
					"actions": [
						"vulgar_happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyThirdFallback",
					"actions": [
						"vulgar_happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyThirdFallback",
					"actions": [
						"vulgar_happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyThirdFallback",
					"actions": [
						"vulgar_happiness_strategy_third_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_third"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_second_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFourthFallback",
					"actions": [
						"happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFourthFallback",
					"actions": [
						"happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFourthFallback",
					"actions": [
						"happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFourthFallback",
					"actions": [
						"happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFourthFallback",
					"actions": [
						"vulgar_happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFourthFallback",
					"actions": [
						"vulgar_happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFourthFallback",
					"actions": [
						"vulgar_happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFourthFallback",
					"actions": [
						"vulgar_happiness_strategy_fourth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fourth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_third_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFifthFallback",
					"actions": [
						"happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFifthFallback",
					"actions": [
						"happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFifthFallback",
					"actions": [
						"happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyFifthFallback",
					"actions": [
						"happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFifthFallback",
					"actions": [
						"vulgar_happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFifthFallback",
					"actions": [
						"vulgar_happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFifthFallback",
					"actions": [
						"vulgar_happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyFifthFallback",
					"actions": [
						"vulgar_happiness_strategy_fifth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_fifth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fourth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySixthFallback",
					"actions": [
						"happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySixthFallback",
					"actions": [
						"happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySixthFallback",
					"actions": [
						"happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySixthFallback",
					"actions": [
						"happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySixthFallback",
					"actions": [
						"vulgar_happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategySixthFallback",
					"actions": [
						"vulgar_happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategySixthFallback",
					"actions": [
						"vulgar_happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategySixthFallback",
					"actions": [
						"vulgar_happiness_strategy_sixth_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_sixth"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_fifth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyseventhFallback",
					"actions": [
						"happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyseventhFallback",
					"actions": [
						"happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyseventhFallback",
					"actions": [
						"happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategyseventhFallback",
					"actions": [
						"happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "happinessStrategy",
					"actions": [
						"happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "nexttip",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyseventhFallback",
					"actions": [
						"vulgar_happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyseventhFallback",
					"actions": [
						"vulgar_happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyseventhFallback",
					"actions": [
						"vulgar_happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarHappinessStrategyseventhFallback",
					"actions": [
						"vulgar_happiness_strategy_seventh_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_happiness_strategy_seventh"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_sixth_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgaroreTipsNoFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgaroreTipsNoFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_seventh_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsFallback",
					"actions": [
						"more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(yes)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTipsNoFallback",
					"actions": [
						"more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "moreTips",
					"actions": [
						"more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "confirmation(no)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarMoreTipsFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgaroreTipsNoFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgaroreTipsNoFallback",
					"actions": [
						"vulgar_more_tips_no_fallback"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_more_tips_no"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_happiness_strategy_eight_eunit_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarEndingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarEndingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarEndingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "vulgarEndingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Ending",
					"actions": [
						"ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "ending",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "endingFallback",
					"actions": [
						"vulgar_ending_fallback",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "*",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no",
								"occurrence": "lastStep"
							}
						}
					]
				},
				{
					"name": "Vulgar",
					"actions": [
						"vulgar_ending",
						"CONVERSATION_END"
					],
					"conditions": [
						{
							"type": "inputmatcher",
							"configs": {
								"expressions": "insult(vulgar)",
								"occurrence": "currentStep"
							}
						},
						{
							"type": "actionmatcher",
							"configs": {
								"actions": "vulgar_more_tips_no_fallback",
								"occurrence": "lastStep"
							}
						}
					]
				}
			]
		}
	]
}
