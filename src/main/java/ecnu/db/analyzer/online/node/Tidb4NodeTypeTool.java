package ecnu.db.analyzer.online.node;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

class Tidb4NodeTypeTool implements NodeTypeTool {
    private static final HashSet<String> readerNodeTypes = new HashSet<>(Arrays.asList("TableReader", "IndexReader", "IndexLookUp"));
    private static final HashSet<String> passNodeTypes = new HashSet<>(Arrays.asList("Projection", "TopN", "Sort", "HashAgg", "StreamAgg", "IndexRangeScan", "IndexFullScan"));
    private static final HashSet<String> joinNodeTypes = new HashSet<>(Arrays.asList("IndexMergeJoin", "IndexHashJoin", "IndexJoin","HashJoin", "MergeJoin"));
    private static final HashSet<String> filterNodeTypes = new HashSet<>(Collections.singletonList("Selection"));
    private static final HashSet<String> tableScanNodeTypes = new HashSet<>(Arrays.asList("TableFullScan", "TableRowIDScan", "TableRangeScan"));
    private static final HashSet<String> indexScanNodeTypes = new HashSet<>(Arrays.asList("IndexRangeScan", "IndexFullScan"));
    @Override
    public boolean isReaderNode(String nodeType) {
        return readerNodeTypes.contains(nodeType);
    }

    @Override
    public boolean isPassNode(String nodeType) {
        return passNodeTypes.contains(nodeType);
    }

    @Override
    public boolean isJoinNode(String nodeType) {
        return joinNodeTypes.contains(nodeType);
    }

    @Override
    public boolean isFilterNode(String nodeType) {
        return filterNodeTypes.contains(nodeType);
    }

    @Override
    public boolean isTableScanNode(String nodeType) {
        return tableScanNodeTypes.contains(nodeType);
    }

    @Override
    public boolean isIndexScanNode(String nodeType) {
        return indexScanNodeTypes.contains(nodeType);
    }
}