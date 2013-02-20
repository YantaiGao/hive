/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.udf.ptf;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hive.ql.exec.PTFOperator;
import org.apache.hadoop.hive.ql.exec.PTFPartition;
import org.apache.hadoop.hive.ql.exec.PTFPartition.PTFPartitionIterator;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.WindowingSpec.BoundarySpec;
import org.apache.hadoop.hive.ql.parse.WindowingSpec.Direction;
import org.apache.hadoop.hive.ql.plan.PTFDesc;
import org.apache.hadoop.hive.ql.plan.PTFDesc.BoundaryDef;
import org.apache.hadoop.hive.ql.plan.PTFDesc.CurrentRowDef;
import org.apache.hadoop.hive.ql.plan.PTFDesc.PTFExpressionDef;
import org.apache.hadoop.hive.ql.plan.PTFDesc.PartitionedTableFunctionDef;
import org.apache.hadoop.hive.ql.plan.PTFDesc.RangeBoundaryDef;
import org.apache.hadoop.hive.ql.plan.PTFDesc.ValueBoundaryDef;
import org.apache.hadoop.hive.ql.plan.PTFDesc.WindowFunctionDef;
import org.apache.hadoop.hive.ql.plan.PTFDesc.WindowTableFunctionDef;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator.AggregationBuffer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;

public class WindowingTableFunction extends TableFunctionEvaluator
{

  @Override
  public PTFPartition execute(PTFPartition iPart)
      throws HiveException
  {
    WindowTableFunctionDef wFnDef = (WindowTableFunctionDef) getTableDef();
    PTFPartitionIterator<Object> pItr = iPart.iterator();
    PTFOperator.connectLeadLagFunctionsToPartition(ptfDesc, pItr);
    PTFPartition outP = new PTFPartition(getPartitionClass(),
        getPartitionMemSize(), wFnDef.getOutputFromWdwFnProcessing().getSerde(), OI);
    execute(pItr, outP);
    return outP;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Override
  public void execute(PTFPartitionIterator<Object> pItr, PTFPartition outP) throws HiveException
  {
    ArrayList<List<?>> oColumns = new ArrayList<List<?>>();
    PTFPartition iPart = pItr.getPartition();
    StructObjectInspector inputOI;
    try {
      inputOI = (StructObjectInspector) iPart.getSerDe().getObjectInspector();
    } catch (SerDeException se) {
      throw new HiveException(se);
    }

    WindowTableFunctionDef wTFnDef = (WindowTableFunctionDef) getTableDef();

    for(WindowFunctionDef wFn : wTFnDef.getWindowFunctions())
    {
      boolean processWindow = wFn.getWindowFrame() != null;
      pItr.reset();
      if ( !processWindow )
      {
        GenericUDAFEvaluator fEval = wFn.getwFnEval();
        Object[] args = new Object[wFn.getArgs() == null ? 0 : wFn.getArgs().size()];
        AggregationBuffer aggBuffer = fEval.getNewAggregationBuffer();
        while(pItr.hasNext())
        {
          Object row = pItr.next();
          int i =0;
          if ( wFn.getArgs() != null ) {
            for(PTFExpressionDef arg : wFn.getArgs())
            {
              args[i++] = arg.getExprEvaluator().evaluate(row);
            }
          }
          fEval.aggregate(aggBuffer, args);
        }
        Object out = fEval.evaluate(aggBuffer);
        if ( !wFn.isPivotResult())
        {
          out = new SameList(iPart.size(), out);
        }
        oColumns.add((List<?>)out);
      }
      else
      {
        oColumns.add(executeFnwithWindow(getQueryDef(), wFn, iPart));
      }
    }

    /*
     * Output Columns in the following order
     * - the columns representing the output from Window Fns
     * - the input Rows columns
     */

    for(int i=0; i < iPart.size(); i++)
    {
      ArrayList oRow = new ArrayList();
      Object iRow = iPart.getAt(i);

      for(int j=0; j < oColumns.size(); j++)
      {
        oRow.add(oColumns.get(j).get(i));
      }

      for(StructField f : inputOI.getAllStructFieldRefs())
      {
        oRow.add(inputOI.getStructFieldData(iRow, f));
      }

      outP.append(oRow);
    }
  }

  public static class WindowingTableFunctionResolver extends TableFunctionResolver
  {
    /*
     * OI of object constructed from output of Wdw Fns; before it is put
     * in the Wdw Processing Partition. Set by Translator/Deserializer.
     */
    private transient StructObjectInspector wdwProcessingOutputOI;

    public StructObjectInspector getWdwProcessingOutputOI() {
      return wdwProcessingOutputOI;
    }

    public void setWdwProcessingOutputOI(StructObjectInspector wdwProcessingOutputOI) {
      this.wdwProcessingOutputOI = wdwProcessingOutputOI;
    }

    @Override
    protected TableFunctionEvaluator createEvaluator(PTFDesc ptfDesc, PartitionedTableFunctionDef tDef)
    {

      return new WindowingTableFunction();
    }

    @Override
    public void setupOutputOI() throws SemanticException {
      setOutputOI(wdwProcessingOutputOI);
    }

    /*
     * Setup the OI based on the:
     * - Input TableDef's columns
     * - the Window Functions.
     */
    @Override
    public void initializeOutputOI() throws HiveException
    {
      setupOutputOI();
    }


    @Override
    public boolean transformsRawInput()
    {
      return false;
    }

    /*
     * (non-Javadoc)
     * @see org.apache.hadoop.hive.ql.udf.ptf.TableFunctionResolver#carryForwardNames()
     * Setting to true is correct only for special internal Functions.
     */
    @Override
    public boolean carryForwardNames() {
      return true;
    }

    /*
     * (non-Javadoc)
     * @see org.apache.hadoop.hive.ql.udf.ptf.TableFunctionResolver#getOutputNames()
     * Set to null only because carryForwardNames is true.
     */
    @Override
    public ArrayList<String> getOutputColumnNames() {
      return null;
    }

  }

  static ArrayList<Object> executeFnwithWindow(PTFDesc ptfDesc, WindowFunctionDef wFnDef, PTFPartition iPart)
    throws HiveException
  {
    ArrayList<Object> vals = new ArrayList<Object>();

    GenericUDAFEvaluator fEval = wFnDef.getwFnEval();
    Object[] args = new Object[wFnDef.getArgs().size()];
    for(int i=0; i < iPart.size(); i++)
    {
      AggregationBuffer aggBuffer = fEval.getNewAggregationBuffer();
      Range rng = getRange(wFnDef, i, iPart);
      PTFPartitionIterator<Object> rItr = rng.iterator();
      PTFOperator.connectLeadLagFunctionsToPartition(ptfDesc, rItr);
      while(rItr.hasNext())
      {
        Object row = rItr.next();
        int j = 0;
        for(PTFExpressionDef arg : wFnDef.getArgs())
        {
          args[j++] = arg.getExprEvaluator().evaluate(row);
        }
        fEval.aggregate(aggBuffer, args);
      }
      Object out = fEval.evaluate(aggBuffer);
      out = ObjectInspectorUtils.copyToStandardObject(out, wFnDef.getOI());
      vals.add(out);
    }
    return vals;
  }

  static Range getRange(WindowFunctionDef wFnDef, int currRow, PTFPartition p) throws HiveException
  {
    BoundaryDef startB = wFnDef.getWindowFrame().getStart();
    BoundaryDef endB = wFnDef.getWindowFrame().getEnd();

    int start = getIndex(startB, currRow, p, false);
    int end = getIndex(endB, currRow, p, true);

    return new Range(start, end, p);
  }

  static int getIndex(BoundaryDef bDef, int currRow, PTFPartition p, boolean end) throws HiveException
  {
    if ( bDef instanceof CurrentRowDef)
    {
      return currRow + (end ? 1 : 0);
    }
    else if ( bDef instanceof RangeBoundaryDef)
    {
      RangeBoundaryDef rbDef = (RangeBoundaryDef) bDef;
      int amt = rbDef.getAmt();

      if ( amt == BoundarySpec.UNBOUNDED_AMOUNT )
      {
        return rbDef.getDirection() == Direction.PRECEDING ? 0 : p.size();
      }

      amt = rbDef.getDirection() == Direction.PRECEDING ?  -amt : amt;
      int idx = currRow + amt;
      idx = idx < 0 ? 0 : (idx > p.size() ? p.size() : idx);
      return idx + (end && idx < p.size() ? 1 : 0);
    }
    else
    {
      ValueBoundaryScanner vbs = ValueBoundaryScanner.getScanner((ValueBoundaryDef)bDef);
      return vbs.computeBoundaryRange(currRow, p);
    }
  }

  static class Range
  {
    int start;
    int end;
    PTFPartition p;

    public Range(int start, int end, PTFPartition p)
    {
      super();
      this.start = start;
      this.end = end;
      this.p = p;
    }

    public PTFPartitionIterator<Object> iterator()
    {
      return p.range(start, end);
    }
  }

  /*
   * - starting from the given rowIdx scan in the given direction until a row's expr
   * evaluates to an amt that crosses the 'amt' threshold specified in the ValueBoundaryDef.
   */
  static abstract class ValueBoundaryScanner
  {
    ValueBoundaryDef bndDef;

    public ValueBoundaryScanner(ValueBoundaryDef bndDef)
    {
      this.bndDef = bndDef;
    }

    /*
     * return the other end of the Boundary
     * - when scanning backwards: go back until you reach a row where the
     * startingValue - rowValue >= amt
     * - when scanning forward:  go forward go back until you reach a row where the
     *  rowValue - startingValue >= amt
     */
    public int computeBoundaryRange(int rowIdx, PTFPartition p) throws HiveException
    {
      int r = rowIdx;
      Object rowValue = computeValue(p.getAt(r));
      int amt = bndDef.getAmt();

      if ( amt == BoundarySpec.UNBOUNDED_AMOUNT )
      {
        return bndDef.getDirection() == Direction.PRECEDING ? 0 : p.size();
      }

      Direction d = bndDef.getDirection();
      boolean scanNext = rowValue != null;
      while ( scanNext )
      {
        if ( d == Direction.PRECEDING ) {
          r = r - 1;
        }
        else {
          r = r + 1;
        }

        if ( r < 0 || r >= p.size() )
        {
          scanNext = false;
          break;
        }

        Object currVal = computeValue(p.getAt(r));
        if ( currVal == null )
        {
          scanNext = false;
          break;
        }

        switch(d)
        {
        case PRECEDING:
          scanNext = !isGreater(rowValue, currVal, amt);
        break;
        case FOLLOWING:
          scanNext = !isGreater(currVal, rowValue, amt);
        case CURRENT:
        default:
          break;
        }
      }
      /*
       * if moving backwards, then r is at a row that failed the range test. So incr r, so that
       * Range starts from a row where the test succeeds.
       * Whereas when moving forward, leave r as is; because the Range's end value should be the
       * row idx not in the Range.
       */
      if ( d == Direction.PRECEDING ) {
        r = r + 1;
      }
      r = r < 0 ? 0 : (r >= p.size() ? p.size() : r);
      return r;
    }

    public Object computeValue(Object row) throws HiveException
    {
      Object o = bndDef.getExprEvaluator().evaluate(row);
      return ObjectInspectorUtils.copyToStandardObject(o, bndDef.getOI());
    }

    public abstract boolean isGreater(Object v1, Object v2, int amt);


    @SuppressWarnings("incomplete-switch")
    public static ValueBoundaryScanner getScanner(ValueBoundaryDef vbDef)
    {
      PrimitiveObjectInspector pOI = (PrimitiveObjectInspector) vbDef.getOI();
      switch(pOI.getPrimitiveCategory())
      {
      case BYTE:
      case INT:
      case LONG:
      case SHORT:
      case TIMESTAMP:
        return new LongValueBoundaryScanner(vbDef);
      case DOUBLE:
      case FLOAT:
        return new DoubleValueBoundaryScanner(vbDef);
      }
      return null;
    }
  }

  public static class LongValueBoundaryScanner extends ValueBoundaryScanner
  {
    public LongValueBoundaryScanner(ValueBoundaryDef bndDef)
    {
      super(bndDef);
    }

    @Override
    public boolean isGreater(Object v1, Object v2, int amt)
    {
      long l1 = PrimitiveObjectInspectorUtils.getLong(v1,
          (PrimitiveObjectInspector) bndDef.getOI());
      long l2 = PrimitiveObjectInspectorUtils.getLong(v2,
          (PrimitiveObjectInspector) bndDef.getOI());
      return (l1 -l2) >= amt;
    }
  }

  public static class DoubleValueBoundaryScanner extends ValueBoundaryScanner
  {
    public DoubleValueBoundaryScanner(ValueBoundaryDef bndDef)
    {
      super(bndDef);
    }

    @Override
    public boolean isGreater(Object v1, Object v2, int amt)
    {
      double d1 = PrimitiveObjectInspectorUtils.getDouble(v1,
          (PrimitiveObjectInspector) bndDef.getOI());
      double d2 = PrimitiveObjectInspectorUtils.getDouble(v2,
          (PrimitiveObjectInspector) bndDef.getOI());
      return (d1 -d2) >= amt;
    }
  }

  public static class SameList<E> extends AbstractList<E>
  {
    int sz;
    E val;

    public SameList(int sz, E val)
    {
      this.sz = sz;
      this.val = val;
    }

    @Override
    public E get(int index)
    {
      return val;
    }

    @Override
    public int size()
    {
      return sz;
    }

  }

}
