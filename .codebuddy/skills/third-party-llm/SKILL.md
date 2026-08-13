# 调用第三方大模型

当用户需要调用第三方大模型时，使用以下方式：

1. 读取环境变量中的 API Key
2. 使用 curl 调用第三方大模型 API

## 用法

```bash
curl -X POST https://token-plan-cn.xiaomimimo.com/v1/chat/completions \
  -H "Authorization: Bearer $THIRD_PARTY_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "your-model-name",
    "messages": [{"role": "user", "content": "$QUERY"}]
  }'