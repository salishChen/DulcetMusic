# 基于官方默认 NPC 镜像构建
FROM node:22-bookworm-slim

# 安装基础工具
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git git-lfs curl jq ripgrep \
    && rm -rf /var/lib/apt/lists/* \
    && git lfs install

# 安装 CNB CLI、Skills 及 OpenAI SDK
RUN npm install -g @cnbcool/cnb-cli skills openai

# 加载 CNB 官方 skills
RUN npx skills add https://cnb.cool/cnb/skills/cnb-skill.git -g -y

# 配置自定义大模型环境变量（模型地址与模型名）
ENV OPENAI_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1/chat/completions
ENV OPENAI_MODEL=mimo-v2.5-pro