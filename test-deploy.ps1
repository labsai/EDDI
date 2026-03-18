# Create a behavior resource
 = @{
    behaviorGroups = @(
        @{
            name = "Default"
            behaviorRules = @(
                @{
                    name = "LLM"
                    actions = @("llm_call")
                    conditions = @(
                        @{
                            type = "inputmatcher"
                            configs = @{ expressions = "true()" }
                        }
                    )
                }
            )
        }
    )
} | ConvertTo-Json -Depth 10

 = Invoke-WebRequest -Uri "http://localhost:7070/behaviorstore/behaviorsets" -Method Post -ContentType "application/json" -Body 
eddi://ai.labs.behavior/behaviorstore/behaviorsets/69ba9c18dfc5b823af3c8f36?version=1 = .Headers['Location'][0]
Write-Host "Behavior: eddi://ai.labs.behavior/behaviorstore/behaviorsets/69ba9c18dfc5b823af3c8f36?version=1"

# Create a langchain resource (minimal)
 = @{
    tasks = @(
        @{
            type = "assistantTask"
            actions = @("llm_call")
            systemPrompt = "You are a test bot."
            connectorType = @{
                type = "anthropic"
                model = "claude-sonnet-4-20250514"
                apiKey = "test-key"
            }
        }
    )
} | ConvertTo-Json -Depth 10

 = Invoke-WebRequest -Uri "http://localhost:7070/langchainstore/langchains" -Method Post -ContentType "application/json" -Body 
eddi://ai.labs.langchain/langchainstore/langchains/69ba9c1bdfc5b823af3c8f37?version=1 = .Headers['Location'][0]
Write-Host "Langchain: eddi://ai.labs.langchain/langchainstore/langchains/69ba9c1bdfc5b823af3c8f37?version=1"

# Create package with both extensions
 = @{
    packageExtensions = @(
        @{
            type = "eddi://ai.labs.behavior"
            config = @{ uri = eddi://ai.labs.behavior/behaviorstore/behaviorsets/69ba9c18dfc5b823af3c8f36?version=1 }
        },
        @{
            type = "eddi://ai.labs.langchain"
            config = @{ uri = eddi://ai.labs.langchain/langchainstore/langchains/69ba9c1bdfc5b823af3c8f37?version=1 }
        }
    )
} | ConvertTo-Json -Depth 10

 = Invoke-WebRequest -Uri "http://localhost:7070/packagestore/packages" -Method Post -ContentType "application/json" -Body 
eddi://ai.labs.package/packagestore/packages/69ba9c1fdfc5b823af3c8f39?version=1 = .Headers['Location'][0]
Write-Host "Package: eddi://ai.labs.package/packagestore/packages/69ba9c1fdfc5b823af3c8f39?version=1"

# Create bot
{"packages":["eddi://ai.labs.package/packagestore/packages/69baa1e2c1624a8eb9edb40d?version=1"]} = @{
    packages = @(eddi://ai.labs.package/packagestore/packages/69ba9c1fdfc5b823af3c8f39?version=1)
} | ConvertTo-Json -Depth 10

 = Invoke-WebRequest -Uri "http://localhost:7070/botstore/bots" -Method Post -ContentType "application/json" -Body {"packages":["eddi://ai.labs.package/packagestore/packages/69baa1e2c1624a8eb9edb40d?version=1"]}
eddi://ai.labs.bot/botstore/bots/69baa1e4c1624a8eb9edb40e?version=1 = .Headers['Location'][0]
69baa1e4c1624a8eb9edb40e = (eddi://ai.labs.bot/botstore/bots/69baa1e4c1624a8eb9edb40e?version=1 -split '/')[(eddi://ai.labs.bot/botstore/bots/69baa1e4c1624a8eb9edb40e?version=1 -split '/').Length - 1] -replace '\?.*',''
Write-Host "Bot: eddi://ai.labs.bot/botstore/bots/69baa1e4c1624a8eb9edb40e?version=1 (id=69baa1e4c1624a8eb9edb40e)"

# Deploy with waitForCompletion
@{status=READY; botId=69baa01adfc5b823af3c8f53; version=1; environment=unrestricted} = Invoke-RestMethod -Uri "http://localhost:7070/administration/unrestricted/deploy/69baa1e4c1624a8eb9edb40e?version=1&autoDeploy=true&waitForCompletion=true" -Method Post -ContentType "application/json"
@{status=READY; botId=69baa01adfc5b823af3c8f53; version=1; environment=unrestricted} | ConvertTo-Json
