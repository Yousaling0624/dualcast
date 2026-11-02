#!/usr/bin/env bash
# 三叠播 PWA 一键部署脚本（Cloudflare Pages + 自定义域）
# 用法：
#   export CLOUDFLARE_API_TOKEN=你的Token
#   bash deploy.sh
# Token 权限要求（创建地址 https://dash.cloudflare.com/profile/api-tokens）：
#   Account | Cloudflare Pages | Edit
#   Account | Account Settings  | Read
#   Zone   | DNS              | Edit
#   Zone   | Zone             | Read
set -euo pipefail

DOMAIN="sanlu.yousaling.dpdns.org"
ROOT_DOMAIN="yousaling.dpdns.org"
PROJECT="sanlu"

if [ -z "${CLOUDFLARE_API_TOKEN:-}" ]; then
  echo "错误：请先 export CLOUDFLARE_API_TOKEN=你的Token"
  exit 1
fi

API="https://api.cloudflare.com/client/v4"
AUTH=(-H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" -H "Content-Type: application/json")

echo "==> [1/5] 构建"
npx vite build

echo "==> [2/5] 查询 Account ID"
ACCOUNT_ID=$(curl -s "${AUTH[@]}" "$API/accounts" | python3 -c "
import sys,json
d=json.load(sys.stdin)
assert d['success'], d.get('errors')
print(d['result'][0]['id'])")
echo "Account: $ACCOUNT_ID"

echo "==> [3/5] 创建 Pages 项目（已存在则跳过）"
curl -s "${AUTH[@]}" -X POST "$API/accounts/$ACCOUNT_ID/pages/projects" \
  -d "{\"name\":\"$PROJECT\",\"production_branch\":\"main\"}" >/dev/null || true

echo "==> [4/5] 部署"
npx wrangler pages deploy dist --project-name "$PROJECT" --branch main

echo "==> [5/5] 绑定自定义域 $DOMAIN"
CODE=$(curl -s -o /tmp/bind.json -w "%{http_code}" "${AUTH[@]}" \
  -X POST "$API/accounts/$ACCOUNT_ID/pages/projects/$PROJECT/domains" \
  -d "{\"name\":\"$DOMAIN\"}")
if [ "$CODE" = "409" ] || grep -q "already" /tmp/bind.json; then
  echo "域名已绑定过，跳过"
else
  cat /tmp/bind.json
fi

echo "==> 配置 DNS CNAME（zone: $ROOT_DOMAIN）"
ZONE_ID=$(curl -s "${AUTH[@]}" "$API/zones?name=$ROOT_DOMAIN" | python3 -c "
import sys,json
d=json.load(sys.stdin)
assert d['success'], d.get('errors')
if not d['result']:
    print(''); exit(0)
print(d['result'][0]['id'])")
if [ -z "$ZONE_ID" ]; then
  echo "警告：账号下没有找到 $ROOT_DOMAIN 这个 zone。"
  echo "请先在 Cloudflare 添加该站点（免费计划即可），NS 生效后重新运行本脚本。"
else
  curl -s "${AUTH[@]}" -X POST "$API/zones/$ZONE_ID/dns_records" \
    -d "{\"type\":\"CNAME\",\"name\":\"sanlu\",\"content\":\"$PROJECT.pages.dev\",\"proxied\":true}" | head -c 400
  echo
fi

echo
echo "完成！地址："
echo "  https://$PROJECT.pages.dev"
echo "  https://$DOMAIN （DNS 生效需几分钟）"
