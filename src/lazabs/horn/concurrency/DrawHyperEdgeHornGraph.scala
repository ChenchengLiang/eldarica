/**
 * Copyright (c) 2011-2020 Philipp Ruemmer, Chencheng Liang.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * * Neither the name of the authors nor the names of their
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package lazabs.horn.concurrency

import java.io.{File, PrintWriter}
import ap.parser.IExpression.{ConstantTerm, Eq}
import ap.parser.{IBinJunctor, IConstant, IExpression, IFormula, ITerm, LineariseVisitor, Simplifier, SymbolCollector}
import lazabs.horn.bottomup.HornClauses.Clause
import lazabs.horn.preprocessor.HornPreprocessor.{Clauses, VerificationHints}
import scala.collection.mutable.ListBuffer
import lazabs.horn.concurrency.DrawHyperEdgeHornGraph.{HyperEdgeType}

object DrawHyperEdgeHornGraph {

  object HyperEdgeType extends Enumeration {
    val controlFlow, dataFlow = Value
  }

}

class hyperEdgeInfo(name: String, from: String = "", to: String, nodeType: HyperEdgeType.Value) {
  val hyperEdgeNodeName = name
  val fromName = from
  var guardName = Set[String]()
  val toName = to
  val hyperEdgeType = nodeType
}

class DrawHyperEdgeHornGraph(file: String, simpClauses: Clauses, hints: VerificationHints, argumentInfoList: ListBuffer[argumentInfo]) extends DrawHornGraph(file: String, simpClauses: Clauses, hints: VerificationHints, argumentInfoList: ListBuffer[argumentInfo]) {

  println("Write hyperedge horn graph to file")
  edgeNameMap += ("controlFlowHyperEdge" -> "CFHE")
  edgeNameMap += ("dataFlowHyperEdge" -> "DFHE")
  edgeNameMap += ("dataFlowAST" -> "data")
  edgeNameMap += ("guardAST" -> "guard")
  edgeNameMap += ("argument" -> "arg")
  //turn on/off edge's label
  var edgeNameSwitch = true
  if (edgeNameSwitch == false) {
    for (key <- edgeNameMap.keys)
      edgeNameMap += (key -> "")
  }
  edgeDirectionMap += ("controlFlowHyperEdge" -> false)
  edgeDirectionMap += ("dataFlowHyperEdge" -> false)
  edgeDirectionMap += ("dataFlowAST" -> false)
  edgeDirectionMap += ("guardAST" -> false)
  edgeDirectionMap += ("argument" -> false)

  //node cotegory: Operator and Constant don't need canonical name. FALSE is unique category
  val controlNodePrefix = "CONTROLN_"
  val symbolicConstantNodePrefix = "SYMBOLIC_CONSTANT_"
  val argumentNodePrefix = "predicateArgument"
  val controlFlowHyperEdgeNodePrefix = "CFHE_"
  val dataFlowHyperEdgeNodePrefix = "DFHE_"
  //node shape map
  nodeShapeMap += ("CONTROL" -> "component")
  nodeShapeMap += ("operator" -> "square")
  nodeShapeMap += ("symbolicConstant" -> "circle")
  nodeShapeMap += ("constant" -> "circle")
  nodeShapeMap += ("predicateArgument" -> "ellipse")
  nodeShapeMap += ("FALSE" -> "component")
  nodeShapeMap += ("controlFlowHyperEdge" -> "diamond")
  nodeShapeMap += ("dataFlowHyperEdge" -> "diamond")

  val sp = new Simplifier()
  val dataFlowInfoWriter = new PrintWriter(new File(file + ".HornGraph"))
  var tempID = 0
  var clauseNumber = 0
  var hyperEdgeList = scala.collection.mutable.ListBuffer[hyperEdgeInfo]()


  for (clause <- simpClauses) {
    //var hyperEdgeList = List[hyperEdgeInfo]()
    hyperEdgeList.clear()
    constantNodeSetInOneClause.clear()
    val normalizedClause = clause.normalize()
    val (dataFlowSet, guardSet) = getDataFlowAndGuard(clause, normalizedClause, dataFlowInfoWriter)

    //draw head predicate node and argument node
    if (normalizedClause.head.pred.name == "FALSE") {
      //draw predicate node
      drawPredicateNode("FALSE", "FALSE", "FALSE")
    } else {
      if (!controlFlowNodeSetInOneClause.keySet.contains(normalizedClause.head.pred.name)) {
        //draw predicate node
        val controlFlowNodeName = controlNodePrefix + gnn_input.CONTROLCanonicalID.toString
        drawPredicateNode(controlFlowNodeName, normalizedClause.head.pred.name, "CONTROL")
        //draw argument node
        var argumentNodeArray = Array[String]()
        tempID = 0
        for (arg <- normalizedClause.head.args) {
          val argumentnodeName = argumentNodePrefix + gnn_input.predicateArgumentCanonicalID.toString
          createNode(argumentnodeName, "ARG_" + tempID.toString, "predicateArgument", nodeShapeMap("predicateArgument"))
          constantNodeSetInOneClause(arg.toString) = argumentnodeName
          argumentNodeArray :+= argumentnodeName
          gnn_input.argumentInfoHornGraphList += new argumentInfoHronGraph(normalizedClause.head.pred.name, tempID,gnn_input.GNNNodeID-1)
          tempID += 1
          //connect to control flow node
          addBinaryEdge(argumentnodeName, controlFlowNodeName, "argument")
          drawDataFlow(arg, dataFlowSet)
        }
        argumentNodeSetInOneClause(normalizedClause.head.pred.name) = argumentNodeArray
      } else {
        for (controlNodeName <- argumentNodeSetInOneClause.keySet) if (controlNodeName == normalizedClause.head.pred.name) {
          for ((argNodeName, arg) <- argumentNodeSetInOneClause(controlNodeName) zip normalizedClause.head.args) {
            constantNodeSetInOneClause(arg.toString) = argNodeName
            drawDataFlow(arg, dataFlowSet)
          }
        }
      }
    }


    //draw body predicate node and argument node
    if (normalizedClause.body.isEmpty) {
      //draw predicate node
      val initialControlFlowNodeName = controlNodePrefix + gnn_input.CONTROLCanonicalID.toString
      drawPredicateNode(initialControlFlowNodeName, "Initial", "CONTROL")
      //draw control flow hyperedge node between body and head
      val controlFlowHyperedgeName = controlFlowHyperEdgeNodePrefix + gnn_input.controlFlowHyperEdgeCanonicalID.toString
      createHyperEdgeNode(controlFlowHyperedgeName, "guarded CFHE Clause " + clauseNumber.toString, "controlFlowHyperEdge", nodeShapeMap("controlFlowHyperEdge"))
      //store control flow hyperedge connection between body and head
      hyperEdgeList :+= new hyperEdgeInfo(controlFlowHyperedgeName, initialControlFlowNodeName, controlFlowNodeSetInOneClause(normalizedClause.head.pred.name), HyperEdgeType.controlFlow)

    } else {
      for (body <- normalizedClause.body) {
        if (!controlFlowNodeSetInOneClause.keySet.contains(body.pred.name)) {
          //draw predicate node
          val controlFlowNodeName = controlNodePrefix + gnn_input.CONTROLCanonicalID.toString
          drawPredicateNode(controlFlowNodeName, body.pred.name, "CONTROL")
          //draw control flow hyperedge node between body and head
          val controlFlowHyperedgeName = controlFlowHyperEdgeNodePrefix + gnn_input.controlFlowHyperEdgeCanonicalID.toString
          createHyperEdgeNode(controlFlowHyperedgeName, "guarded CFHE Clause " + clauseNumber.toString, "controlFlowHyperEdge", nodeShapeMap("controlFlowHyperEdge"))
          //store control flow hyperedge connection between body and head
          hyperEdgeList :+= new hyperEdgeInfo(controlFlowHyperedgeName, controlFlowNodeName, controlFlowNodeSetInOneClause(normalizedClause.head.pred.name), HyperEdgeType.controlFlow)
          //draw argument node
          var argumentNodeArray = Array[String]()
          tempID = 0
          for (arg <- body.args) {
            val argumentnodeName = argumentNodePrefix + gnn_input.predicateArgumentCanonicalID.toString
            createNode(argumentnodeName, "ARG_" + tempID.toString, "predicateArgument", nodeShapeMap("predicateArgument"))
            constantNodeSetInOneClause(arg.toString) = argumentnodeName
            argumentNodeArray :+= argumentnodeName
            gnn_input.argumentInfoHornGraphList += new argumentInfoHronGraph(body.pred.name, tempID,gnn_input.GNNNodeID-1)
            tempID += 1
            //connect to control flow node
            addBinaryEdge(argumentnodeName, controlFlowNodeName, "argument")
          }
          argumentNodeSetInOneClause(body.pred.name) = argumentNodeArray
        } else {
          for (controlNodeName <- argumentNodeSetInOneClause.keySet) if (controlNodeName == body.pred.name) {
            for ((argNodeName, arg) <- argumentNodeSetInOneClause(controlNodeName) zip body.args) {
              constantNodeSetInOneClause(arg.toString) = argNodeName
            }
          }
          //draw control flow hyperedge node between body and head
          val controlFlowHyperedgeName = controlFlowHyperEdgeNodePrefix + gnn_input.controlFlowHyperEdgeCanonicalID.toString
          createHyperEdgeNode(controlFlowHyperedgeName, "guarded CFHE Clause " + clauseNumber.toString, "controlFlowHyperEdge", nodeShapeMap("controlFlowHyperEdge"))
          //store control flow hyperedge connection between body and head
          hyperEdgeList :+= new hyperEdgeInfo(controlFlowHyperedgeName, controlFlowNodeSetInOneClause(body.pred.name), controlFlowNodeSetInOneClause(normalizedClause.head.pred.name), HyperEdgeType.controlFlow)
        }
      }
    }

    if (guardSet.isEmpty) {
      val trueNodeName = "true_" + gnn_input.GNNNodeID.toString
      createNode(trueNodeName, "true", "constant", nodeShapeMap("constant"))
      constantNodeSetInOneClause("true") = trueNodeName
      for (hyperEdgeNode <- hyperEdgeList) {
        hyperEdgeNode.hyperEdgeType match {
          case HyperEdgeType.controlFlow => addTernaryEdge(hyperEdgeNode.fromName, trueNodeName, hyperEdgeNode.toName, hyperEdgeNode.hyperEdgeNodeName, "controlFlowHyperEdge")
          case HyperEdgeType.dataFlow => addTernaryEdge(hyperEdgeNode.fromName, trueNodeName, hyperEdgeNode.toName, hyperEdgeNode.hyperEdgeNodeName, "dataFlowHyperEdge")
        }
      }
    } else {
      astEdgeType = "guardAST"
      for (guard <- guardSet) {
        val guardRootNodeName = drawAST(guard)
        for (hyperEdgeNode <- hyperEdgeList) {
          hyperEdgeNode.guardName += guardRootNodeName
          if (hyperEdgeNode.guardName.size <= 1) {
            hyperEdgeNode.hyperEdgeType match {
              case HyperEdgeType.controlFlow => addTernaryEdge(hyperEdgeNode.fromName, guardRootNodeName, hyperEdgeNode.toName, hyperEdgeNode.hyperEdgeNodeName, "controlFlowHyperEdge")
              case HyperEdgeType.dataFlow => addTernaryEdge(hyperEdgeNode.fromName, guardRootNodeName, hyperEdgeNode.toName, hyperEdgeNode.hyperEdgeNodeName, "dataFlowHyperEdge")
            }
          } else {
            hyperEdgeNode.hyperEdgeType match {
              case HyperEdgeType.controlFlow => updateTernaryEdge(hyperEdgeNode.fromName, guardRootNodeName, hyperEdgeNode.toName, hyperEdgeNode.hyperEdgeNodeName, "controlFlowHyperEdge")
              case HyperEdgeType.dataFlow => updateTernaryEdge(hyperEdgeNode.fromName, guardRootNodeName, hyperEdgeNode.toName, hyperEdgeNode.hyperEdgeNodeName, "dataFlowHyperEdge")
            }
          }
        }
      }
    }

    clauseNumber += 1
  }
  writerGraph.write("}" + "\n")
  writerGraph.close()
  dataFlowInfoWriter.close()

  val (argumentIDList, argumentNameList, argumentOccurrenceList,argumentBoundList,argumentIndicesList) = matchArguments()
  writeGNNInputToJSONFile(argumentIDList, argumentNameList, argumentOccurrenceList,argumentBoundList,argumentIndicesList)

  def drawPredicateNode(controlFlowNodeName: String, predicateName: String, className: String): Unit = {
    //draw predicate node
    createNode(controlFlowNodeName, predicateName, className, nodeShapeMap(className))
    controlFlowNodeSetInOneClause(predicateName) = controlFlowNodeName
  }

  def drawDataFlow(arg: ITerm, dataFlowSet: Set[IExpression]): Unit = {
    val SE = IExpression.SymbolEquation(arg)
    for (df <- dataFlowSet) df match {
      case SE(coefficient, rhs) if (!coefficient.isZero) => {
        //draw data flow hyperedge node
        val dataFlowHyperedgeName = dataFlowHyperEdgeNodePrefix + gnn_input.dataFlowHyperEdgeCanonicalID.toString
        createHyperEdgeNode(dataFlowHyperedgeName, "guarded DFHE Clause " + clauseNumber.toString, "dataFlowHyperEdge", nodeShapeMap("dataFlowHyperEdge"))
        astEdgeType = "dataFlowAST"
        val dataFlowRoot = drawAST(rhs)
        //store data flow hyperedge connection
        hyperEdgeList :+= new hyperEdgeInfo(dataFlowHyperedgeName, dataFlowRoot, constantNodeSetInOneClause(arg.toString), HyperEdgeType.dataFlow)
      }
      case _ => {}
    }
  }

  def getDataFlowAndGuard(clause: Clause, normalizedClause: Clause, dataFlowInfoWriter: PrintWriter): (Set[IExpression], Set[IFormula]) = {
    /*
   (1) x = f(\bar y) s.t.

   <1> x is one of the arguments of the clause head
   <2> \bar y are arguments of the literals in the clause body.
   <3> any variable assignment (assignment of values to the variables occurring in C) that satisfies the constraint of C also satisfies (1).
   */
    var dataflowList = Set[IExpression]()
    var dataflowListHeadArgSymbolEquation = Set[IExpression]()
    val bodySymbols = for (body <- normalizedClause.body; arg <- body.args) yield new ConstantTerm(arg.toString)
    var bodySymbolsSet = bodySymbols.toSet
    for (x <- normalizedClause.head.args) {
      val SE = IExpression.SymbolEquation(x)
      val constantTermX = new ConstantTerm(x.toString)
      for (f <- LineariseVisitor(normalizedClause.constraint, IBinJunctor.And)) f match {
        case SE(coefficient, rhs) => {
          if (!(dataflowList contains f) && !(bodySymbolsSet contains constantTermX) && !SymbolCollector.constants(rhs).isEmpty
            && (for (s <- SymbolCollector.constants(rhs)) yield s.name).subsetOf(for (s <- bodySymbolsSet) yield s.name)
            && (for (s <- SymbolCollector.constants(f)) yield s.name).contains(x.toString)) {
            // discovered data-flow from body to x!
            dataflowList += f //sp(IExpression.Eq(x,rhs))
            dataflowListHeadArgSymbolEquation += sp(IExpression.Eq(x, rhs))
            bodySymbolsSet += constantTermX
          }
        }
        case _ => { //guardList+=f}
        }
      }
    }
    val guardList = (for (f <- LineariseVisitor(normalizedClause.constraint, IBinJunctor.And)) yield f).toSet.diff(for (df <- dataflowList) yield df.asInstanceOf[IFormula])
    dataFlowInfoWriter.write("--------------------\n")
    dataFlowInfoWriter.write(clause.toPrologString + "\n")
    dataFlowInfoWriter.write(normalizedClause.toPrologString + "\n")
    dataFlowInfoWriter.write("dataflow:\n")
    for (df <- dataflowListHeadArgSymbolEquation)
      dataFlowInfoWriter.write(df.toString + "\n")
    dataFlowInfoWriter.write("guard:\n")
    for (g <- guardList)
      dataFlowInfoWriter.write(g.toString + "\n")
    (dataflowListHeadArgSymbolEquation, guardList)
  }

}
