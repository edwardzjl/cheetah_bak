package cn.edu.zju.cheetah.jdbc.adapter;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.calcite.interpreter.BindableConvention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table mapped onto a Cheetah table.
 */
public class CheetahTable extends AbstractTable implements TranslatableTable {

    public static final String DEFAULT_TIMESTAMP_COLUMN = "__time";
    public static final Interval DEFAULT_INTERVAL =
            new Interval(new DateTime("1900-01-01", ISOChronology.getInstanceUTC()),
                    new DateTime("3000-01-01", ISOChronology.getInstanceUTC()));

    final CheetahSchema schema;
    final String dataSource;
    final RelProtoDataType protoRowType;
    // edwardlol: change access modifiers from package-private to public
    public final ImmutableSet<String> metricFieldNames;
    final ImmutableList<Interval> intervals;
    // edwardlol: change access modifiers from package-private to public
    public final String timestampFieldName;

    /**
     * Creates a Cheetah table.
     *
     * @param schema             Cheetah schema that contains this table
     * @param dataSource         Cheetah data source name
     * @param protoRowType       Field names and types
     * @param metricFieldNames   Names of fields that are metrics
     * @param timestampFieldName Name of the column that contains the time
     * @param intervals          Default interval if query does not constrain the time, or null
     */
    public CheetahTable(CheetahSchema schema, String dataSource,
                        RelProtoDataType protoRowType, Set<String> metricFieldNames,
                        String timestampFieldName, List<Interval> intervals) {
        // edwardlol: change the check null strategy, make use of DEFAULT_TIMESTAMP_COLUMN
        this.timestampFieldName = timestampFieldName == null ? DEFAULT_TIMESTAMP_COLUMN : timestampFieldName;
        this.schema = Preconditions.checkNotNull(schema);
        this.dataSource = Preconditions.checkNotNull(dataSource);
        this.protoRowType = protoRowType;
        this.metricFieldNames = ImmutableSet.copyOf(metricFieldNames);
        this.intervals = intervals != null ? ImmutableList.copyOf(intervals)
                : ImmutableList.of(DEFAULT_INTERVAL);
        for (Interval interval : this.intervals) {
            assert interval.getChronology() == ISOChronology.getInstanceUTC();
        }
    }

    /**
     * Creates a {@link CheetahTable}
     *
     * @param cheetahSchema       Cheetah schema
     * @param dataSourceName      Data source name in Cheetah, also table name
     * @param intervals           Intervals, or null to use default
     * @param fieldMap            Mutable map of fields (dimensions plus metrics);
     *                            may be partially populated already
     * @param metricNameSet       Mutable set of metric names;
     *                            may be partially populated already
     * @param timestampColumnName Name of timestamp column, or null
     * @param connection          If not null, use this connection to find column
     *                            definitions
     * @return A table
     */
    static Table create(CheetahSchema cheetahSchema, String dataSourceName,
                        List<Interval> intervals, Map<String, SqlTypeName> fieldMap,
                        Set<String> metricNameSet, String timestampColumnName,
                        CheetahConnectionImpl connection) {
        if (connection != null) {
            connection.metadata(dataSourceName, timestampColumnName, intervals, fieldMap, metricNameSet);
        }
        final ImmutableMap<String, SqlTypeName> fields =
                ImmutableMap.copyOf(fieldMap);
        return new CheetahTable(cheetahSchema, dataSourceName,
                new MapRelProtoDataType(fields), ImmutableSet.copyOf(metricNameSet),
                timestampColumnName, intervals);
    }

    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        final RelDataType rowType = protoRowType.apply(typeFactory);
        final List<String> fieldNames = rowType.getFieldNames();
        Preconditions.checkArgument(fieldNames.contains(timestampFieldName));
        Preconditions.checkArgument(fieldNames.containsAll(metricFieldNames));
        return rowType;
    }

    public RelNode toRel(RelOptTable.ToRelContext context,
                         RelOptTable relOptTable) {
        final RelOptCluster cluster = context.getCluster();
        final TableScan scan = LogicalTableScan.create(cluster, relOptTable);
        return CheetahQuery.create(cluster,
                cluster.traitSetOf(BindableConvention.INSTANCE), relOptTable, this,
                ImmutableList.of(scan));
    }

    /**
     * Creates a {@link RelDataType} from a map of
     * field names and types.
     */
    private static class MapRelProtoDataType implements RelProtoDataType {
        private final ImmutableMap<String, SqlTypeName> fields;

        MapRelProtoDataType(ImmutableMap<String, SqlTypeName> fields) {
            this.fields = fields;
        }

        public RelDataType apply(RelDataTypeFactory typeFactory) {
            final RelDataTypeFactory.FieldInfoBuilder builder = typeFactory.builder();
            for (Map.Entry<String, SqlTypeName> field : fields.entrySet()) {
                builder.add(field.getKey(), field.getValue()).nullable(true);
            }
            return builder.build();
        }
    }
}

// End CheetahTable.java
