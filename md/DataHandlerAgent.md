## 任务内容：

### 1.创建DataHandlerAgent

##### 示例代码：

```
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.message.Msg;

// 创建智能体并内联配置模型
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

### 2.创建定时任务，查询患者信息

说明：去数据库查询未处理数据对应的患者信息，对应表：PatientRawDataEntity

1.查询data_json不为null、struct_data_json为null的数据，然后分组，只返回reqno列表

2.若reqno不为空，则唤醒DataHandlerAgent进行数据处理。

### 2.创建Tool工具

#### 查询患者reqno列表工具

说明：去数据库查询未处理数据对应的患者信息，对应表：PatientRawDataEntity

1.查询data_json不为null、struct_data_json为null的数据，然后分组，只返回reqno列表

2.循环reqno列表，根据reqno查询数据，按datadate字段倒序排列，得到返回患者信息List

3.循环患者信息List，从data_json中查询首次病程记录。查询方式为：从clinical_notes字段中进行“首次病程记录”字符匹配，匹配到之后，将clinical_notes按照空格分组，记录”首次病程记录“的下标。然后获取到data_json.pat_illnessCourse数组，根据下标匹配，就可拿到患者的首次病程记录。

4.将患者的其他病程记录，与首次病程记录进行相似度匹配。如果与首次病程记录相同或达到重复相似度阈值，就从其他病程记录中删掉相同的内容，将data_json.pat_illnessCourse替换为过滤后的内容，然后将新的data_json保存到filter_data_json字段。