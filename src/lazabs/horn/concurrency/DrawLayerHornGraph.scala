/**
 * Copyright (c) 2011-2020 Philipp Ruemmer, Chencheng Liang All rights reserved.
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

import ap.parser.{IAtom, IBinFormula, IBinJunctor, IBoolLit, IConstant, IEpsilon, IExpression, IFormulaITE, IFunApp, IIntFormula, IIntLit, INamedPart, INot, IPlus, IQuantified, ITerm, ITermITE, ITimes, ITrigger, IVariable, LineariseVisitor}
import lazabs.horn.preprocessor.HornPreprocessor.{Clauses, VerificationHints}
import play.api.libs.json.Json
import scala.collection.mutable.ListBuffer

class DrawLayerHornGraph(file: String, simpClauses: Clauses, hints: VerificationHints, argumentInfoList: ListBuffer[argumentInfo]) extends DrawHornGraph(file: String, simpClauses: Clauses, hints: VerificationHints, argumentInfoList: ListBuffer[argumentInfo]) {

  println("Write layer horn graph to file")
  edgeNameMap += ("predicateArgument" -> "PA")
  edgeNameMap += ("predicateInstance" -> "PI")
  edgeNameMap += ("argumentInstance" -> "AI")
  edgeNameMap += ("controlHead" -> "CH")
  edgeNameMap += ("controlBody" -> "CB")
  edgeNameMap += ("controlArgument" -> "ARG")
  edgeNameMap += ("guard" -> "guard")
  edgeNameMap += ("data" -> "data")
  edgeNameMap += ("subTerm" -> "st")
  //turn on/off edge's label
  var edgeNameSwitch = true
  if (edgeNameSwitch == false) {
    for (key <- edgeNameMap.keys)
      edgeNameMap += (key -> "")
  }

  //node shape map
  nodeShapeMap += ("constant" -> "circle")
  nodeShapeMap += ("operator" -> "square")
  nodeShapeMap += ("predicateName" -> "box")
  nodeShapeMap += ("predicateArgument" -> "ellipse")
  nodeShapeMap += ("clause" -> "box")
  nodeShapeMap += ("clauseHead" -> "box")
  nodeShapeMap += ("clauseBody" -> "box")
  nodeShapeMap += ("clauseArgument" -> "ellipse")
  nodeShapeMap += ("symbolicConstant" -> "circle")
  //node cotegory: Operator and Constant don't need canonical name. FALSE is unique category
  val predicateNamePrefix = "predicate_"
  val predicateArgumentPrefix = "predicateArgument_"
  val clausePrefix = "clause_"
  val clauseHeadPrefix = "clauseHead_"
  val clauseBodyPrefix = "clauseBody_"
  val clauseArgumentPrefix = "clauseArgument_"
  val symbolicConstantPrefix = "symbolicConstant_"

  var predicateNameMap = Map[String, predicateInfo]() //original name -> canonical name
  class predicateInfo(predicateName: String) {
    val predicateCanonicalName = predicateName
    var argumentCanonicalNameList = new ListBuffer[Pair[String, Int]]() //(canonicalName, ID)
  }

  for (clause <- simpClauses) {
    //predicate layer: create predicate and arguments and edges between them
    createPredicateLayerNodesAndEdges(clause.head)
    for (bodyPredicate <- clause.body) {
      //predicate layer: create predicate and arguments and edges between them
      createPredicateLayerNodesAndEdges(bodyPredicate)
    }
  }

  for (clause <- simpClauses) {
    constantNodeSetInOneClause.clear()
    //clause layer: create clause node
    val clauseNodeName = clausePrefix + gnn_input.clauseCanonicalID.toString
    createNode(clauseNodeName,
      "C" + gnn_input.clauseCanonicalID.toString, "clause", nodeShapeMap("clause"))
    //draw constraints and connect to clause node
    for (conjunct <- LineariseVisitor(clause.constraint, IBinJunctor.And)) {
      drawAST(conjunct, clauseNodeName)
    }

    //clause layer: create clause head node
    val clauseHeadNodeName = clauseHeadPrefix + gnn_input.clauseHeadCanonicalID.toString
    createNode(clauseHeadNodeName,
      "HEAD", "clauseHead", nodeShapeMap("clauseHead"))
    //clause layer: create edge between head and clause node
    addBinaryEdge(clauseNodeName, clauseHeadNodeName, "controlHead")
    //predicateLayer->clauseLayer: connect predicate to clause head
    addBinaryEdge(predicateNameMap(clause.head.pred.name).predicateCanonicalName, clauseHeadNodeName, "predicateInstance")
    var tempIDForArgument = 0
    for ((headArgument, predicateArgument) <- clause.head.args zip predicateNameMap(clause.head.pred.name).argumentCanonicalNameList) {
      //clause layer: create clause head argument node
      val clauseArgumentNodeName = clauseArgumentPrefix + gnn_input.clauseArgCanonicalID.toString
      createNode(clauseArgumentNodeName,
        "ARG" + tempIDForArgument.toString, "clauseArgument", nodeShapeMap("clauseArgument"))
      //clause layer: create edge between head and argument
      addBinaryEdge(clauseHeadNodeName, clauseArgumentNodeName, "controlArgument")
      //predicateLayer->clauseLayer: connect predicate argument to clause argument
      drawAST(headArgument, clauseArgumentNodeName)
      tempIDForArgument += 1
    }

    //clause layer: create clause arguments node in body
    var tempIDForPredicate = 0
    for (bodyPredicate <- clause.body) {
      //clause layer: create clause body node
      val clauseBodyNodeName = clauseBodyPrefix + gnn_input.clauseBodyCanonicalID.toString
      createNode(clauseBodyNodeName,
        "BODY" + tempIDForPredicate.toString, "clauseBody", nodeShapeMap("clauseBody"))
      tempIDForPredicate += 1
      //clause layer: create edge between body and clause node
      addBinaryEdge(clauseNodeName, clauseBodyNodeName, "controlBody")
      //predicateLayer->clauseLayer: connect predicate to clause body
      addBinaryEdge(predicateNameMap(bodyPredicate.pred.name).predicateCanonicalName, clauseBodyNodeName, "predicateInstance")

      tempIDForArgument = 0
      for ((bodyArgument, predicateArgument) <- bodyPredicate.args zip predicateNameMap(bodyPredicate.pred.name).argumentCanonicalNameList) {
        //clause layer: create clause body argument node
        val clauseArgumentNodeName = clauseArgumentPrefix + gnn_input.clauseArgCanonicalID.toString
        createNode(clauseArgumentNodeName,
          "ARG" + tempIDForArgument.toString, "clauseArgument", nodeShapeMap("clauseArgument"))
        //clause layer: create edge between body and argument
        addBinaryEdge(clauseBodyNodeName, clauseArgumentNodeName, "controlArgument")
        //predicateLayer->clauseLayer: connect predicate argument to clause argument
        addBinaryEdge(predicateArgument._1, clauseArgumentNodeName, "argumentInstance")
        drawAST(bodyArgument, clauseArgumentNodeName)
        tempIDForArgument += 1
      }
    }
  }
  writerGraph.write("}" + "\n")
  writerGraph.close()

  val (argumentIDList, argumentNameList, argumentOccurrenceList) = matchArguments()
  writeGNNInputToJSONFile(argumentIDList, argumentNameList, argumentOccurrenceList)
  /*
  //write JSON file by json library
  val layerVersionGraphGNNInput=Json.obj("nodeIds" -> gnn_input.nodeIds,"nodeSymbolList"->gnn_input.nodeSymbols,
    "argumentIndices"->gnn_input.argumentIndices,
    "binaryAdjacentList"->gnn_input.binaryAdjacency.binaryEdge.toVector.toString(),
    "ternaryAdjacencyList"->gnn_input.ternaryAdjacency.ternaryEdge.toString,
    "predicateArgumentEdges"->gnn_input.predicateArgumentEdges.binaryEdge.toString,
    "predicateInstanceEdges"->gnn_input.predicateInstanceEdges.binaryEdge.toString,
    "argumentInstanceEdges"->gnn_input.argumentInstanceEdges.binaryEdge.toString,
    "controlHeadEdges"->gnn_input.controlHeadEdges.binaryEdge.toString,
    "controlBodyEdges"->gnn_input.controlBodyEdges.binaryEdge.toString,
    "controlArgumentEdges"->gnn_input.controlArgumentEdges.binaryEdge.toString,
    "guardEdges"->gnn_input.guardEdges.binaryEdge.toString,
    "dataEdges"->gnn_input.dataEdges.binaryEdge.toString,
    "unknownEdges"->gnn_input.unknownEdges.binaryEdge.toString,
    "argumentIDList"->argumentIDList,
    "argumentNameList"->argumentNameList,
    "argumentOccurrence"->argumentOccurrenceList)
  println("Write GNNInput to file")
  val writer = new PrintWriter(new File(file + ".layerHornGraph.JSON")) //python path
  writer.write(layerVersionGraphGNNInput.toString())
  writer.close()
*/


  def createPredicateLayerNodesAndEdges(pred: IAtom): Unit = {
    //predicate layer: create predicate and argument node
    if (!predicateNameMap.contains(pred.pred.name)) {
      val predicateNodeCanonicalName = predicateNamePrefix + gnn_input.predicateNameCanonicalID.toString
      predicateNameMap += (pred.pred.name -> new predicateInfo(predicateNodeCanonicalName))
      createNode(predicateNodeCanonicalName,
        pred.pred.name, "predicateName", nodeShapeMap("predicateName"))
      var tempID = 0
      for (headArg <- pred.args) {
        val argumentNodeCanonicalName = predicateArgumentPrefix + gnn_input.predicateArgumentCanonicalID.toString
        //create argument node
        createNode(argumentNodeCanonicalName,
          "Arg" + tempID.toString, "predicateArgument", nodeShapeMap("predicateArgument"))
        //create edge from argument to predicate
        addBinaryEdge(predicateNodeCanonicalName, argumentNodeCanonicalName, "predicateArgument")
        predicateNameMap(pred.pred.name).argumentCanonicalNameList += Pair(argumentNodeCanonicalName, tempID)
        gnn_input.argumentInfoHornGraphList += new argumentInfoHronGraph(pred.pred.name, tempID)
        tempID += 1
      }
    }
  }


}
