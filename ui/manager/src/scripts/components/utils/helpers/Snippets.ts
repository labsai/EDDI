export const regularDictionarySnippets: string = `# AddPhrases
snippet phrases
	"phrases": [\${1:}]
# AddPhrase
snippet phrase
	{
		"phrase": "\${1:phrase}",
		"exp": "\${2:exp_name}(\${3:exp_value})",
		"frequency": 0
	}

# AddWords
snippet words
	"words": [\${1:}]

# AddWord
snippet word
	{
		"word": "\${1:word}",
		"exp": "\${2:exp_name}(\${3:exp_value})",
		"frequency": 0
	}
`;
