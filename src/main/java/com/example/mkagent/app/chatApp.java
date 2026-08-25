package com.example.mkagent.app;



import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.example.mkagent.advisor.MyLoggerAdvisor;
import com.example.mkagent.advisor.ReReadingAdvisor;
import com.example.mkagent.chatmemory.FileBasedChatMemory;
import com.example.mkagent.demo.invoke.TestApiKey;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.rag.ChatAppRagCloudAdvisorConfig;
import com.example.mkagent.rag.ChatAppRagCustomAdvisorFactory;
import com.example.mkagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@Slf4j
public class chatApp {

    private static final String SYSTEM_PROMPT = """
            你是一名深耕恋爱心理领域的咨询助手。
            开场时先表明身份，并告诉用户可以倾诉恋爱难题。

            根据用户状态进行追问：
            - 单身：询问社交圈拓展、追求心仪对象的困难；
            - 恋爱：询问沟通方式、习惯差异、冲突的具体经过；
            - 已婚：询问家庭责任分配、亲属关系和沟通问题。

            引导用户说明事情经过、对方反应和自己的真实想法，
            再给出温和、具体、可执行的建议。
            不要把自己描述为持证心理医生；如用户出现自伤、他伤
            或紧急危险迹象，应建议尽快联系当地紧急服务或专业人士。
            """;

    private final ChatClient chatClient;

    /**
     * Spring 创建 ChatApp Bean 时自动注入 ChatModel。
     */
    public chatApp(ChatModel chatModel) {

        //初始化对于文件的对话记忆
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);

        /*
        //初始化对于内存的对话记忆
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)//由于Spring AI版本更新，现在的上下文记忆条数需要在记忆设置的时候配置
                .build();
         */
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        //自定义日志拦截器
                        new MyLoggerAdvisor()

                        /*自定义ReReading

                        new ReReadingAdvisor()

                         */
                )
                .build();
    }

    /**
     * 单次 AI 对话。
     *
     * @param message 用户本轮输入
     * @param chatId 当前会话 ID；同一聊天窗口必须始终传相同的 chatId
     * @return AI 文本回复
     */
    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))
                .call()
                .chatResponse();

        String content = response.getResult().getOutput().getText();
        log.info("chatId: {}, content: {}", chatId, content);
        return content;
    }


    record LoveReport(String title, List<String> suggestion){

    }
    /**
     * AI 恋爱报告功能（支持结构化输出）
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient.prompt()
                .system(SYSTEM_PROMPT + "每次对话都要生成恋爱结构，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(advisor -> advisor.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))
                .call()
                .entity(LoveReport.class);

        log.info("LoveReport: {}", loveReport);
        return loveReport;
    }





     @Resource
    private VectorStore chatAppVectorStore;


    @Resource(name = "chatAppRagCloudAdvisor")
    private Advisor chatAppRagCloudAdvisor;

    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 和RAG知识库进行问答
     * @param message
     * @param chatId
     * @return
     */

    public String doChatWithRag(String message, String chatId) {

        String rewrittenMessage = queryRewriter.doQueryRewrite(message);

        ChatResponse chatResponse = chatClient.prompt()
                .user(rewrittenMessage)//优化用户的输入内容之后再次进行引入
                .advisors(spec -> spec.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))
                //对本地数据进行分片，再存储到向量数据库中，再针对向量数据进行初步检索召回
                //.advisors(new QuestionAnswerAdvisor(chatAppVectorStore)) //对本地数据进行分片，再存储到向量数据库中，再针对向量数据进行初步检索召回

                //对云数据库的内容进行获取，云数据库
                //.advisors(chatAppRagCloudAdvisor)

                /**
                 *应用自定义的RAG检索增强顾问（文档查询器 + 上下文增强器）
                 */
                .advisors(
                        ChatAppRagCustomAdvisorFactory.createChatAppRagCustomAdvisor(
                                chatAppVectorStore, "单身"  //自定义RAG
                        )
                )
                .call()
                .chatResponse();

        String content = chatResponse.getResult().getOutput().getText();
        log.info("chatId: {}, content: {}", chatId, content);
        return content;
    }


    @Resource
    private ToolCallbackProvider toolCallbackProvider; //mcp工具注入

    public String doChatWithMcp(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(advisor -> advisor.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))

                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }


    /**
     * 流式 AI 对话（响应式）。
     * 调用方可通过 Flux 订阅逐字输出，例如对接 SSE 推送给前端。
     *
     * @param message 用户本轮输入
     * @param chatId  当前会话 ID
     * @return AI 回复的文本流
     */
    public Flux<String> doChatWithByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 10))
                .stream()
                .content();
    }
}
