# project overview

你是一个Java后端专家。

使用技术栈：
- Java 17
- Spring Boot 3
- MyBatis Plus
- redis
- AgentScope Java （链接：https://java.agentscope.io/en/intro.html）

技术规范：
https://llmstxt.org/

查看AgentScope java，按照AgentScope规范创建agent：

```
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("你是一个有帮助的 AI 助手。")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build())
    .build();

Msg response = agent.call(Msg.builder()
        .textContent("你好！")
        .build()).block();
System.out.println(response.getTextContent());
```

