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

package org.apache.hadoop.hive.ql.parse;

import java.util.List;

import org.apache.hadoop.hive.ql.exec.ExprNodeEvaluator;
import org.apache.hadoop.hive.ql.exec.ExprNodeEvaluatorFactory;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.PTFTranslator.LeadLagInfo;
import org.apache.hadoop.hive.ql.plan.ExprNodeColumnDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeConstantDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeFieldDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeNullDesc;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFLeadLag.GenericUDFLag;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFLeadLag.GenericUDFLead;

/*
 * When constructing the Evaluator Tree from an ExprNode Tree
 * - look for any descendant LeadLag Function Expressions
 * - if they are found:
 *   - add them to the LLInfo.leadLagExprs and
 *   - add a mapping from the Expr Tree root to the LLFunc Expr in LLInfo.mapTopExprToLLFunExprs
 */
public class WindowingExprNodeEvaluatorFactory {

  public static ExprNodeEvaluator get(LeadLagInfo llInfo, ExprNodeDesc desc) throws HiveException
  {
    FindLeadLagFuncExprs visitor = new FindLeadLagFuncExprs(llInfo, desc);
    new ExprNodeWalker(visitor).walk(desc);
    return ExprNodeEvaluatorFactory.get(desc);
  }

  public static class FindLeadLagFuncExprs extends ExprNodeVisitor
  {
    ExprNodeDesc topExpr;
    LeadLagInfo llInfo;

    FindLeadLagFuncExprs(LeadLagInfo llInfo, ExprNodeDesc topExpr)
    {
      this.llInfo = llInfo;
      this.topExpr = topExpr;
    }

    @Override
    public void visit(ExprNodeGenericFuncDesc fnExpr) throws HiveException
    {
      GenericUDF fn = fnExpr.getGenericUDF();
      if (fn instanceof GenericUDFLead || fn instanceof GenericUDFLag )
      {
        llInfo.addLLFuncExprForTopExpr(topExpr, fnExpr);
      }
    }
  }

  static class ExprNodeVisitor
  {
    public void visit(ExprNodeColumnDesc e) throws HiveException
    {
    }

    public void visit(ExprNodeConstantDesc e) throws HiveException
    {
    }

    public void visit(ExprNodeFieldDesc e) throws HiveException
    {
    }

    public void visit(ExprNodeGenericFuncDesc e) throws HiveException
    {
    }

    public void visit(ExprNodeNullDesc e) throws HiveException
    {
    }
  }

  static class ExprNodeWalker
  {
    ExprNodeVisitor visitor;

    public ExprNodeWalker(ExprNodeVisitor visitor)
    {
      super();
      this.visitor = visitor;
    }

    public void walk(ExprNodeDesc e) throws HiveException
    {
      if ( e == null ) {
        return;
      }
      List<ExprNodeDesc>  children = e.getChildren();
      if ( children != null )
      {
        for(ExprNodeDesc child : children)
        {
          walk(child);
        }
      }

      if ( e instanceof ExprNodeColumnDesc)
      {
        walk((ExprNodeColumnDesc) e);
      }
      else if ( e instanceof ExprNodeConstantDesc)
      {
        walk((ExprNodeConstantDesc) e);
      }
      else if ( e instanceof ExprNodeFieldDesc)
      {
        walk((ExprNodeFieldDesc) e);
      }
      else if ( e instanceof ExprNodeGenericFuncDesc)
      {
        walk((ExprNodeGenericFuncDesc) e);
      }
      else if ( e instanceof ExprNodeNullDesc)
      {
        walk((ExprNodeNullDesc) e);
      }
      else
      {
        throw new HiveException("Unknown Expr Type " + e.getClass().getName());
      }
    }

    private void walk(ExprNodeColumnDesc e) throws HiveException
    {
      visitor.visit(e);
    }

    private void walk(ExprNodeConstantDesc e) throws HiveException
    {
      visitor.visit(e);
    }

    private void walk(ExprNodeFieldDesc e) throws HiveException
    {
      visitor.visit(e);
    }

    private void walk(ExprNodeGenericFuncDesc e) throws HiveException
    {
      visitor.visit(e);
    }

    private void walk(ExprNodeNullDesc e) throws HiveException
    {
      visitor.visit(e);
    }
  }

}
