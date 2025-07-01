## 创建 llm proxy service 

```markdown
帮我创建一个代理服务，它提供和 claude api / openai api 兼容两种 api 模式，暂只需提供 completions （含 tool calls ），返回的格式有 stream sse 和 普通 json 两种，返回源是请求另一个支持 openai api 的 server(先留空)。

注意，需保留一个持续显示的 cli 会话，并把请求侧的内容和返回侧的内容都直接显示出来。

技术，使用 kotlin + docker，最终产物是一个 docker 镜象
```

## 创建 github copilot oauth & llm service

```markdown
0. check if '${HOME}/.config/github-copilot/app.json" exists. it's content will be like:

{"github.com:Iv23ctfURkiMfJ4xr5mv":{"oauth_token":"xxxxx","user":"iptton","githubAppId":"Iv23ctfURkiMfJ4xr5mv"},"github.com:Iv1.b507a08c87ecfe98":{"user":"iptton","oauth_token":"xxxxxx","githubAppId":"Iv1.b507a08c87ecfe98"}}

get `$..oauth_token to do step 3

1. add Github Device Auth flow to this project, the client Id is "Iv23ctfURkiMfJ4xr5mv" scope is "copilot" , generate device code and open browser notice user to auth
2. keep get oauth_token , and when got it ,save to '${HOME}/.config/app.json" with json path shown in step 0
3. use oauth_token go fetch apiToken and then get supported models and print them.
4. when user request completion, Send POST to github copilot use model "claude 4"(or fallback to claude 3" and response the result to requester 
```

后续，提供 claude code 的请求和返回细节，修复 openai 格式与 anthropic 格式转换问题
