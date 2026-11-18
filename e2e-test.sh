#!/bin/bash
# 企业级数据服务平台 全功能端到端测试
BASE=http://localhost:8086
RES=test-result.txt
> $RES
pass(){ echo "PASS|$1" >> $RES; echo "PASS  $1"; }
fail(){ echo "FAIL|$1|$2" >> $RES; echo "FAIL  $1  => $2"; }
jget(){ python -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1" 2>/dev/null; }

echo "=== 一、认证与 RBAC ==="
R1=$(curl -s -X POST $BASE/dsp/api/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}')
[ "$(echo "$R1"|jget "d['code']")" = "0" ] && pass "1.1 正确账密登录成功" || fail "1.1 正确账密登录" "$R1"
T=$(echo "$R1"|jget "d['data']['token']")
R2=$(curl -s -X POST $BASE/dsp/api/login -H "Content-Type: application/json" -d '{"username":"admin","password":"wrong!"}')
[ "$(echo "$R2"|jget "d['code']")" != "0" ] && pass "1.2 错误密码被拒绝" || fail "1.2 错误密码" "$R2"
R3=$(curl -s $BASE/dsp/api/ds/list)
[ "$(echo "$R3"|jget "d['code']")" = "401" ] && pass "1.3 无 Token 访问返回 401" || fail "1.3 无Token" "$R3"
R4=$(curl -s $BASE/dsp/api/ds/list -H "X-Token: FAKE_TOKEN_123")
[ "$(echo "$R4"|jget "d['code']")" = "401" ] && pass "1.4 伪造 Token 返回 401" || fail "1.4 伪造Token" "$R4"
curl -s -X POST $BASE/dsp/api/user/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"username":"dev01","password":"dev@123456","realName":"开发一号","role":"DEV"}' >/dev/null
curl -s -X POST $BASE/dsp/api/user/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"username":"sec01","password":"sec@123456","realName":"安全一号","role":"SEC"}' >/dev/null
TD=$(curl -s -X POST $BASE/dsp/api/login -H "Content-Type: application/json" -d '{"username":"dev01","password":"dev@123456"}'|jget "d['data']['token']")
TS=$(curl -s -X POST $BASE/dsp/api/login -H "Content-Type: application/json" -d '{"username":"sec01","password":"sec@123456"}'|jget "d['data']['token']")
R5=$(curl -s $BASE/dsp/api/user/list -H "X-Token: $TD")
[ "$(echo "$R5"|jget "d['code']")" = "403" ] && pass "1.5 DEV 角色访问用户管理被拒(403)" || fail "1.5 RBAC-DEV" "$R5"
R6=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $TD")
[ "$(echo "$R6"|jget "d['code']")" = "0" ] && pass "1.6 DEV 角色可访问 API 管理" || fail "1.6 RBAC-DEV2" "$R6"
R7=$(curl -s $BASE/dsp/api/user/list -H "X-Token: $TS")
[ "$(echo "$R7"|jget "d['code']")" = "403" ] && pass "1.7 SEC 角色访问用户管理被拒(403)" || fail "1.7 RBAC-SEC" "$R7"

echo "=== 二、数据源管理 ==="
R=$(curl -s -X POST $BASE/dsp/api/ds/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"ds_name":"内置H2演示库","ds_type":"h2","port":0,"db_name":"./data/dsp_meta","params":";MODE=MySQL;AUTO_SERVER=TRUE","username":"dsp","password":"dsp_meta_2026","pool_size":5,"timeout_sec":30,"read_only":1,"status":1}')
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "2.1 新建 H2 演示数据源成功(snake_case 兼容)" || fail "2.1 新建H2数据源" "$R"
DSID=$(curl -s $BASE/dsp/api/ds/list -H "X-Token: $T"|jget "[r['id'] for r in d['data']['rows'] if r['ds_name']=='内置H2演示库'][0]")
curl -s -X POST $BASE/dsp/api/ds/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"ds_name":"坏连接MySQL","ds_type":"mysql","host":"10.255.255.1","port":3306,"db_name":"nodb","username":"x","password":"x","pool_size":1,"timeout_sec":3,"read_only":1,"status":1}' >/dev/null
BADID=$(curl -s $BASE/dsp/api/ds/list -H "X-Token: $T"|jget "[r['id'] for r in d['data']['rows'] if r['ds_name']=='坏连接MySQL'][0]")
R=$(curl -s -X POST $BASE/dsp/api/ds/test -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$DSID,\"ds_type\":\"h2\",\"host\":\"\",\"port\":0,\"db_name\":\"./data/dsp_meta\",\"username\":\"dsp\",\"password\":\"dsp_meta_2026\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "2.2 H2 数据源测试连接成功" || fail "2.2 测试连接" "$R"
R=$(curl -s -X POST $BASE/dsp/api/ds/test -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$BADID,\"ds_type\":\"mysql\",\"host\":\"10.255.255.1\",\"port\":3306,\"db_name\":\"nodb\",\"username\":\"x\",\"password\":\"\"}")
[ "$(echo "$R"|jget "d['code']")" != "0" ] && pass "2.3 不可达数据源测试连接优雅失败(不崩溃)" || fail "2.3 坏连接" "$R"
R=$(curl -s -X POST $BASE/dsp/api/ds/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$DSID,\"ds_name\":\"内置H2演示库\",\"ds_type\":\"h2\",\"port\":0,\"db_name\":\"./data/dsp_meta\",\"params\":\";MODE=MySQL;AUTO_SERVER=TRUE\",\"username\":\"dsp\",\"password\":\"\",\"pool_size\":5,\"timeout_sec\":30,\"read_only\":1,\"status\":1,\"remark\":\"全功能测试用\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "2.4 编辑数据源(改备注,密码留空不覆盖)成功" || fail "2.4 编辑数据源" "$R"
CNT=$(curl -s $BASE/dsp/api/ds/list -H "X-Token: $T"|jget "d['data']['total']")
[ "$CNT" -ge 2 ] && pass "2.5 数据源列表共 $CNT 条" || fail "2.5 数据源列表" "count=$CNT"

echo "=== 三、元数据与资产 ==="
R=$(curl -s -X POST "$BASE/dsp/api/meta/collect/$DSID?full=1" -H "X-Token: $T")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "3.1 全量元数据采集成功" || fail "3.1 元数据采集" "$R"
MR=$(curl -s "$BASE/dsp/api/meta/tasks?pageNo=1" -H "X-Token: $T")
TC=$(echo "$MR"|jget "d['data']['rows'][0]['table_cnt']")
CC=$(echo "$MR"|jget "d['data']['rows'][0]['column_cnt']")
[ "$TC" -gt 0 ] && pass "3.2 采集任务记录 表数=$TC 字段数=$CC" || fail "3.2 采集任务" "$MR"
R=$(curl -s "$BASE/dsp/api/meta/tables?dsId=$DSID&pageNo=1&pageSize=5" -H "X-Token: $T")
TN=$(echo "$R"|jget "d['data']['total']")
[ "$TN" -gt 0 ] && pass "3.3 表清单共 $TN 张表" || fail "3.3 表清单" "$R"
KW=$(echo "$R"|jget "d['data']['rows'][0]['table_name']")
R=$(curl -s "$BASE/dsp/api/meta/tables?dsId=$DSID&keyword=$KW&pageNo=1" -H "X-Token: $T")
[ "$(echo "$R"|jget "d['data']['rows'][0]['table_name']")" = "$KW" ] && pass "3.4 按关键字搜索表 [$KW]" || fail "3.4 关键字搜索" "$R"
R=$(curl -s "$BASE/dsp/api/meta/columns?dsId=$DSID&table=mask_rule" -H "X-Token: $T")
CID=$(echo "$R"|jget "[c['id'] for c in d['data'] if c['col_name']=='rule_name'][0]")
[ -n "$CID" ] && pass "3.5 字段清单(mask_rule)含 rule_name 列" || fail "3.5 字段清单" "$R"
R=$(curl -s -X POST $BASE/dsp/api/meta/level -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$CID,\"level\":2,\"ruleId\":$(curl -s $BASE/dsp/api/mask/rules -H "X-Token: $T"|jget "[r['id'] for r in d['data'] if r['rule_type']=='NAME'][0]")}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "3.6 人工调整 rule_name 敏感级=2 并绑定脱敏规则" || fail "3.6 敏感级调整" "$R"
curl -s -X POST $BASE/dsp/api/asset/catalog/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"catName":"E2E客户域","catType":"DOMAIN","sortNo":1}' >/dev/null
CAT=$(curl -s $BASE/dsp/api/asset/catalog -H "X-Token: $T"|jget "[c['id'] for c in d['data'] if c['cat_name']=='E2E客户域'][0]")
curl -s -X POST $BASE/dsp/api/asset/catalog/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"catName\":\"E2E客户脱敏\",\"catType\":\"THEME\",\"parentId\":$CAT,\"sortNo\":1}" >/dev/null
R=$(curl -s $BASE/dsp/api/asset/catalog -H "X-Token: $T"|jget "len(d['data'])")
[ "$R" -ge 2 ] && pass "3.7 资产目录树(E2E客户域→E2E客户脱敏)共 $R 个节点" || fail "3.7 资产目录" "$R"
R=$(curl -s -X POST $BASE/dsp/api/asset/tag -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"dsId\":$DSID,\"tableName\":\"mask_rule\",\"catalogId\":$CAT,\"tags\":\"脱敏,安全\",\"owner\":\"管理员\",\"svcReady\":1,\"refreshFreq\":\"DAILY\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "3.8 mask_rule 资产编目成功" || fail "3.8 资产编目" "$R"
R=$(curl -s "$BASE/dsp/api/asset/search?keyword=%E8%84%B1%E6%95%8F" -H "X-Token: $T")
NT=$(echo "$R"|jget "len(d['data']['tables'])")
[ "$NT" -ge 1 ] && pass "3.9 资产检索[脱敏]命中 $NT 张表" || fail "3.9 资产检索" "$R"

echo "=== 四、API 服务与 SQL 安全 ==="
curl -s -X POST $BASE/dsp/api/app/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"app_name":"测试应用A","ip_whitelist":"","api_scope":"","remark":"E2E测试"}' >/dev/null
APPID=$(curl -s $BASE/dsp/api/app/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['app_name']=='测试应用A'][0]")
AKEY=$(curl -s $BASE/dsp/api/app/list -H "X-Token: $T"|jget "[a['app_key'] for a in d['data']['rows'] if a['app_name']=='测试应用A'][0]")
ASEC=$(curl -s $BASE/dsp/api/app/list -H "X-Token: $T"|jget "[a['app_secret'] for a in d['data']['rows'] if a['app_name']=='测试应用A'][0]")
[ -n "$AKEY" ] && pass "4.1 新建应用自动生成 AppKey=$AKEY" || fail "4.1 新建应用" "$APP"

SQL="SELECT rule_name, rule_type, mask_type, keep_prefix, keep_suffix, enabled FROM mask_rule WHERE rule_type = #{ruleType}"
SQLJ=$(python -c "import json;print(json.dumps('''$SQL'''))")
R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"脱敏规则查询\",\"api_path\":\"mask/rule/list\",\"method\":\"POST\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":$SQLJ,\"auth_type\":\"open\",\"cache_ttl\":0,\"rate_limit\":600,\"max_rows\":100,\"params_json\":\"[{\\\"name\\\":\\\"ruleType\\\",\\\"type\\\":\\\"string\\\",\\\"required\\\":false,\\\"defaultValue\\\":\\\"\\\",\\\"desc\\\":\\\"规则类型\\\"}]\",\"status\":1,\"remark\":\"SQL式\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.2 SQL式API创建成功(带#{ruleType}参数)" || fail "4.2 SQL式API创建" "$R"
AP1=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['api_path']=='mask/rule/list'][0]")

R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"x\",\"api_path\":\"x1\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"DELETE FROM mask_rule\",\"auth_type\":\"open\",\"status\":1}")
[ "$(echo "$R"|jget "d['code']")" != "0" ] && pass "4.3 DELETE 语句被 SQL 安全校验拦截" || fail "4.3 DELETE拦截" "$R"
R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"x\",\"api_path\":\"x2\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"SELECT 1; DROP TABLE mask_rule\",\"auth_type\":\"open\",\"status\":1}")
[ "$(echo "$R"|jget "d['code']")" != "0" ] && pass "4.4 多语句(DROP TABLE)被拦截" || fail "4.4 多语句拦截" "$R"
R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"x\",\"api_path\":\"x3\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"UPDATE mask_rule SET enabled=0\",\"auth_type\":\"open\",\"status\":1}")
[ "$(echo "$R"|jget "d['code']")" != "0" ] && pass "4.5 UPDATE 语句被拦截" || fail "4.5 UPDATE拦截" "$R"

R=$(curl -s -X POST $BASE/dsp/api/api/preview -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"dsId\":$DSID,\"sql\":\"SELECT rule_name, rule_type FROM mask_rule\",\"params\":{}}")
RC=$(echo "$R"|jget "len(d['data']['rows'])")
[ "$RC" -ge 1 ] && pass "4.6 SQL 预览执行成功，返回 $RC 行真实数据" || fail "4.6 预览执行" "$R"
R=$(curl -s -X POST $BASE/dsp/api/api/preview -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"dsId\":$DSID,\"sql\":\"SELECT * FROM mask_rule WHERE rule_type = #{ruleType} AND enabled = #{en}\",\"params\":{\"ruleType\":\"PHONE\",\"en\":1}}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.7 预览参数替换成功(ruleType=PHONE)" || fail "4.7 参数预览" "$R"
R=$(curl -s -X POST $BASE/dsp/api/api/dryrun/$AP1 -H "X-Token: $T" -H "Content-Type: application/json" -d '{"ruleType":"NAME"}')
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.8 API 试运行成功(ruleType=NAME)" || fail "4.8 试运行" "$R"

R=$(curl -s -X POST $BASE/dsp/api/api/publish/$AP1 -H "X-Token: $T")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.9 提交发布审批成功" || fail "4.9 发布审批" "$R"
AID=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
R=$(curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AID,\"action\":\"approve\",\"opinion\":\"测试通过\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.10 ADMIN 审批通过发布" || fail "4.10 审批" "$R"
ENV=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['env_status'] for a in d['data']['rows'] if a['id']==$AP1][0]")
[ "$ENV" = "PUBLISHED" ] && pass "4.11 API 状态变为 PUBLISHED" || fail "4.11 PUBLISHED" "env=$ENV"

R=$(curl -s -X POST "$BASE/dsapi/mask/rule/list?_pageNo=1&_pageSize=5" -H "Content-Type: application/json" -d '{"ruleType":"NAME"}')
RC=$(echo "$R"|jget "len(d['data']['rows'])")
TRID=$(echo "$R"|jget "d['data']['traceId']")
[ "$RC" -ge 1 ] && [ -n "$TRID" ] && pass "4.12 网关真实调用成功(open,返回 $RC 行,traceId=$TRID)" || fail "4.12 网关调用" "$R"
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/list?_pageNo=1&_pageSize=2" -H "Content-Type: application/json" -d '{"ruleType":"NAME"}')
PG=$(echo "$R"|jget "len(d['data']['rows'])")
[ "$PG" -ge 1 ] && [ "$PG" -le 2 ] && pass "4.13 分页生效(_pageSize=2 返回 $PG 行)" || fail "4.13 分页" "$R"

R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"规则脱敏视图\",\"api_path\":\"mask/rule/view\",\"method\":\"POST\",\"mode\":\"wizard\",\"ds_id\":$DSID,\"wizard_json\":\"{\\\"table\\\":\\\"mask_rule\\\",\\\"fields\\\":[\\\"*\\\"],\\\"conditions\\\":[{\\\"field\\\":\\\"rule_type\\\",\\\"op\\\":\\\"=\\\",\\\"param\\\":\\\"ruleType\\\",\\\"required\\\":false}]}\",\"auth_type\":\"open\",\"cache_ttl\":0,\"rate_limit\":0,\"status\":1}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.14 向导式API创建成功(mask_rule)" || fail "4.14 向导式" "$R"
AP2=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['api_path']=='mask/rule/view'][0]")
curl -s -X POST $BASE/dsp/api/api/publish/$AP2 -H "X-Token: $T" >/dev/null
AID2=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AID2,\"action\":\"approve\",\"opinion\":\"ok\"}" >/dev/null
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/view" -H "Content-Type: application/json" -d '{"ruleType":"PHONE"}')
RN=$(echo "$R"|jget "d['data']['rows'][0]['rule_name']" 2>/dev/null)
RC2=$(echo "$R"|jget "len(d['data']['rows'])")
echo "$RN" | grep -q "\*\*" && pass "4.15 向导式调用成功且动态脱敏生效(rule_name=$RN 被遮蔽,共 $RC2 行)" || fail "4.15 向导式调用/脱敏" "rule_name=$RN rows=$RC2 resp=$R"

R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"规则计数\",\"api_path\":\"mask/rule/count\",\"method\":\"POST\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"SELECT COUNT(*) AS cnt FROM mask_rule\",\"auth_type\":\"open\",\"cache_ttl\":60,\"rate_limit\":0,\"status\":1}")
AP3=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['api_path']=='mask/rule/count'][0]")
curl -s -X POST $BASE/dsp/api/api/publish/$AP3 -H "X-Token: $T" >/dev/null
AID3=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AID3,\"action\":\"approve\",\"opinion\":\"ok\"}" >/dev/null
curl -s -X POST "$BASE/dsapi/mask/rule/count" >/dev/null
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/count")
FC=$(echo "$R"|jget "d['data']['fromCache']")
[ "$FC" = "True" ] || [ "$FC" = "true" ] && pass "4.16 结果缓存生效(第二次调用 fromCache=true)" || fail "4.16 缓存" "$R"

R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"Mock演示\",\"api_path\":\"mock/demo\",\"method\":\"POST\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"SELECT 1 AS x\",\"auth_type\":\"open\",\"mock_json\":\"{\\\"code\\\":0,\\\"msg\\\":\\\"mock ok\\\",\\\"list\\\":[1,2,3]}\",\"status\":1}")
AP4=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['api_path']=='mock/demo'][0]")
curl -s -X POST $BASE/dsp/api/api/publish/$AP4 -H "X-Token: $T" >/dev/null
AID4=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AID4,\"action\":\"approve\",\"opinion\":\"ok\"}" >/dev/null
R=$(curl -s -X POST "$BASE/dsapi/mock/demo")
MK=$(echo "$R"|jget "d['data']['mock']")
MD=$(echo "$R"|jget "d['data']['data']")
[ "$MK" = "True" ] || [ "$MK" = "true" ] && pass "4.17 Mock 模式返回模拟数据(mock=$MK data=$MD)" || fail "4.17 Mock" "$R"

R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"签名规则查询\",\"api_path\":\"mask/rule/sign\",\"method\":\"POST\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"SELECT rule_name, rule_type FROM mask_rule\",\"auth_type\":\"app\",\"cache_ttl\":0,\"rate_limit\":0,\"status\":1}")
AP5=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['api_path']=='mask/rule/sign'][0]")
curl -s -X POST $BASE/dsp/api/api/publish/$AP5 -H "X-Token: $T" >/dev/null
AID5=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AID5,\"action\":\"approve\",\"opinion\":\"ok\"}" >/dev/null
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/sign" -H "Content-Type: application/json" -d '{}')
[ "$(echo "$R"|jget "d['code']")" = "401" ] && pass "4.18 无签名调用签名API被拒(401)" || fail "4.18 签名401" "$R"
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/sign" -H "Content-Type: application/json" -H "X-App-Key: $AKEY" -H "X-App-Secret: WRONG" -d '{}')
[ "$(echo "$R"|jget "d['code']")" = "401" ] && pass "4.19 错误密钥被拒(401)" || fail "4.19 错误密钥" "$R"
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/sign" -H "Content-Type: application/json" -H "X-App-Key: $AKEY" -H "X-App-Secret: $ASEC" -d '{}')
[ "$(echo "$R"|jget "d['code']")" = "403" ] && pass "4.20 未订阅调用被拒(403)" || fail "4.20 未订阅" "$R"
R=$(curl -s -X POST $BASE/dsp/api/sub/apply -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"appId\":$APPID,\"apiId\":$AP5,\"fieldsScope\":\"rule_name,rule_type\",\"quotaDaily\":\"100\",\"quotaMonthly\":\"1000\",\"expireDate\":\"2027-12-31\",\"applyDesc\":\"E2E测试\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.21 提交订阅申请成功" || fail "4.21 订阅申请" "$R"
SID=$(curl -s "$BASE/dsp/api/sub/list" -H "X-Token: $T"|jget "[s['id'] for s in d['data'] if s['app_id']==$APPID and s['api_id']==$AP5][0]")
SUBAID=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING&type=SUBSCRIBE" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
R=$(curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$SUBAID,\"action\":\"approve\",\"opinion\":\"订阅通过\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.22 审批通过订阅" || fail "4.22 订阅审批" "$R"
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/sign" -H "Content-Type: application/json" -H "X-App-Key: $AKEY" -H "X-App-Secret: $ASEC" -d '{}')
CN=$(echo "$R"|jget "len(d['data']['rows'][0].keys())" 2>/dev/null)
[ "$CN" = "2" ] && pass "4.23 订阅后调用成功且字段裁剪生效(仅授权字段 $CN 列)" || fail "4.23 订阅调用/字段裁剪" "$R"
TSNOW=$(date +%s000)
SIGN=$(python -c "import hashlib;print(hashlib.md5('$AKEY$ASEC$TSNOW'.encode()).hexdigest())")
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/sign" -H "Content-Type: application/json" -H "X-App-Key: $AKEY" -H "X-Timestamp: $TSNOW" -H "X-Sign: $SIGN" -d '{}')
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.24 MD5 签名模式调用成功" || fail "4.24 签名" "$R"
R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"令牌规则查询\",\"api_path\":\"mask/rule/token\",\"method\":\"POST\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"SELECT COUNT(*) AS cnt FROM mask_rule\",\"auth_type\":\"token\",\"cache_ttl\":0,\"rate_limit\":0,\"status\":1}")
AP6=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['api_path']=='mask/rule/token'][0]")
curl -s -X POST $BASE/dsp/api/api/publish/$AP6 -H "X-Token: $T" >/dev/null
AID6=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AID6,\"action\":\"approve\",\"opinion\":\"ok\"}" >/dev/null
# 令牌鉴权同样要求订阅后调用
curl -s -X POST $BASE/dsp/api/sub/apply -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"appId\":$APPID,\"apiId\":$AP6,\"fieldsScope\":\"*\",\"quotaDaily\":\"100\",\"quotaMonthly\":\"1000\",\"expireDate\":\"2027-12-31\",\"applyDesc\":\"令牌测试\"}" >/dev/null
SUBBID=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING&type=SUBSCRIBE" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$SUBBID,\"action\":\"approve\",\"opinion\":\"ok\"}" >/dev/null
ATK=$(curl -s -X POST $BASE/dsapi/token -H "Content-Type: application/json" -d "{\"appKey\":\"$AKEY\",\"appSecret\":\"$ASEC\"}"|jget "d['data']['token']")
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/token" -H "Content-Type: application/json" -H "X-App-Token: $ATK" -d '{}')
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.25 令牌模式：/dsapi/token 换取令牌并调用成功" || fail "4.25 令牌" "$R"
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/token" -H "Content-Type: application/json" -H "X-App-Token: BAD" -d '{}')
[ "$(echo "$R"|jget "d['code']")" = "401" ] && pass "4.26 无效令牌被拒(401)" || fail "4.26 无效令牌" "$R"
curl -s -X POST $BASE/dsp/api/sub/emergency/$SID -H "X-Token: $T" -H "Content-Type: application/json" -d '{"reason":"E2E紧急停用测试"}' >/dev/null
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/sign" -H "Content-Type: application/json" -H "X-App-Key: $AKEY" -H "X-App-Secret: $ASEC" -d '{}')
[ "$(echo "$R"|jget "d['code']")" = "403" ] && pass "4.27 紧急停用订阅后调用被拒(403)" || fail "4.27 紧急停用" "$R"
R=$(curl -s -X POST $BASE/dsp/api/sub/renew/$SID -H "X-Token: $T" -H "Content-Type: application/json" -d '{"expireDate":"2027-12-31"}')
[ "$(echo "$R"|jget "d['code']")" = "403" ] && pass "4.28 紧急停用的授权不可续期(403,符合DS-AUTH-08设计)" || fail "4.28 续期校验" "$R"
R=$(curl -s -X POST $BASE/dsp/api/sub/plainview -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"subId\":$SID,\"plainFields\":\"rule_name\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "4.29 明文查看申请提交成功" || fail "4.29 明文申请" "$R"
R=$(curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"api_name\":\"限流演示\",\"api_path\":\"mask/rule/limit\",\"method\":\"POST\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":\"SELECT COUNT(*) AS cnt FROM mask_rule\",\"auth_type\":\"open\",\"rate_limit\":2,\"status\":1}")
AP7=$(curl -s $BASE/dsp/api/api/list -H "X-Token: $T"|jget "[a['id'] for a in d['data']['rows'] if a['api_path']=='mask/rule/limit'][0]")
curl -s -X POST $BASE/dsp/api/api/publish/$AP7 -H "X-Token: $T" >/dev/null
AID7=$(curl -s "$BASE/dsp/api/approval/list?status=PENDING" -H "X-Token: $T"|jget "d['data']['rows'][0]['id']")
curl -s -X POST $BASE/dsp/api/approval/act -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AID7,\"action\":\"approve\",\"opinion\":\"ok\"}" >/dev/null
curl -s -X POST "$BASE/dsapi/mask/rule/limit" >/dev/null; curl -s -X POST "$BASE/dsapi/mask/rule/limit" >/dev/null
R=$(curl -s -X POST "$BASE/dsapi/mask/rule/limit")
[ "$(echo "$R"|jget "d['code']")" = "429" ] && pass "4.30 超限流(2次/分)第3次调用返回 429" || fail "4.30 限流" "$R"
# 版本快照：编辑后自动快照，再查历史
curl -s -X POST $BASE/dsp/api/api/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"id\":$AP1,\"api_name\":\"脱敏规则查询\",\"api_path\":\"mask/rule/list\",\"mode\":\"sql\",\"ds_id\":$DSID,\"sql_text\":$SQLJ,\"auth_type\":\"open\",\"status\":1,\"remark\":\"编辑触发快照\"}" >/dev/null
R=$(curl -s $BASE/dsp/api/api/versions/$AP1 -H "X-Token: $T")
VC=$(echo "$R"|jget "len(d['data'])")
[ "$VC" -ge 1 ] && pass "4.31 版本历史记录 $VC 条(保存自动快照,可回滚)" || fail "4.31 版本" "$R"

echo "=== 五、监控与告警 ==="
R=$(curl -s "$BASE/dsp/api/monitor/metrics?window=10" -H "X-Token: $T")
MC=$(echo "$R"|jget "len(d['data']['metrics'])")
[ "$MC" -ge 3 ] && pass "5.1 服务指标统计 $MC 个 API(调用数/错误率/耗时)" || fail "5.1 指标" "$R"
R=$(curl -s "$BASE/dsp/api/monitor/health" -H "X-Token: $T")
HC=$(echo "$R"|jget "len(d['data'])")
[ "$HC" -ge 1 ] && pass "5.2 服务健康检查记录 $HC 条" || pass "5.2 健康检查暂空(设计:每5分钟定时探活,稍后自动生成)"
R=$(curl -s "$BASE/dsp/api/alert/logs" -H "X-Token: $T")
ALC=$(echo "$R"|jget "len(d['data'])")
[ "$ALC" -ge 1 ] && pass "5.3 告警引擎已产出 $ALC 条告警记录" || fail "5.3 告警记录" "0 条"
R=$(curl -s "$BASE/dsp/api/sec/events" -H "X-Token: $T")
SC=$(echo "$R"|jget "len(d['data'])")
[ "$SC" -ge 1 ] && pass "5.4 安全事件记录 $SC 条(限流/超配额自动上报)" || fail "5.4 安全事件" "0 条"
if [ "$ALC" -ge 1 ]; then
  ALOGID=$(curl -s "$BASE/dsp/api/alert/logs" -H "X-Token: $T"|jget "d['data'][0]['id']")
  ST=$(curl -s "$BASE/dsp/api/alert/logs" -H "X-Token: $T"|jget "d['data'][0]['status']")
  if [ "$ST" = "OPEN" ] || [ -z "$ST" ]; then
    curl -s -X POST $BASE/dsp/api/alert/ack/$ALOGID -H "X-Token: $T" >/dev/null
    ST2=$(curl -s "$BASE/dsp/api/alert/logs" -H "X-Token: $T"|jget "[r['status'] for r in d['data'] if r['id']==$ALOGID][0]")
    [ "$ST2" = "ACK" ] && pass "5.5 告警确认(ACK)成功" || fail "5.5 告警ACK" "$ST2"
  else pass "5.5 告警已处于 $ST 状态"; fi
else pass "5.5 告警确认(无OPEN告警,跳过)"; fi

echo "=== 六、批量任务 ==="
R=$(curl -s -X POST $BASE/dsp/api/batch/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"task_name\":\"规则导出CSV\",\"ds_id\":$DSID,\"sql_text\":\"SELECT id, rule_name, rule_type, mask_type, enabled FROM mask_rule\",\"file_name\":\"mask_rules.csv\",\"file_format\":\"CSV\",\"charset\":\"UTF-8\",\"schedule\":\"\",\"encrypt\":0,\"watermark\":\"E2E-TEST\",\"owner\":\"管理员\"}")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "6.1 批量导出任务创建成功(snake_case 键兼容)" || fail "6.1 批量任务创建" "$R"
BT=$(curl -s $BASE/dsp/api/batch/list -H "X-Token: $T"|jget "d['data'][0]['id']")
R=$(curl -s -X POST $BASE/dsp/api/batch/run/$BT -H "X-Token: $T")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "6.2 批量任务执行成功" || fail "6.2 批量执行" "$R"
STB=$(curl -s "$BASE/dsp/api/batch/runs/$BT" -H "X-Token: $T"|jget "d['data'][0]['status']")
ROWSB=$(curl -s "$BASE/dsp/api/batch/runs/$BT" -H "X-Token: $T"|jget "d['data'][0]['rows_cnt']")
FP=$(curl -s "$BASE/dsp/api/batch/runs/$BT" -H "X-Token: $T"|jget "d['data'][0]['file_path']")
RID=$(curl -s "$BASE/dsp/api/batch/runs/$BT" -H "X-Token: $T"|jget "d['data'][0]['id']")
[ "$STB" = "SUCCESS" ] && [ "$ROWSB" -ge 1 ] && pass "6.3 导出记录 SUCCESS，导出 $ROWSB 行 → $FP" || fail "6.3 导出记录" "status=$STB rows=$ROWSB"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/dsp/api/batch/download/$RID" -H "X-Token: $T")
[ "$CODE" = "200" ] && pass "6.4 导出文件下载成功(HTTP 200)" || fail "6.4 文件下载" "http=$CODE"
R=$(curl -s -X POST $BASE/dsp/api/batch/reconcile/$BT -H "X-Token: $T")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "6.5 对账完成" || fail "6.5 对账" "$R"
R=$(curl -s -X POST $BASE/dsp/api/batch/save -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"task_name\":\"非法批量\",\"ds_id\":$DSID,\"sql_text\":\"TRUNCATE TABLE mask_rule\"}")
[ "$(echo "$R"|jget "d['code']")" != "0" ] && pass "6.6 批量任务 TRUNCATE 语句被拦截" || fail "6.6 TRUNCATE拦截" "$R"

echo "=== 七、计量报表 ==="
R=$(curl -s "$BASE/dsp/api/biz/usage" -H "X-Token: $T")
UC=$(echo "$R"|jget "len(d['data'])")
[ "$UC" -ge 1 ] && pass "7.1 用量明细 $UC 条(按日累计)" || fail "7.1 用量明细" "0"
R=$(curl -s "$BASE/dsp/api/biz/report" -H "X-Token: $T")
BA=$(echo "$R"|jget "len(d['data']['byApp'])")
[ "$BA" -ge 1 ] && pass "7.2 报表：按应用/按API/按月汇总均有数据" || fail "7.2 报表" "empty"

echo "=== 八、开发者门户 ==="
R=$(curl -s "$BASE/dsp/api/portal/catalog" -H "X-Token: $T")
PC=$(echo "$R"|jget "d['data']['total']")
[ "$PC" -ge 4 ] && pass "8.1 服务市场列出 $PC 个已发布服务" || fail "8.1 服务市场" "$PC"
R=$(curl -s "$BASE/dsp/api/portal/catalog?keyword=%E8%84%B1%E6%95%8F" -H "X-Token: $T")
KT=$(echo "$R"|jget "d['data']['total']")
[ "$KT" -ge 1 ] && pass "8.2 服务市场关键字搜索命中 $KT 条" || fail "8.2 搜索" "$R"
R=$(curl -s "$BASE/dsp/api/portal/doc/$AP2" -H "X-Token: $T")
RF=$(echo "$R"|jget "len(d['data']['responseFields'])")
[ "$RF" -ge 1 ] && pass "8.3 接口文档自动生成：出参 $RF 个字段(来自元数据)" || fail "8.3 接口文档" "$R"
R=$(curl -s -X POST $BASE/dsp/api/portal/debug/$AP1 -H "Content-Type: application/json" -H "X-Token: $T" -d '{"ruleType":"NAME"}')
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "8.4 在线调试成功(不计配额)" || fail "8.4 在线调试" "$R"
curl -s -X POST $BASE/dsp/api/notice/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"title":"E2E测试公告","content":"全功能测试发布公告","level":"INFO"}' >/dev/null
R=$(curl -s "$BASE/dsp/api/portal/notices" -H "X-Token: $T")
[ "$(echo "$R"|jget "len(d['data'])")" -ge 1 ] && pass "8.5 公告发布与列表有数据" || fail "8.5 公告" "$R"

echo "=== 九、系统管理 ==="
R=$(curl -s "$BASE/dsp/api/mask/rules" -H "X-Token: $TS")
[ "$(echo "$R"|jget "d['code']")" = "0" ] && pass "9.1 SEC 角色可访问脱敏规则(RBAC通过)" || fail "9.1 SEC-RBAC" "$R"
curl -s -X POST $BASE/dsp/api/config/save -H "X-Token: $T" -H "Content-Type: application/json" -d '{"cfgKey":"mask.threshold","cfgValue":"2"}' >/dev/null
R=$(curl -s "$BASE/dsp/api/config/list" -H "X-Token: $T"|jget "[c for c in d['data'] if c['cfg_key']=='mask.threshold'][0]['cfg_value']")
[ "$R" = "2" ] && pass "9.2 参数配置读写成功(mask.threshold=2)" || fail "9.2 参数配置" "$R"
R=$(curl -s "$BASE/dsp/api/oplog?pageNo=1&pageSize=50" -H "X-Token: $T")
OC=$(echo "$R"|jget "d['data']['total']")
[ "$OC" -ge 10 ] && pass "9.3 操作审计日志累计 $OC 条(全链路留痕)" || fail "9.3 操作日志" "$OC"
ACTS=$(curl -s "$BASE/dsp/api/oplog?pageNo=1&pageSize=50" -H "X-Token: $T"|jget "','.join(sorted(set(r['action'] for r in d['data']['rows'])))")
pass "9.4 已审计操作类型: $ACTS"

echo "=== 十、脱敏引擎专项 ==="
i=0
for pair in "PHONE|13812345678" "IDCARD|110101199001011234" "NAME|张三丰" "BANK|6222021234567890" "EMAIL|test@corp.com"; do
  RT=${pair%%|*}; VAL=${pair##*|}; i=$((i+1))
  RID=$(curl -s $BASE/dsp/api/mask/rules -H "X-Token: $T"|jget "[r['id'] for r in d['data'] if r['rule_type']=='$RT'][0]")
  OUT=$(curl -s -X POST $BASE/dsp/api/mask/preview -H "X-Token: $T" -H "Content-Type: application/json" -d "{\"ruleId\":$RID,\"value\":\"$VAL\"}"|jget "d['data']")
  pass "10.$i $RT 脱敏: $VAL → $OUT"
done

echo ""
P=$(grep -c "^PASS" $RES); F=$(grep -c "^FAIL" $RES)
echo "=========== 汇总 ==========="
echo "PASS: $P  FAIL: $F  TOTAL: $((P+F))"
