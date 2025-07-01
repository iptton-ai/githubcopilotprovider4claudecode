# GitHub Copilot LLM Provider

一个代理服务，提供 OpenAI API 和 Claude API 兼容的接口，支持将请求转发到其他 LLM 服务提供商。

## 功能特性

### ✅ 已实现功能

- **GitHub Copilot 集成**
  - 自动检测现有 GitHub Copilot 配置
  - OAuth 设备流认证（如需要）
  - 支持 Claude Sonnet 4、Claude 3.7 Sonnet、Claude 3.5 Sonnet 等模型
  - 真实的 API 转发到 GitHub Copilot

- **OpenAI API 兼容**
  - `/v1/chat/completions` 端点
  - 支持流式 (SSE) 和非流式响应
  - 完整的请求验证和错误处理
  - Tool calls 支持
  - 自动模型映射和选择

- **Claude API 兼容**
  - `/v1/messages` 端点
  - 支持流式 (SSE) 和非流式响应
  - Claude 特有的消息格式支持
  - System message 支持
  - 自动转换为 OpenAI 格式

- **智能认证管理**
  - 自动 OAuth token 检测和管理
  - API token 缓存和自动刷新
  - 设备流认证备用方案

- **友好的日志显示**
  - 结构化的请求/响应日志格式
  - 角色图标显示 (👤 User, 🤖 Assistant, 🔧 System)
  - 实时流式响应显示
  - 错误信息格式化
  - CLI 监控界面支持

- **健壮性**
  - 完整的错误处理和验证
  - CORS 支持
  - 结构化日志记录

### 🚧 开发中功能

- CLI 监控界面的完善
- Docker 容器化

## 快速开始

### 方式一：直接运行

```bash
# 构建项目
./gradlew shadowJar

# 运行应用
java -jar build/libs/llm-provider.jar
```

### 方式二：使用 Docker

#### 构建 Docker 镜像

```bash
# 构建应用
./gradlew shadowJar

# 构建 Docker 镜像
docker build -t github-copilot-llm-provider .
```

#### 运行 Docker 容器

```bash
# 基本运行
docker run -p 8080:8080 github-copilot-llm-provider

# 挂载配置目录（推荐）
docker run -p 8080:8080 \
  -v ~/.config:/root/.config \
  github-copilot-llm-provider

# 使用环境变量
docker run -p 8080:8080 \
  -e PORT=8080 \
  -e HOST=0.0.0.0 \
  -v ~/.config:/root/.config \
  github-copilot-llm-provider
```

#### 使用 Docker Compose

```bash
# 启动服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

应用将在 `http://localhost:8080` 启动。

## 友好的日志显示

应用提供了结构化和用户友好的请求/响应日志显示：

### 请求日志格式
```
🤖 OpenAI Chat Completion Request
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 Model: gpt-4
🌡️  Temperature: 0.7
🎯 Max Tokens: 100
🔄 Stream: false

💬 Messages:
  1. 👤 User: Hello, how are you?
  2. 🤖 Assistant: I'm doing well, thank you!
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### 响应日志格式
```
✅ OpenAI Chat Completion Response
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🆔 ID: chatcmpl-123
📋 Model: gpt-4
📊 Usage: 15 prompt + 25 completion = 40 total tokens

🤖 Assistant Response:
  1. 🤖 Assistant: Hello! I'm doing well, thank you for asking.
     🏁 Finish Reason: stop
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### 流式响应显示
```
🌊 Streaming Response Started
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Hello! I'm doing well, thank you for asking. How can I help you today?
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🏁 Streaming Response Completed
```

### Docker 注意事项

1. **配置挂载**: 为了让容器能访问 GitHub Copilot 配置，需要挂载 `~/.config` 目录
2. **网络访问**: 容器需要访问 GitHub API 和 GitHub Copilot API
3. **健康检查**: Docker 容器包含健康检查，确保服务正常运行
4. **日志**: 日志会输出到容器的标准输出，可以通过 `docker logs` 查看

### 环境变量

- `PORT`: 服务器端口 (默认: 8080)
- `HOST`: 服务器主机 (默认: 0.0.0.0)

### 认证配置

应用会自动检测现有的 GitHub Copilot 配置：

1. **自动检测**: 检查 `~/.config/github-copilot/apps.json`
2. **设备认证**: 如果没有现有配置，会启动 OAuth 设备流认证
3. **Token 管理**: 自动获取和刷新 API tokens

### API 使用示例

#### OpenAI API 兼容

```bash
# 非流式请求
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "messages": [
      {
        "role": "user",
        "content": "Hello, world!"
      }
    ],
    "stream": false
  }'

# 流式请求
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "messages": [
      {
        "role": "user",
        "content": "Hello, world!"
      }
    ],
    "stream": true
  }'
```

#### Claude API 兼容

```bash
# 非流式请求
curl -X POST http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-sonnet-20240229",
    "max_tokens": 1000,
    "messages": [
      {
        "role": "user",
        "content": "Hello, Claude!"
      }
    ],
    "stream": false
  }'
```

#### 健康检查

```bash
curl http://localhost:8080/health
```

## 项目结构

```
src/
├── main/kotlin/com/github/copilot/llmprovider/
│   ├── Main.kt                 # 应用程序入口
│   ├── api/                    # API 接口实现
│   │   ├── OpenAIApi.kt       # OpenAI API 兼容接口
│   │   └── ClaudeApi.kt       # Claude API 兼容接口
│   ├── cli/                    # CLI 监控界面
│   │   └── CliMonitor.kt      # 实时监控显示
│   ├── model/                  # 数据模型
│   │   ├── OpenAIModel.kt     # OpenAI API 数据模型
│   │   └── ClaudeModel.kt     # Claude API 数据模型
│   ├── server/                 # 服务器配置
│   │   └── Server.kt          # Ktor 服务器配置
│   └── service/                # 业务服务
│       └── ProxyService.kt    # 代理转发服务
└── test/                       # 测试代码
    └── kotlin/com/github/copilot/llmprovider/
        ├── api/               # API 测试
        ├── model/             # 模型测试
        └── server/            # 服务器测试
```

## 技术栈

- **Kotlin**: 主要编程语言
- **Ktor**: HTTP 服务器框架
- **Kotlinx Serialization**: JSON 序列化
- **Mordant**: 终端 UI 库
- **JUnit 5**: 测试框架
- **Gradle**: 构建工具

## 开发

### 运行测试

```bash
# 运行所有测试
./gradlew test

# 运行特定测试
./gradlew test --tests "*OpenAIApiTest*"
./gradlew test --tests "*ClaudeApiTest*"
```

### 开发模式

```bash
# 使用 Gradle 运行（支持热重载）
./gradlew run
```

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request！

## 支持的模型

通过 GitHub Copilot 集成，支持以下模型：

### Claude 模型（推荐）
- `claude-sonnet-4` - 最新的 Claude 4 模型
- `claude-3.7-sonnet` - Claude 3.7 Sonnet
- `claude-3.5-sonnet` - Claude 3.5 Sonnet

### OpenAI 模型
- `gpt-4o` - GPT-4 Omni
- `gpt-4` - GPT-4
- `gpt-3.5-turbo` - GPT-3.5 Turbo
- `o1` - OpenAI o1 模型

### 其他模型
- `gemini-2.0-flash-001` - Google Gemini 2.0
- 以及更多...

## 验证和测试

### 构建验证
```bash
# 运行完整的构建验证
./verify_build.sh

# 手动构建步骤
./gradlew clean
./gradlew compileKotlin
./gradlew shadowJar
```

### Docker 测试
```bash
# 运行 Docker 集成测试
./test_docker.sh

# 手动 Docker 测试
docker build -t github-copilot-llm-provider .
docker run -p 8080:8080 -v ~/.config:/root/.config github-copilot-llm-provider
```

### API 测试
```bash
# 启动服务后运行 API 测试
python3 test_server.py

# 测试 Claude content 格式兼容性
python3 test_claude_content.py

# 演示友好的日志显示
python3 demo_friendly_logs.py

# 或者运行端到端测试
python3 test_end_to_end.py
```

## 故障排除

### 常见问题

1. **构建失败**: 确保使用 Java 17+ 和最新的 Gradle
2. **认证失败**: 检查 `~/.config/github-copilot/apps.json` 文件是否存在
3. **Docker 权限**: 确保 Docker 有权限访问配置目录
4. **网络问题**: 确保能访问 GitHub API 和 GitHub Copilot API

### 日志调试
```bash
# 查看应用日志
java -jar build/libs/llm-provider.jar

# 查看 Docker 日志
docker logs <container-id>
```

## 下一步计划

1. 完善 CLI 监控界面
2. 添加配置文件支持
3. 性能优化和缓存
4. 添加速率限制
5. 添加监控和指标收集
6. 添加负载均衡支持
