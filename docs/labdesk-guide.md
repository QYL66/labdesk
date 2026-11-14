# LabDesk 开发与调试指南

用 IDEA 打开根目录的 Maven 项目。下文的 `类名#方法名` 表示推荐打断点的位置。用两条 Java 进程分别跟后端和 MCP。需要单步调试时，先停止脚本拉起的同端口进程，再通过 IDEA 启动对应进程。

LabDesk 负责实验室页面、业务提示词、设备与预约表以及设备 MCP 工具。通用 Agent 负责工具编排、请求确认、会话身份传递和 SSE。通用 RAG 负责解析、检索、生成与来源组装。预约参数校验、账户隔离和并发写入由 LabDesk 的预约服务处理。

## 1. 从上传 Markdown 到向量入库

`rag` 模块同时包含知识库后台和 RAG 链路。

| 断点 | 在这里看什么 |
| --- | --- |
| `KnowledgeDocumentController#upload` | 管理后台发 multipart 请求。看 `kbId`、文件名、处理模式。 |
| `KnowledgeDocumentServiceImpl#upload` | 检查配置和文件类型，原件写到 RustFS，PostgreSQL 记录文件地址、知识库 ID 和 `pending` 状态。 |
| `KnowledgeDocumentServiceImpl#startChunk` | 点「开始分块」后，文档状态改为 `running`，经 RocketMQ 投递分块任务。 |
| `KnowledgeDocumentChunkConsumer#onMessage` → `KnowledgeDocumentServiceImpl#executeChunk` | 后台消费者收到相同的文档 ID，再拉取原文件、执行解析和分块。分别观察成功状态和失败日志。 |
| `DefaultIngestionKernel#run` → `PgVectorStoreService#indexDocumentChunks` | 看文本切成多少块、Embedding 回来的向量维度、向量关联的 `collectionName` 和 `docId`。向量写入 `t_knowledge_vector`，文档详情显示分块数量。 |

推荐从《设备操作规程.md》开始。可在分块结果里搜索“青绿色复位卡”，记下所属片段和文档 ID，下一条链路会用到它们。

## 2. 从提问到可打开的来源

在前端点击「资料问答」。路由 `/knowledge` 使用 `ChatPage.tsx`，向 `GET /api/labdesk/rag/v3/chat` 发起 SSE 请求。

| 断点 | 在这里看什么 |
| --- | --- |
| `RAGChatController#chat` → `RAGChatServiceImpl#streamChat` | 看提问、登录用户与对话 ID 如何进入流式服务。 |
| `StreamChatPipeline#execute` → `VectorSearchChannel#search` | 依次观察加载历史、改写和意图判断。向量检索阶段用问题向量找出资料片段，然后做融合和重排。 |
| `StreamChatPipeline#streamRagResponse` → `SourcesAssembler#assemble` | 检索结果按来源整理为 `SourceRef`。对照文档 ID、来源编号和 Prompt 里传给模型的片段。 |
| `StreamChatEventHandler#onContent` / `#onComplete` → `SourcesPanel.tsx` | 观察答案内容分批到达，完成时携带来源列表。点击面板中的 Markdown 文档会调用文档预览接口。 |

Agent 首页也可以用 `search_knowledge` 回答资料问题。演示可打开的原文引用时走「资料问答」，这里使用现有的 RAG 来源面板。

## 3. 从 Agent 工具调用到确认与执行

在「实验室助手」输入设备编号、绝对日期和整点。查空闲和查自己的预约是只读操作。提交预约走以下链路。

| 断点 | 在这里看什么 |
| --- | --- |
| `AgentChatController#chat` → `AgentChatServiceImpl#streamChat` | 对话调用 `GET /api/labdesk/agent/v1/chat`。观察服务端当前用户 ID 和对话 ID。 |
| `ReActAgentProvider#getAgent` → `AgentToolCatalog#resolveMcpTools` | 核对 MCP 工具目录和配置。LabDesk 注册日期和三个设备工具，另外沿用 Agent 内置知识检索。 |
| `McpToolProxy#checkPermissions` | 对照只读提示和预约工具的确认设置。预约返回待确认，前端渲染确认卡。 |
| `AgentChatController#confirm` → `AgentChatServiceImpl#confirmPendingTool` | 确认按钮提交对话 ID、待确认消息 ID 和同意状态。拒绝后结束这次待确认调用。 |
| `McpToolProxy#callAsync` → `LabEquipmentTools` | Agent 把登录身份写入 MCP 请求元数据。请求头用本机生成的令牌验证内部调用方。预约参数只包含设备、日期和时间。 |
| `LabBookingService#book` | 在 Java 校验设备和时段，之后由 PostgreSQL 裁决同一设备、同一时段的并发。 |

### 预约表里发生了什么

`labdesk_equipment` 保存设备和房间，`labdesk_booking` 保存设备、时段及账户 ID。应用按北京时间解释输入，数据库以 `timestamptz` 保存同一个绝对时刻。起始时间为 `09:00` 到 `17:00` 的整点。

查询空闲只代表查询当下。确认时用带唯一约束的 `INSERT … ON CONFLICT DO NOTHING` 竞争写入。随后单独执行一次 SELECT，读取已提交的预约。若账户一致，返回同一个编号。若另一账户已经占用，返回已预约。这样可以用相同输入安全重试，也能处理两个用户几乎同时确认的情况。

主要新增代码在 `mcp-server/src/main/java/io/github/qyl66/labdesk/mcp/lab/`，表结构与设备样例在 `mcp-server/src/main/resources/db/labdesk.sql`。部署参数、初始提示词和样例问题分别在 `local/` 与 `resources/database/labdesk_seed.sql`。
