/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.vectorized.build;

import com.alibaba.polardbx.common.charset.CollationName;
import com.alibaba.polardbx.common.datatype.Decimal;
import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.alibaba.polardbx.common.exception.code.ErrorCode;
import com.alibaba.polardbx.common.jdbc.ParameterContext;
import com.alibaba.polardbx.common.jdbc.Parameters;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.common.utils.time.calculator.MySQLIntervalType;
import com.alibaba.polardbx.executor.vectorized.BenchmarkVectorizedExpression;
import com.alibaba.polardbx.executor.vectorized.BuiltInFunctionVectorizedExpression;
import com.alibaba.polardbx.executor.vectorized.CaseVectorizedExpression;
import com.alibaba.polardbx.executor.vectorized.CoalesceVectorizedExpression;
import com.alibaba.polardbx.executor.vectorized.InputRefVectorizedExpression;
import com.alibaba.polardbx.executor.vectorized.LiteralVectorizedExpression;
import com.alibaba.polardbx.executor.vectorized.VectorizedExpression;
import com.alibaba.polardbx.executor.vectorized.VectorizedExpressionRegistry;
import com.alibaba.polardbx.executor.vectorized.metadata.ArgumentInfo;
import com.alibaba.polardbx.executor.vectorized.metadata.ExpressionConstructor;
import com.alibaba.polardbx.executor.vectorized.metadata.ExpressionMode;
import com.alibaba.polardbx.executor.vectorized.metadata.ExpressionSignature;
import com.alibaba.polardbx.executor.vectorized.metadata.Rex2ArgumentInfo;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.TddlOperatorTable;
import com.alibaba.polardbx.optimizer.core.TddlRelDataTypeSystemImpl;
import com.alibaba.polardbx.optimizer.core.TddlTypeFactoryImpl;
import com.alibaba.polardbx.optimizer.core.datatype.DataType;
import com.alibaba.polardbx.optimizer.core.datatype.DataTypeUtil;
import com.alibaba.polardbx.optimizer.core.datatype.DataTypes;
import com.alibaba.polardbx.optimizer.core.datatype.DecimalType;
import com.alibaba.polardbx.optimizer.core.expression.ExtraFunctionManager;
import com.alibaba.polardbx.optimizer.core.expression.build.Rex2ExprUtil;
import com.alibaba.polardbx.optimizer.core.function.calc.AbstractCollationScalarFunction;
import com.alibaba.polardbx.optimizer.core.function.calc.AbstractScalarFunction;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexSystemVar;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.IntervalSqlType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.IntervalString;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.calcite.sql.type.SqlTypeName.BIGINT;
import static org.apache.calcite.sql.type.SqlTypeName.BIGINT_UNSIGNED;
import static org.apache.calcite.sql.type.SqlTypeName.BINARY;
import static org.apache.calcite.sql.type.SqlTypeName.CHAR;
import static org.apache.calcite.sql.type.SqlTypeName.DECIMAL;
import static org.apache.calcite.sql.type.SqlTypeName.DOUBLE;
import static org.apache.calcite.sql.type.SqlTypeName.SIGNED;
import static org.apache.calcite.sql.type.SqlTypeName.UNSIGNED;

/**
 * Visit the rational expression tree and binding to vectorized expression node-by-node.
 * Don't support sub query util now.
 */
public class Rex2VectorizedExpressionVisitor extends RexVisitorImpl<VectorizedExpression> {
    private final static RelDataTypeFactory TYPE_FACTORY =
        new TddlTypeFactoryImpl(TddlRelDataTypeSystemImpl.getInstance());
    private final static RexBuilder REX_BUILDER = new RexBuilder(TYPE_FACTORY);

    /**
     * Special vectorized expressions.
     */
    public static final Map<SqlOperator, Class<? extends VectorizedExpression>> SPECIAL_VECTORIZED_EXPRESSION_MAPPING =
        ImmutableMap
            .<SqlOperator, Class<? extends VectorizedExpression>>builder()
            .put(TddlOperatorTable.CASE, CaseVectorizedExpression.class)
            .put(TddlOperatorTable.COALESCE, CoalesceVectorizedExpression.class)
            .put(TddlOperatorTable.BENCHMARK, BenchmarkVectorizedExpression.class)
            .build();

    private static final String CAST_TO_DECIMAL = "CastToDecimal";
    private static final String CAST_TO_UNSIGNED = "CastToUnsigned";
    private static final String CAST_TO_SIGNED = "CastToSigned";
    public static final String CAST_TO_DOUBLE = "CastToDouble";
    /**
     * The vectorized function names of the Cast function
     */
    public static final Map<SqlTypeName, String> VECTORIZED_CAST_FUNCTION_NAMES =
        ImmutableMap
            .<SqlTypeName, String>builder()
            .put(DECIMAL, CAST_TO_DECIMAL)
            .put(BIGINT_UNSIGNED, CAST_TO_UNSIGNED)
            .put(BIGINT, CAST_TO_SIGNED)
            .put(SIGNED, CAST_TO_SIGNED)
            .put(UNSIGNED, CAST_TO_UNSIGNED)
            .put(DOUBLE, CAST_TO_DOUBLE)
            .build();

    /**
     * Denote filter mode calls. Must be identified by reference.
     */
    private final Map<RexCall, RexCall> callsInFilterMode = new IdentityHashMap<>();
    private final ExecutionContext executionContext;
    private final List<DataType<?>> outputDataTypes = new ArrayList<>(64);
    private int currentOutputIndex;

    public Rex2VectorizedExpressionVisitor(ExecutionContext executionContext, int startIndex) {
        super(false);
        this.executionContext = executionContext;
        this.currentOutputIndex = startIndex;
    }

    private RexCall rewrite(RexCall call) {
        Preconditions.checkNotNull(call);
        if (TddlOperatorTable.CONTROL_FLOW_VECTORIZED_OPERATORS.contains(call.op)) {
            return rewriteControlFlowFunction(call);
        } else if (call.op == TddlOperatorTable.TRIM) {
            ImmutableList<RexNode> operands = call.operands;
            List<RexNode> nodes = Arrays.asList(operands.get(2), operands.get(1), operands.get(0));
            return call.clone(call.type, nodes);
        } else if (call.op == TddlOperatorTable.DATE_ADD
            || call.op == TddlOperatorTable.ADDDATE
            || call.op == TddlOperatorTable.DATE_SUB
            || call.op == TddlOperatorTable.SUBDATE) {

            RexNode interval = call.getOperands().get(1);
            if (interval instanceof RexCall && ((RexCall) interval).op == TddlOperatorTable.INTERVAL_PRIMARY) {
                // flat the operands tree:
                //
                //       DATE_ADD                                 DATE_ADD
                //       /      \                               /    |     \
                //   TIME   INTERVAL_PRIMARY        =>      TIME    VALUE   TIME_UNIT
                //                 /    \
                //           VALUE        TIME_UNIT
                RexNode literal = ((RexCall) interval).getOperands().get(1);
                if (literal instanceof RexLiteral && ((RexLiteral) literal).getValue() != null) {
                    String unit = ((RexLiteral) literal).getValue().toString();
                    RexCall newCall = (RexCall) REX_BUILDER.makeCall(
                        call.getType(),
                        call.op,
                        ImmutableList.of(
                            call.getOperands().get(0),
                            ((RexCall) interval).getOperands().get(0),
                            REX_BUILDER.makeLiteral(unit)
                        ));
                    return newCall;
                }

            } else if (call.getOperands().size() == 2) {
                // flat the operands tree:
                //
                //       DATE_ADD                                   DATE_ADD
                //       /      \                               /     |         \
                //   TIME    DAY VALUE        =>           TIME    DAY VALUE   INTERVAL_DAY
                //
                RexCall newCall = (RexCall) REX_BUILDER.makeCall(
                    call.getType(),
                    call.op,
                    ImmutableList.of(
                        call.getOperands().get(0),
                        call.getOperands().get(1),
                        REX_BUILDER.makeLiteral(MySQLIntervalType.INTERVAL_DAY.name())
                    ));
                return newCall;
            }

        }
        return call;
    }

    private RexCall rewriteControlFlowFunction(RexCall rexCall) {
        final List<RexNode> exprList = rexCall.getOperands();
        final RelDataType returnType = rexCall.type;
        final RelDataType condType = TYPE_FACTORY.createSqlType(SqlTypeName.BIGINT);
        if (rexCall.op == SqlStdOperatorTable.CASE) {
            List<RexNode> operands = IntStream.range(0, exprList.size())
                .mapToObj((i) -> {
                    RexNode node = exprList.get(i);
                    if (i % 2 == 0 && i != exprList.size() - 1) {
                        // Other expressions need to be rewrite to IS TRUE expression to be compatible.
                        while (!canBindingToCommonFilterExpression(node)) {
                            node = REX_BUILDER.makeCall(TddlOperatorTable.IS_TRUE, node);
                        }
                    }
                    return node;
                }).collect(Collectors.toList());

            return rexCall.clone(rexCall.type, operands);
        } else if (rexCall.op == TddlOperatorTable.IFNULL) {
            // all IFNULL function has already been rewrite to COALESCE function.
            // IFNULL(expr1, expr2) => COALESCE(expr1, expr2)
            RexCall coalesceOperator =
                (RexCall) REX_BUILDER.makeCall(returnType, TddlOperatorTable.COALESCE, ImmutableList.copyOf(exprList));
            return coalesceOperator;
        } else if (rexCall.op == TddlOperatorTable.IF) {
            // IF(cond, thenExpr, elseExpr) => case when cond then thenExpr else elseExpr
            List<RexNode> exprs = ImmutableList.<RexNode>builder().addAll(rexCall.operands).build();
            RexCall caseOp = (RexCall) REX_BUILDER.makeCall(returnType, TddlOperatorTable.CASE, exprs);
            return rewriteControlFlowFunction(caseOp);
        }
        return rexCall;
    }

    /**
     * rewrite CASE operator to IF operator.
     * CASE WHEN cond1 THEN expr1
     * WHEN cond2 THEN expr2
     * ...
     * ELSE exprN
     * END
     * =>
     * IF = IF1
     * IF1 = (cond1, expr1, IF2)
     * IFI = (condI, exprI, IFI+1)
     * I < N
     */
    private RexNode makeCaseOperator(final RelDataType type, final List<RexNode> exprList, int startIndex) {
        RexNode elseNode = startIndex + 2 == exprList.size() - 1 ?
            exprList.get(startIndex + 2) :
            makeCaseOperator(type, exprList, startIndex + 2);

        List<RexNode> newExprList = ImmutableList.of(
            exprList.get(startIndex),
            exprList.get(startIndex + 1),
            elseNode
        );
        RexNode rexNode = REX_BUILDER.makeCall(type, TddlOperatorTable.IF, newExprList);
        return rexNode;
    }

    private void registerFilterModeChildren(RexCall call) {
        Preconditions.checkNotNull(call);
        final RexCall parent = call;
        // register the children that should be in filter mode.
        if (call.op == TddlOperatorTable.CASE) {
            // for case operator, we should set all when condition expressions to filter mode.
            final int operandSize = call.getOperands().size();
            IntStream.range(0, operandSize)
                .filter(i -> i % 2 == 0 && i != operandSize - 1)
                .mapToObj(i -> call.getOperands().get(i))
                .filter(child -> canBindingToCommonFilterExpression(child))
                .forEach(child -> callsInFilterMode.put((RexCall) child, parent));
        }
    }

    private boolean canBindingToCommonFilterExpression(RexNode node) {
        Preconditions.checkNotNull(node);
        if (!(node instanceof RexCall)) {
            return false;
        }
        RexCall call = (RexCall) node;
        if (TddlOperatorTable.VECTORIZED_COMPARISON_OPERATORS.contains(call.op)) {
            // if the call belongs to comparison operators, all it's operands must be int type or approx type.
            boolean allOperandTypesMatch = call.getOperands().stream()
                .map(e -> e.getType())
                .allMatch(t -> (SqlTypeUtil.isIntType(t) && !SqlTypeUtil.isUnsigned(t)) || SqlTypeUtil
                    .isApproximateNumeric(t));
            // now, we don't support constant folding.
            boolean anyOperandRexNodeMatch = call.getOperands().stream()
                .anyMatch(e -> e instanceof RexCall || e instanceof RexInputRef);
            return allOperandTypesMatch && anyOperandRexNodeMatch;
        }
        return false;
    }

    private boolean isInFilterMode(RexCall call) {
        return callsInFilterMode.containsKey(call);
    }

    private static boolean isSpecialFunction(RexCall call) {
        SqlKind sqlKind = call.getKind();
        return sqlKind == SqlKind.MINUS_PREFIX
            || sqlKind == SqlKind.PLUS_PREFIX
            || call.getOperator() == SqlStdOperatorTable.TRIM
            || call.getOperands() == SqlStdOperatorTable.IS_NOT_DISTINCT_FROM;
    }

    /**
     * Generate the proper function name that vectorized registry can recognize.
     */
    private static String normalizeFunctionName(RexCall call) {
        if (call.op == TddlOperatorTable.CAST
            || call.op == TddlOperatorTable.CONVERT) {
            SqlTypeName castToType = call.getType().getSqlTypeName();
            String castFunctionName = VECTORIZED_CAST_FUNCTION_NAMES.get(castToType);
            if (castFunctionName != null) {
                return castFunctionName;
            }
        } else if (call.op == TddlOperatorTable.IMPLICIT_CAST) {
            // for implicit cast, we only use cast to double util now.
            SqlTypeName castToType = call.getType().getSqlTypeName();
            if (castToType == DOUBLE) {
                return CAST_TO_DOUBLE;
            }
        }
        return call.op.getName().toUpperCase();
    }

    @Override
    public VectorizedExpression visitLiteral(RexLiteral literal) {
        return LiteralVectorizedExpression
            .from(literal, addOutput(DataTypeUtil.calciteToDrdsType(literal.getType())));
    }

    @Override
    public VectorizedExpression visitInputRef(RexInputRef inputRef) {
        return new InputRefVectorizedExpression(DataTypeUtil.calciteToDrdsType(inputRef.getType()),
            inputRef.getIndex(), inputRef.getIndex());
    }

    @Override
    public VectorizedExpression visitCall(RexCall call) {
        if (!isSpecialFunction(call)) {
            Optional<VectorizedExpression> expression = createVectorizedExpression(call);
            if (expression.isPresent()) {
                return expression.get();
            }
        }

        // Fallback method
        return createGeneralVectorizedExpression(call);
    }

    @Override
    public VectorizedExpression visitFieldAccess(RexFieldAccess fieldAccess) {
        throw new IllegalArgumentException("Correlated variable not supported in cectorized expression!");
    }

    @Override
    public VectorizedExpression visitDynamicParam(RexDynamicParam dynamicParam) {
        if (dynamicParam.getIndex() == -3 || dynamicParam.getIndex() == -2) {
            throw new IllegalStateException("Subquery not supported yet!");
        }

        Object value = Optional.ofNullable(executionContext.getParams())
            .map(Parameters::getCurrentParameter)
            .flatMap(m -> Optional.ofNullable(m.get(dynamicParam.getIndex() + 1)))
            .map(ParameterContext::getValue)
            .map(v -> {
                if (v instanceof BigDecimal) {
                    return Decimal.fromBigDecimal((BigDecimal) v);
                } else {
                    return v;
                }
            }).orElse(null);

        DataType<?> dataType = DataTypeUtil.calciteToDrdsType(dynamicParam.getType());
        return new LiteralVectorizedExpression(dataType, value, addOutput(dataType));
    }

    private VectorizedExpression createGeneralVectorizedExpression(RexCall call) {
        call = rewrite(call);

        String functionName = call.getOperator().getName();
        if (call.getKind().equals(SqlKind.MINUS_PREFIX)) {
            functionName = "UNARY_MINUS";
        }
        if (call.getKind().equals(SqlKind.PLUS_PREFIX)) {
            functionName = "UNARY_PLUS";
        }

        List<RexNode> operands = call.getOperands();
        List<VectorizedExpression> args = new ArrayList<>(operands.size());
        for (RexNode rexNode : operands) {
            args.add(rexNode.accept(this));
        }
        args.addAll(visitExtraParams(call));

        DataType resultType = DataTypeUtil.calciteToDrdsType(call.getType());
        List<DataType> operandTypes = operands.stream()
            .map(RexNode::getType)
            .map(type -> DataTypeUtil.calciteToDrdsType(type))
            .collect(Collectors.toList());
        AbstractScalarFunction scalarFunction = ExtraFunctionManager.getExtraFunction(functionName,
            operandTypes, resultType);
        if (scalarFunction instanceof AbstractCollationScalarFunction) {
            CollationName collation = Rex2ExprUtil.fixCollation(call, executionContext);
            ((AbstractCollationScalarFunction) scalarFunction).setCollation(collation);
        }

        return BuiltInFunctionVectorizedExpression
            .from(args.toArray(new VectorizedExpression[0]), addOutput(scalarFunction.getReturnType()),
                scalarFunction, executionContext);
    }

    private Optional<VectorizedExpression> createVectorizedExpression(RexCall call) {
        Preconditions.checkNotNull(call);
        Optional<ExpressionConstructor<?>> constructor;
        // rewrite the RexNode Tree.
        call = rewrite(call);

        if (SPECIAL_VECTORIZED_EXPRESSION_MAPPING.containsKey(call.op)) {
            // special class binding.
            constructor = Optional.of(
                ExpressionConstructor.of(
                    SPECIAL_VECTORIZED_EXPRESSION_MAPPING.get(call.getOperator())
                )
            );
        } else {
            // common code-generated class binding.
            constructor = createExpressionFromRegistry(call);
        }

        // register child expression to filter mode map.
        registerFilterModeChildren(call);

        if (constructor.isPresent()) {
            // Determine whether to update the date type list and output index, according to the expression mode.
            int outputIndex = -1;
            boolean isInFilterMode = isInFilterMode(call);
            DataType<?> dataType = getOutputDataType(call);
            if (!isInFilterMode) {
                outputIndex = addOutput(dataType);
            }

            VectorizedExpression[] children =
                call.getOperands().stream().map(node -> node.accept(this)).toArray(VectorizedExpression[]::new);

            try {
                VectorizedExpression vecExpr = constructor.get().build(dataType, outputIndex, children);

                if (!isInFilterMode && !DataTypeUtil.equalsSemantically(vecExpr.getOutputDataType(), dataType)) {
                    throw new IllegalStateException(String
                        .format("Vectorized expression %s output type %s not equals to rex call type %s!",
                            vecExpr.getClass().getSimpleName(), vecExpr.getOutputDataType(), dataType));
                }
                return Optional.of(vecExpr);
            } catch (Exception e) {
                throw GeneralUtil.nestedException("Failed to create vectorized expression", e);
            }
        } else {
            return Optional.empty();
        }
    }

    private DataType getOutputDataType(RexCall call) {
        RelDataType relDataType = call.getType();
        if ((call.op == TddlOperatorTable.CONVERT || call.op == TddlOperatorTable.CAST)
            && SqlTypeUtil.isDecimal(relDataType)) {
            // For decimal type of cast operator, we should use precious type info.
            int precision = relDataType.getPrecision();
            int scale = relDataType.getScale();
            return new DecimalType(precision, scale);
        }
        return DataTypeUtil.calciteToDrdsType(relDataType);
    }

    private Optional<ExpressionConstructor<?>> createExpressionFromRegistry(RexCall call) {
        String functionName = normalizeFunctionName(call);
        List<ArgumentInfo> argumentInfos = new ArrayList<>(call.getOperands().size());

        for (RexNode arg : call.getOperands()) {
            ArgumentInfo info = Rex2ArgumentInfo.toArgumentInfo(arg);
            if (info != null) {
                argumentInfos.add(info);
            } else {
                // Unable to convert operand to argument, so skip conversion.
                return Optional.empty();
            }
        }
        ExpressionMode mode = callsInFilterMode.containsKey(call) ? ExpressionMode.FILTER : ExpressionMode.PROJECT;
        ExpressionSignature signature =
            new ExpressionSignature(functionName, argumentInfos.toArray(new ArgumentInfo[0]), mode);

        return VectorizedExpressionRegistry.builderConstructorOf(signature);
    }

    private List<VectorizedExpression> visitExtraParams(RexCall call) {
        String functionName = call.getOperator().getName();
        if ("CAST".equalsIgnoreCase(functionName)) {
            RelDataType relDataType = call.getType();
            DataType<?> dataType = DataTypeUtil.calciteToDrdsType(relDataType);
            if ((relDataType.getSqlTypeName() == CHAR || relDataType.getSqlTypeName() == BINARY)
                && relDataType.getPrecision() >= 0) {
                return ImmutableList
                    .of(new LiteralVectorizedExpression(DataTypes.StringType, dataType.getStringSqlType(),
                            addOutput(DataTypes.StringType)),
                        new LiteralVectorizedExpression(DataTypes.IntegerType, relDataType.getPrecision(),
                            addOutput(DataTypes.IntegerType)));
            }

            if (relDataType.getSqlTypeName() == DECIMAL && relDataType.getPrecision() > 0) {
                return ImmutableList
                    .of(new LiteralVectorizedExpression(DataTypes.StringType, dataType.getStringSqlType(),
                            addOutput(DataTypes.StringType)),
                        new LiteralVectorizedExpression(DataTypes.IntegerType, relDataType.getPrecision(),
                            addOutput(DataTypes.IntegerType)),
                        new LiteralVectorizedExpression(DataTypes.IntegerType,
                            relDataType.getScale() == -1 ? 0 : relDataType.getScale(),
                            addOutput(DataTypes.IntegerType)));
            }

            // For fractional time type, preserve scale value.
            if (DataTypeUtil.isFractionalTimeType(dataType)) {
                return ImmutableList
                    .of(new LiteralVectorizedExpression(DataTypes.StringType, dataType.getStringSqlType(),
                            addOutput(DataTypes.StringType)),
                        new LiteralVectorizedExpression(DataTypes.IntegerType,
                            relDataType.getScale() == -1 ? 0 : relDataType.getScale(),
                            addOutput(DataTypes.IntegerType)));
            }

            return ImmutableList.of(new LiteralVectorizedExpression(DataTypes.StringType, dataType.getStringSqlType(),
                addOutput(DataTypes.StringType)));
        }

        return Collections.emptyList();
    }

    private VectorizedExpression buildIntervalFunction(RexLiteral literal) {
        Preconditions.checkArgument(literal.getType() instanceof IntervalSqlType, "Input type should be interval!");
        VectorizedExpression[] args = new VectorizedExpression[2];
        String unit = literal.getType().getIntervalQualifier().getUnit().name();
        String value = ((IntervalString) literal.getValue()).getIntervalStr();

        args[0] = new LiteralVectorizedExpression(DataTypes.StringType, unit, addOutput(DataTypes.StringType));
        args[1] = new LiteralVectorizedExpression(DataTypes.StringType, value, addOutput(DataTypes.StringType));

        AbstractScalarFunction scalarFunction = ExtraFunctionManager.getExtraFunction("INTERVAL_PRIMARY", null, null);
        return BuiltInFunctionVectorizedExpression
            .from(args, addOutput(scalarFunction.getReturnType()), scalarFunction, executionContext);
    }

    @Override
    public VectorizedExpression visitSystemVar(RexSystemVar systemVar) {
        throw new TddlRuntimeException(ErrorCode.ERR_NOT_SUPPORT, "System variables (not pushed down)");
    }

    /**
     * Add output datatype and update index.
     *
     * @param outputDataType Datatype of this output
     * @return The output index of this output.
     */
    private int addOutput(DataType<?> outputDataType) {
        this.outputDataTypes.add(outputDataType);
        int ret = this.currentOutputIndex;
        this.currentOutputIndex += 1;
        return ret;
    }

    public List<DataType<?>> getOutputDataTypes() {
        return Collections.unmodifiableList(outputDataTypes);
    }
}