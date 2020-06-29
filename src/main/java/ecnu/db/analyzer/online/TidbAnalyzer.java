package ecnu.db.analyzer.online;

import com.alibaba.druid.util.JdbcConstants;
import ecnu.db.analyzer.online.node.ExecutionNode;
import ecnu.db.analyzer.online.node.ExecutionNode.ExecutionNodeType;
import ecnu.db.analyzer.online.node.RawNode;
import ecnu.db.dbconnector.DatabaseConnectorInterface;
import ecnu.db.schema.Schema;
import ecnu.db.utils.TouchstoneToolChainException;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ecnu.db.utils.CommonUtils.matchPattern;

/**
 * @author qingshuai.wang
 */
public class TidbAnalyzer extends AbstractAnalyzer {
    private static final Pattern ROW_COUNTS = Pattern.compile("rows:[0-9]*");
    private static final Pattern INNER_JOIN_OUTER_KEY = Pattern.compile("outer key:.*,");
    private static final Pattern INNER_JOIN_INNER_KEY = Pattern.compile("inner key:.*");
    private static final Pattern JOIN_EQ_OPERATOR = Pattern.compile("equal:\\[.*]");
    private static final Pattern PLAN_ID = Pattern.compile("([a-zA-Z]+_[0-9]+)");
    private static final Pattern JOIN_EQ_SUB_EXPR = Pattern.compile("eq\\(([a-zA-Z0-9\\_\\$\\.]+)\\, ([a-zA-Z0-9\\_\\$\\.]+)\\)");
    private static final Pattern INNER_JOIN = Pattern.compile("inner join");
    HashMap<String, String> tidbSelectArgs;


    public TidbAnalyzer(String databaseVersion, DatabaseConnectorInterface dbConnector, HashMap<String, String> tidbSelectArgs,
                        HashMap<String, Schema> schemas) {
        super(databaseVersion, dbConnector, schemas);
        this.tidbSelectArgs = tidbSelectArgs;
    }

    @Override
    String[] getSqlInfoColumns(String databaseVersion) throws TouchstoneToolChainException {
        if ("3.1.0".equals(databaseVersion)) {
            return new String[]{"id", "operator info", "execution info"};
        } else if ("4.0.0".equals(databaseVersion)) {
            return new String[]{"id", "operator info", "actRows", "access object"};
        } else {
            throw new TouchstoneToolChainException(String.format("unsupported tidb version %s", databaseVersion));
        }
    }

    @Override
    public String getDbType() {
        return JdbcConstants.MYSQL;
    }

    @Override
    public ExecutionNode getExecutionTree(List<String[]> queryPlan) throws TouchstoneToolChainException {
        RawNode rawNodeRoot = buildRawNodeTree(queryPlan);
        return buildExecutionTree(rawNodeRoot);
    }

    /**
     * TODO 支持 semi join, outer join
     * 合并节点，删除query plan中不需要或者不支持的节点，并根据节点类型提取对应信息
     * 关于join下推到tikv节点的处理:
     * 1. 有selection的下推
     *             IndexJoin                                         Filter
     *            /       \                                          /
     *      leftNode      IndexLookup              ===>>>          Join
     *                     /        \                             /    \
     *             IndexRangeScan   Selection               leftNode  Scan
     *                             /
     *                           Scan
     *
     * 2. 没有selection的下推(leftNode中有Selection节点)
     *             IndexJoin                                         Join
     *            /       \                                         /    \
     *      leftNode      IndexLookup              ===>>>     leftNode   Scan
     *                     /        \
     *             IndexRangeScan   Scan
     *
     * 3. 没有selection的下推(leftNode中没有Selection节点，但右边扫描节点上有索引)
     *             IndexJoin                                         Join
     *            /       \                                         /    \
     *      leftNode      IndexReader              ===>>>     leftNode   Scan
     *                     /
     *             IndexRangeScan
     *
     * @param rawNode 需要处理的query plan树
     * @return 处理好的树
     * @throws TouchstoneToolChainException 构建查询树失败
     */
    private ExecutionNode buildExecutionTree(RawNode rawNode) throws TouchstoneToolChainException {
        argsAndIndex.clear();                                                                       //清空参数和位置的对应关系
        if (rawNode == null) {
            return null;
        }
        Matcher matcher;
        String nodeType = rawNode.nodeType;
        if (nodeTypeRef.isPassNode(nodeType)) {
            return rawNode.left == null ? null : buildExecutionTree(rawNode.left);
        }
        ExecutionNode node;
        // 处理底层的TableScan
        if (nodeTypeRef.isTableScanNode(nodeType)) {
            return new ExecutionNode(rawNode.id, ExecutionNodeType.scan, rawNode.rowCount, rawNode.operatorInfo);
        } else if (nodeTypeRef.isFilterNode(nodeType)) {
            node = new ExecutionNode(rawNode.id, ExecutionNodeType.filter, rawNode.rowCount, rawNode.operatorInfo);
            // 跳过底部的TableScan
            if (rawNode.left != null && nodeTypeRef.isTableScanNode(rawNode.left.nodeType)) {
                return node;
            }
            node.leftNode = rawNode.left == null ? null : buildExecutionTree(rawNode.left);
            node.rightNode = rawNode.right == null ? null : buildExecutionTree(rawNode.right);
        } else if (nodeTypeRef.isJoinNode(nodeType)) {
            if (matchPattern(INNER_JOIN, rawNode.operatorInfo).isEmpty()) {
                throw new TouchstoneToolChainException(String.format("不支持的join类型, operatorInfo{%s}", rawNode.operatorInfo));
            }
            // 处理IndexJoin有selection的下推到tikv情况
            if (nodeTypeRef.isReaderNode(rawNode.right.nodeType)
                    && rawNode.right.right != null
                    && nodeTypeRef.isIndexScanNode(rawNode.right.left.nodeType)
                    && nodeTypeRef.isFilterNode(rawNode.right.right.nodeType)) {
                node = new ExecutionNode(rawNode.right.right.id, ExecutionNodeType.filter, rawNode.rowCount, rawNode.right.right.operatorInfo);
                node.leftNode = new ExecutionNode(rawNode.right.left.id, ExecutionNodeType.join, rawNode.right.left.rowCount, rawNode.operatorInfo);
                String tableName = extractTableName(rawNode.right.right.left.operatorInfo);
                node.leftNode.rightNode = new ExecutionNode(null, ExecutionNodeType.scan, schemas.get(tableName).getTableSize(), "table:" + tableName);
                node.leftNode.leftNode = buildExecutionTree(rawNode.left);
                return node;
            }
            node = new ExecutionNode(rawNode.id, ExecutionNodeType.join, rawNode.rowCount, rawNode.operatorInfo);
            node.leftNode = rawNode.left == null ? null : buildExecutionTree(rawNode.left);
            node.rightNode = rawNode.right == null ? null : buildExecutionTree(rawNode.right);
        } else if (nodeTypeRef.isReaderNode(nodeType)) {
            if (rawNode.right != null) {
                List<List<String>> matches = matchPattern(JOIN_EQ_SUB_EXPR, rawNode.left.operatorInfo);
                // 处理IndexJoin没有selection的下推到tikv情况
                if (!matches.isEmpty() && nodeTypeRef.isTableScanNode(rawNode.right.nodeType)) {
                    String tableName = extractTableName(rawNode.right.operatorInfo);
                    node = new ExecutionNode(rawNode.id, ExecutionNodeType.scan, schemas.get(tableName).getTableSize(), rawNode.right.operatorInfo);
                // 其他情况跳过左侧节点
                } else {
                    node = buildExecutionTree(rawNode.right);
                }
            }
            // 处理IndexReader后接一个IndexScan的情况
            else if (nodeTypeRef.isIndexScanNode(rawNode.left.nodeType)) {
                String tableName = extractTableName(rawNode.left.operatorInfo);
                int tableSize = schemas.get(tableName).getTableSize();
                // 处理IndexJoin没有selection的下推到tikv情况
                if (rawNode.left.rowCount != tableSize) {
                    node = new ExecutionNode(rawNode.left.id, ExecutionNodeType.scan, tableSize, rawNode.left.operatorInfo);
                // 正常情况
                } else{
                    node = new ExecutionNode(rawNode.left.id, ExecutionNodeType.scan, rawNode.left.rowCount, rawNode.left.operatorInfo);
                }
            } else {
                node = buildExecutionTree(rawNode.left);
            }
        } else {
            throw new TouchstoneToolChainException("未支持的查询树Node，类型为" + nodeType);
        }
        return node;
    }

    /**
     * 根据explain analyze的结果生成query plan树
     *
     * @param queryPlan explain analyze的结果
     * @return 生成好的树
     */
    private RawNode buildRawNodeTree(List<String[]> queryPlan) throws TouchstoneToolChainException {
        Deque<Pair<Integer, RawNode>> pStack = new ArrayDeque<>();
        List<List<String>> matches = matchPattern(PLAN_ID, queryPlan.get(0)[0]);
        String nodeType = matches.get(0).get(0).split("_")[0];
        String[] subQueryPlanInfo = extractSubQueryPlanInfo(databaseVersion, queryPlan.get(0));
        String planId = matches.get(0).get(0), operatorInfo = subQueryPlanInfo[1], executionInfo = subQueryPlanInfo[2];
        Matcher matcher;
        int rowCount = (matcher = ROW_COUNTS.matcher(executionInfo)).find() ?
                Integer.parseInt(matcher.group(0).split(":")[1]) : 0;
        RawNode rawNodeRoot = new RawNode(planId, null, null, nodeType, operatorInfo, rowCount), rawNode;
        pStack.push(Pair.of(0, rawNodeRoot));
        for (String[] subQueryPlan : queryPlan.subList(1, queryPlan.size())) {
            subQueryPlanInfo = extractSubQueryPlanInfo(databaseVersion, subQueryPlan);
            matches = matchPattern(PLAN_ID, subQueryPlanInfo[0]);
            planId = matches.get(0).get(0);
            operatorInfo = subQueryPlanInfo[1];
            executionInfo = subQueryPlanInfo[2];
            nodeType = matches.get(0).get(0).split("_")[0];
            rowCount = (matcher = ROW_COUNTS.matcher(executionInfo)).find() ?
                    Integer.parseInt(matcher.group(0).split(":")[1]) : 0;
            rawNode = new RawNode(planId, null, null, nodeType, operatorInfo, rowCount);
            int level = (subQueryPlan[0].split("─")[0].length() + 1) / 2;
            while (!pStack.isEmpty() && pStack.peek().getKey() > level) {
                pStack.pop(); // pop直到找到同一个层级的节点
            }
            if (pStack.isEmpty()) {
                throw new TouchstoneToolChainException("pStack不应为空");
            }
            if (pStack.peek().getKey().equals(level)) {
                pStack.pop();
                if (pStack.isEmpty()) {
                    throw new TouchstoneToolChainException("pStack不应为空");
                }
                pStack.peek().getValue().right = rawNode;
            } else {
                pStack.peek().getValue().left = rawNode;
            }
            pStack.push(Pair.of(level, rawNode));
        }
        return rawNodeRoot;
    }

    private String[] extractSubQueryPlanInfo(String databaseVersion, String[] data) throws TouchstoneToolChainException {
        if ("3.1.0".equals(databaseVersion)) {
            return data;
        } else if ("4.0.0".equals(databaseVersion)) {
            String[] ret = new String[3];
            ret[0] = data[0];
            ret[1] = data[3].isEmpty()? data[1]: String.format("%s,%s", data[3], data[1]);
            ret[2] = "rows:" + data[2];
            return ret;
        } else {
            throw new TouchstoneToolChainException(String.format("unsupported tidb version %s", databaseVersion));
        }
    }

    /**
     * 分析join信息
     * TODO support other valid schema object names listed in https://dev.mysql.com/doc/refman/5.7/en/identifiers.html
     * @param joinInfo join字符串
     * @return 长度为4的字符串数组，0，1为join info左侧的表名和列名，2，3为join右侧的表明和列名
     * @throws TouchstoneToolChainException 无法分析的join条件
     */
    @Override
    public String[] analyzeJoinInfo(String joinInfo) throws TouchstoneToolChainException {
        if (joinInfo.contains("other cond:")) {
            throw new TouchstoneToolChainException("join中包含其他条件,暂不支持");
        }
        String[] result = new String[4];
        String leftTable, leftCol, rightTable, rightCol;
        Matcher eqCondition = JOIN_EQ_OPERATOR.matcher(joinInfo);
        if (eqCondition.find()) {
            if (eqCondition.groupCount() > 1) {
                throw new UnsupportedOperationException();
            }
            List<List<String>> matches = matchPattern(JOIN_EQ_SUB_EXPR, joinInfo);
            String[] leftJoinInfos = matches.get(0).get(1).split("\\."), rightJoinInfos = matches.get(0).get(2).split("\\.");
            leftTable = leftJoinInfos[1];
            rightTable = rightJoinInfos[1];
            List<String> leftCols = new ArrayList<>(), rightCols = new ArrayList<>();
            for (List<String> match: matches) {
                leftJoinInfos = match.get(1).split("\\.");
                rightJoinInfos = match.get(2).split("\\.");
                String currLeftTable = leftJoinInfos[1], currLeftCol = leftJoinInfos[2], currRightTable = rightJoinInfos[1], currRightCol = rightJoinInfos[2];
                if (!leftTable.equals(currLeftTable) || !rightTable.equals(currRightTable)) {
                    throw new TouchstoneToolChainException("join中包含多个表的约束,暂不支持");
                }
                leftCols.add(currLeftCol);
                rightCols.add(currRightCol);
            }
            leftCol = String.join(",", leftCols);
            rightCol = String.join(",", rightCols);
            result[0] = leftTable;
            result[1] = leftCol;
            result[2] = rightTable;
            result[3] = rightCol;
        } else {
            Matcher innerInfo = INNER_JOIN_INNER_KEY.matcher(joinInfo);
            if (innerInfo.find()) {
                String[] innerInfos = innerInfo.group(0).split("\\.");
                result[0] = innerInfos[1];
                result[1] = innerInfos[2];
            } else {
                throw new TouchstoneToolChainException("无法匹配的join格式" + joinInfo);
            }
            Matcher outerInfo = INNER_JOIN_OUTER_KEY.matcher(joinInfo);
            if (outerInfo.find()) {
                String[] outerInfos = outerInfo.group(0).split("\\.");
                result[2] = outerInfos[1];
                result[3] = outerInfos[2].substring(0, outerInfos[2].length() - 1);
            } else {
                throw new TouchstoneToolChainException("无法匹配的join格式" + joinInfo);
            }
        }
        if (result[1].contains(")")) {
            result[1] = result[1].substring(0, result[1].indexOf(')'));
        }
        if (result[3].contains(")")) {
            result[3] = result[3].substring(0, result[3].indexOf(')'));
        }
        return convertToDbTableName(result);
    }

    private String[] convertToDbTableName(String[] result) {
        if (aliasDic != null && aliasDic.containsKey(result[0])) {
            result[0] = aliasDic.get(result[0]);
        }
        if (aliasDic != null && aliasDic.containsKey(result[2])) {
            result[2] = aliasDic.get(result[2]);
        }
        return result;
    }


    /**
     * 分析传入的select 过滤条件，传出表名和格式化后的condition
     * todo 增加对or的支持
     *
     * @param selectCondition 传入的select条件语句
     * @return 表名和格式化后的condition
     */
    @Override
    Pair<String, String> analyzeSelectCondition(String selectCondition) throws TouchstoneToolChainException {
        String tableName = null;
        HashMap<String, String> conditionString = new HashMap<>();
        boolean useAlias = false;
        String[] conditions = selectCondition.split("\\),");
        int inCount = -1;
        for (String condition : conditions) {
            String[] conditionInfos = condition.split("\\(");
            String operator;
            String columnName;
            if ("not".equals(conditionInfos[0].trim())) {
                operator = "not " + convertOperator(conditionInfos[1]);
                String[] operatorInfo = conditionInfos[2].split(",");
                columnName = operatorInfo[0].split("\\.")[2];
                if ("not isnull".equals(operator)) {
                    while (columnName.endsWith(")")) {
                        columnName = columnName.substring(0, columnName.length() - 1);
                    }
                }
                if (tableName == null) {
                    tableName = operatorInfo[0].split("\\.")[1];
                    if (aliasDic != null && aliasDic.containsKey(tableName)) {
                        useAlias = true;
                    }
                } else {
                    if (!tableName.equals(operatorInfo[0].split("\\.")[1])) {
                        throw new TouchstoneToolChainException("select的表名不一致");
                    }
                }

                if ("not in".equals(operator)) {
                    operator += "(" + (operatorInfo.length - 1) + ")";
                    inCount = operatorInfo.length - 1;
                }
            } else {
                operator = convertOperator(conditionInfos[0]);
                String[] operatorInfo = conditionInfos[1].split(",");
                columnName = operatorInfo[0].split("\\.")[2];
                if ("isnull".equals(operator)) {
                    while (columnName.endsWith(")")) {
                        columnName = columnName.substring(0, columnName.length() - 1);
                    }
                }
                if (tableName == null) {
                    tableName = operatorInfo[0].split("\\.")[1];
                    if (aliasDic != null && aliasDic.containsKey(tableName)) {
                        useAlias = true;
                    }
                } else {
                    if (!tableName.equals(operatorInfo[0].split("\\.")[1])) {
                        throw new TouchstoneToolChainException("select的表名不一致");
                    }
                }
                if ("in".equals(operator)) {
                    operator += "(" + (operatorInfo.length - 1) + ")";
                    inCount = operatorInfo.length - 1;
                }
            }
            if (conditionString.containsKey(columnName)) {
                conditionString.put(columnName, "bet");
            } else {
                conditionString.put(columnName, operator);
            }
        }
        if (aliasDic != null && aliasDic.containsKey(tableName)) {
            tableName = aliasDic.get(tableName);
        }
        StringBuilder conditionFinalString = new StringBuilder();
        for (Map.Entry<String, String> stringStringEntry : conditionString.entrySet()) {
            conditionFinalString.append(stringStringEntry.getKey()).append("@").append(stringStringEntry.getValue()).append("#");
            String columnName = stringStringEntry.getKey();
            String operator = stringStringEntry.getValue();
            String selectArgName = columnName + " " + operator;
            if (useAlias) {
                selectArgName = tableName + "." + selectArgName;
            }
            StringBuilder selectArgs = new StringBuilder();

            if (operator.contains("in")) {
                int dateOrNot = schemas.get(tableName).isDate(columnName) ? 1 : 0;
                for (int i = 0; i < inCount; i++) {
                    selectArgs.append("'#").append(sqlArgIndex).append(",").append(i).append(",").append(dateOrNot).append("#', ");
                }
                sqlArgIndex++;
                selectArgs = new StringBuilder("(" + selectArgs.substring(0, selectArgs.length() - 2) + ")");
            } else {
                if ("bet".equals(operator)) {
                    selectArgs.append("ween '#").append(sqlArgIndex).append(",0,").append(schemas.get(tableName)
                            .isDate(columnName) ? 1 : 0).append("#' and '#").append(sqlArgIndex++).append(",1,")
                            .append(schemas.get(tableName).isDate(columnName) ? 1 : 0).append("#'");
                } else {
                    selectArgs.append("#").append(sqlArgIndex++).append(",0,").append(schemas.get(tableName)
                            .isDate(columnName) ? 1 : 0).append("#");
                }
            }
            if (argsAndIndex.containsKey(selectArgName)) {
                argsAndIndex.get(selectArgName).add(selectArgs.toString());
            } else {
                List<String> value = new ArrayList<>();
                value.add(selectArgs.toString());
                argsAndIndex.put(selectArgName, value);
            }
        }
        if (conditionString.size() > 1) {
            conditionFinalString.append("and");
        }


        return new MutablePair<>(tableName, conditionFinalString.toString());
    }

    @Override
    String extractTableName(String operatorInfo) {
        String tableName = operatorInfo.split(",")[0].substring(6).toLowerCase();
        if (aliasDic != null && aliasDic.containsKey(tableName)) {
            tableName = aliasDic.get(tableName);
        }
        return tableName;
    }

    public String convertOperator(String tidbOperator) throws TouchstoneToolChainException {
        tidbOperator = tidbOperator.trim().toUpperCase();
        if (tidbSelectArgs.containsKey(tidbOperator)) {
            return tidbSelectArgs.get(tidbOperator);
        } else {
            throw new TouchstoneToolChainException("没有指定的operator转换：" + tidbOperator);
        }
    }
}
