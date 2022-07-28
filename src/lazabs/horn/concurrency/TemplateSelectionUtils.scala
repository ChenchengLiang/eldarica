package lazabs.horn.concurrency

import ap.PresburgerTools
import ap.parser.IExpression.Predicate
import ap.parser.{IAtom, IFormula}
import ap.terfor.TerForConvenience
import ap.terfor.TerForConvenience.{conj, disj, v}
import ap.terfor.conjunctions.Conjunction
import ap.terfor.preds.Predicate
import ap.terfor.substitutions.ConstantSubst
import lazabs.GlobalParameters
import lazabs.horn.abstractions.StaticAbstractionBuilder.AbstractionType
import lazabs.horn.abstractions.{AbsReader, StaticAbstractionBuilder, VerificationHints}
import lazabs.horn.bottomup.CEGAR.Counterexample
import lazabs.horn.bottomup.DisjInterpolator.AndOrNode
import lazabs.horn.bottomup.HornClauses.Clause
import lazabs.horn.bottomup.Util.Dag
import lazabs.horn.bottomup.{AbstractState, CEGAR, HornClauses, HornPredAbs, HornPredAbsContext, HornTranslator, HornWrapper, NormClause, PredicateMiner, PredicateStore}
import lazabs.horn.concurrency.DrawHornGraph.{HornGraphType, addQuotes}
import lazabs.horn.concurrency.HintsSelection.{detectIfAJSONFieldExists, generateCombinationTemplates, getArgumentInfo, getParametersFromVerifHintElement, getPredGenerator, termContains, wrappedReadHintsCheckExistence}
import lazabs.horn.preprocessor.HornPreprocessor.Clauses
import play.api.libs.json.{JsSuccess, JsValue, Json}
import scala.util.Random

import java.io.{File, PrintWriter}

object TemplateSelectionUtils{

  def outputPrologFile(normalizedClauses:Seq[Clause],typeName:String="normalized"): Unit ={
    val writerGraph = new PrintWriter(new File(GlobalParameters.get.fileName + "."+typeName+".prolog"))
    for (c<-normalizedClauses)
      writerGraph.write(c.toPrologString+"\n")
    writerGraph.close()
  }

  def writeGNNInputFieldToJSONFile(fieldName: String, fiedlContent: Arrays, writer: PrintWriter, lastFiledFlag: Boolean): Unit = {
    fiedlContent match {
      case StringArray(x) => writeOneField(fieldName, x, writer)
      case IntArray(x) => writeOneField(fieldName, x, writer)
      case PairArray(x) => writeOneField(fieldName, x, writer)
      case TripleArray(x) => writeOneField(fieldName, x, writer)
      case PairStringArray(x)=> writePairStringArrayField(fieldName, x, writer)
    }
    if (lastFiledFlag == false)
      writer.write(",\n")
    else
      writer.write("\n")
  }

  sealed abstract class Arrays
  case class StringArray(x: Array[String]) extends Arrays
  case class IntArray(x: Array[Int]) extends Arrays
  case class PairArray(x: Array[Pair[Int, Int]]) extends Arrays
  case class PairStringArray(x: Array[Pair[String, String]]) extends Arrays
  case class TripleArray(x: Array[Triple[Int, Int, Int]]) extends Arrays

  def writeOneField(fieldName: String, fiedlContent: Array[Pair[Int, Int]], writer: PrintWriter): Unit = {
    writer.write(addQuotes(fieldName))
    writer.write(":")
    writer.write("[")
    val filedSize = fiedlContent.size - 1
    for ((p, i) <- fiedlContent.zipWithIndex) {
      writer.write("[")
      writer.write(p._1.toString)
      writer.write(",")
      writer.write(p._2.toString)
      writer.write("]")
      if (i < filedSize)
        writer.write(",")
    }
    writer.write("]")
  }
  def writePairStringArrayField(fieldName: String, fiedlContent: Array[Pair[String, String]], writer: PrintWriter): Unit = {
    writer.write(addQuotes(fieldName))
    writer.write(":")
    writer.write("[")
    val filedSize = fiedlContent.size - 1
    for ((p, i) <- fiedlContent.zipWithIndex) {
      writer.write("[")
      writer.write(p._1)
      writer.write(",")
      writer.write(p._2)
      writer.write("]")
      if (i < filedSize)
        writer.write(",")
    }
    writer.write("]")
  }
  def writeOneField(fieldName: String, fiedlContent: Array[Triple[Int, Int, Int]], writer: PrintWriter): Unit = {
    writer.write(addQuotes(fieldName))
    writer.write(":")
    writer.write("[")
    val filedSize = fiedlContent.size - 1
    for ((p, i) <- fiedlContent.zipWithIndex) {
      writer.write("[")
      writer.write(p._1.toString)
      writer.write(",")
      writer.write(p._2.toString)
      writer.write(",")
      writer.write(p._3.toString)
      writer.write("]")
      if (i < filedSize)
        writer.write(",")
    }
    writer.write("]")
  }

  def writeOneField(fieldName: String, fiedlContent: Array[Int], writer: PrintWriter): Unit = {
    writer.write(addQuotes(fieldName))
    writer.write(":")
    writer.write("[")
    val filedSize = fiedlContent.size - 1
    for ((p, i) <- fiedlContent.zipWithIndex) {
      writer.write(p.toString)
      if (i < filedSize)
        writer.write(",")
    }
    writer.write("]")
  }

  def writeOneField(fieldName: String, fiedlContent: Array[String], writer: PrintWriter): Unit = {
    writer.write(addQuotes(fieldName))
    writer.write(":")
    writer.write("[")
    val filedSize = fiedlContent.size - 1
    for ((p, i) <- fiedlContent.zipWithIndex) {
      writer.write(addQuotes(p.toString))
      if (i < filedSize)
        writer.write(",")
    }
    writer.write("]")
  }
  def readOneJSONFieldToMap(fieldName:String,fileName:String,json_data: JsValue,fields:Map[String,String]): Map[String,String] ={
    try{
      val stRelationalEqs= (json_data \ fieldName).validate[Array[String]] match {
        case JsSuccess(st,_)=> st
      }
      fields++Map(fieldName->stRelationalEqs.head)
    } catch {
      case _=> fields
    }
  }
  def readJSONFieldToMap(solvingTimeFileName:String,fieldNames:Seq[String]=Seq("RelationalEqs","Term","Octagon","RelationalIneqs","splitClauses")): Map[String,String] ={
    var fields:Map[String,String]=Map()
    val json_content = scala.io.Source.fromFile(solvingTimeFileName).mkString
    val json_data = Json.parse(json_content)
    //println("json_data",json_data)
    for (f<-fieldNames)
      fields=readOneJSONFieldToMap(fieldName = f,fileName = solvingTimeFileName,json_data=json_data,fields = fields)
    fields
  }

  def writeSolvingTimeToJSON(solvingTimeFileName:String,fields:Map[String,String]): Unit ={
    val writer = new PrintWriter(new File(solvingTimeFileName))
    writer.write("{\n")
    var lastFiledFlag= false
    for (f<-fields)
      writeGNNInputFieldToJSONFile(f._1, StringArray(Array[String](f._2)), writer, lastFiledFlag)
    lastFiledFlag = true
    writeGNNInputFieldToJSONFile("dummyFiled", StringArray(Array[String]()), writer, lastFiledFlag)
    writer.write("}")
    writer.close()
  }

  def getSolvability(unsimplifiedClauses: Seq[Clause],
                     simplifiedClausesForGraph:Seq[Clause],
                     initialPredicatesForCEGAR:Map[Predicate, Seq[IFormula]],
                     predGenerator : Dag[AndOrNode[NormClause, Unit]] => Either[Seq[(Predicate, Seq[Conjunction])], Dag[(IAtom, NormClause)]]): Unit ={
    println(Console.BLUE+"--------------check solvability ---------------")
    val unlabeledTemplates = wrappedReadHintsCheckExistence(simplifiedClausesForGraph,".unlabeledPredicates",VerificationHints(Map()))
    val unlabeledTemplatesStatistics=HintsSelection.getVerificationHintsStatistics(unlabeledTemplates)
    val labeledTemplates = wrappedReadHintsCheckExistence(simplifiedClausesForGraph,".labeledPredicates",VerificationHints(Map()))
    val labeledTemplatesStatistics=HintsSelection.getVerificationHintsStatistics(labeledTemplates)
    val minedTemplates = wrappedReadHintsCheckExistence(simplifiedClausesForGraph,".minedPredicates",VerificationHints(Map()))
    val minedTemplatesStatistics=HintsSelection.getVerificationHintsStatistics(minedTemplates)

    val jsonFileName= if (GlobalParameters.get.getSolvingTime) "solvingTime" else if (GlobalParameters.get.checkSolvability) "solvability" else ""
    val solvingTimeFileName = GlobalParameters.get.fileName + "." + jsonFileName + ".JSON"
    val meansureFields=Seq("solvingTime","cegarIterationNumber","generatedPredicateNumber",
      "averagePredicateSize","predicateGeneratorTime","solvability",
      "clauseNumberBeforeSimplification","clauseNumberAfterSimplification","smt2FileSizeByte","relationSymbolNumberBeforeSimplification","relationSymbolNumberAfterSimplification",
    "minedSingleVariableTemplatesNumber","minedBinaryVariableTemplatesNumber","minedTemplateNumber","minedTemplateRelationSymbolNumber",
      "labeledSingleVariableTemplatesNumber","labeledBinaryVariableTemplatesNumber","labeledTemplateNumber","labeledTemplateRelationSymbolNumber",
      "unlabeledSingleVariableTemplatesNumber","unlabeledBinaryVariableTemplatesNumber","unlabeledTemplateNumber","unlabeledTemplateRelationSymbolNumber")
    val AbstractionTypeFields=AbstractionType.values.toSeq
    val splitClausesOption=Seq("splitClauses_0","splitClauses_1")
    val initialFieldsSeq= (for (m<-meansureFields;a<-AbstractionTypeFields;s<-splitClausesOption) yield (m+"_"+a+"_"+s->(m,a,s))).toMap
    if(!jsonFileName.isEmpty && !new java.io.File(solvingTimeFileName).exists){
      //create solving time JSON file
      val timeout = 60 * 60 * 3 * 1000 //milliseconds
      //val initialFields: Map[String, Int] = (for (e<-initialFieldsSeq) yield e->timeout).toMap
      val initialFields: Map[String, Int] = (
        for ((k,v)<-initialFieldsSeq) yield v._1 match {
          case "clauseNumberBeforeSimplification"=>k->{unsimplifiedClauses.length}
          case "clauseNumberAfterSimplification"=>k->{simplifiedClausesForGraph.length}
          case "smt2FileSizeByte"=>k->new File(GlobalParameters.get.fileName).length().toInt//bytes
          case "relationSymbolNumberBeforeSimplification"=>k->unsimplifiedClauses.map(_.allAtoms.length).reduce(_+_)
          case "relationSymbolNumberAfterSimplification"=>k->simplifiedClausesForGraph.map(_.allAtoms.length).reduce(_+_)
          case "minedSingleVariableTemplatesNumber"=> k->minedTemplatesStatistics._1
          case "minedBinaryVariableTemplatesNumber"=> k->minedTemplatesStatistics._2
          case "minedTemplateNumber"=>k->minedTemplatesStatistics._3
          case "minedTemplateRelationSymbolNumber"=>k->minedTemplatesStatistics._4
          case "labeledSingleVariableTemplatesNumber"=> k->labeledTemplatesStatistics._1
          case "labeledBinaryVariableTemplatesNumber"=> k->labeledTemplatesStatistics._2
          case "labeledTemplateNumber"=>k->labeledTemplatesStatistics._3
          case "labeledTemplateRelationSymbolNumber"=>k->labeledTemplatesStatistics._4
          case "unlabeledSingleVariableTemplatesNumber"=> k->unlabeledTemplatesStatistics._1
          case "unlabeledBinaryVariableTemplatesNumber"=> k->unlabeledTemplatesStatistics._2
          case "unlabeledTemplateNumber"=>k->unlabeledTemplatesStatistics._3
          case "unlabeledTemplateRelationSymbolNumber"=>k->unlabeledTemplatesStatistics._4
          case _=>k->timeout
          }
        ).toMap
      writeSolvingTimeToJSON(solvingTimeFileName, initialFields.mapValues(_.toString))
    }
    val outStream = Console.err
    val predAbs = Console.withOut(outStream) {
      if (GlobalParameters.get.templateBasedInterpolationType == AbstractionType.Combined) {
        val predGeneratorSeq = for (abstractOption <- Seq(AbstractionType.Octagon, AbstractionType.Term, AbstractionType.RelationalEqs, AbstractionType.RelationalIneqs,
          AbstractionType.PredictedCG, AbstractionType.PredictedCDHG, AbstractionType.Unlabeled, AbstractionType.Random)) yield
          (abstractOption, getPredGenerator(Seq((new StaticAbstractionBuilder(simplifiedClausesForGraph, abstractOption)).abstractionRecords), outStream = outStream))
        new TemplateSelectionHornPredAbs(simplifiedClausesForGraph, initialPredicatesForCEGAR, predGenerator, predGeneratorSeq)
      }
      else {
        new HornPredAbs(simplifiedClausesForGraph, initialPredicatesForCEGAR, predGenerator)
      }
    }


    if (new java.io.File(solvingTimeFileName).exists){ //update the solving time for current abstract option in JSON file
      val solvingTime=(predAbs.cegar.cegarEndTime - predAbs.cegar.cegarStartTime)//milliseconds
      val cegarIterationNumber=predAbs.cegar.iterationNum
      val generatedPredicateNumber=predAbs.cegar.generatedPredicateNumber
      val averagePredicateSize=predAbs.cegar.averagePredicateSize
      val predicateGeneratorTime=predAbs.cegar.predicateGeneratorTime
      val solvability=1
      val resultList=Seq(solvingTime,cegarIterationNumber,generatedPredicateNumber,
        averagePredicateSize,predicateGeneratorTime,solvability).map(_.toInt).map(_.toString)
      for ((m,v)<-meansureFields.zip(resultList)) {
        writeSolvingTimeToJSON(solvingTimeFileName,readJSONFieldToMap(solvingTimeFileName,fieldNames=initialFieldsSeq.keys.toSeq).updated(m+"_"+GlobalParameters.get.templateBasedInterpolationType.toString+"_splitClauses_"+GlobalParameters.get.splitClauses.toString,v))
      }
    }
    sys.exit()

  }

  def mineTemplates(simplifiedClausesForGraph:Clauses,initialPredicatesForCEGAR:Map[Predicate, Seq[IFormula]],predGenerator : Dag[AndOrNode[NormClause, Unit]] => Either[Seq[(Predicate, Seq[Conjunction])],
      Dag[(IAtom, NormClause)]]): Unit ={
    //full code in TrainDataGeneratorTemplatesSmt2.scala

    if (GlobalParameters.get.generateTemplates){
      val unlabeledPredicateFileName=".unlabeledPredicates"
      val generatedTpl = generateCombinationTemplates(simplifiedClausesForGraph, onlyLoopHead = true) //false
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName + unlabeledPredicateFileName + ".tpl")) {AbsReader.printHints(generatedTpl)}
      sys.exit()
    }

    val predAbs =
      new HornPredAbs(simplifiedClausesForGraph, initialPredicatesForCEGAR, predGenerator)

    val absBuilder =
      new StaticAbstractionBuilder(
        simplifiedClausesForGraph,
        GlobalParameters.get.templateBasedInterpolationType)

    def labelTemplates(unlabeledTemplates:VerificationHints): (VerificationHints,VerificationHints) ={
      if (GlobalParameters.get.debugLog){println("Mining the templates...")}
      val predMiner=new PredicateMiner(predAbs)
      //val predMiner=new PredicateMiner(predAbs)
      val positiveTemplates=predMiner.unitTwoVariableTemplates//predMiner.variableTemplates
      val costThreshold=99
      val filteredPositiveTemplates= VerificationHints((for((k,ps)<-positiveTemplates.predicateHints) yield {
        k->ps.filter(getParametersFromVerifHintElement(_)._2<costThreshold)
      }).filterNot(_._2.isEmpty))
      if (GlobalParameters.get.debugLog){
        println("predicates from " +lazabs.GlobalParameters.get.templateBasedInterpolationType.toString)
        absBuilder.abstractionHints.pretyPrintHints()
        println("mined predicates (unitTwoVariableTemplates)")
        positiveTemplates.pretyPrintHints()
        println("filtered mined predicates")
        filteredPositiveTemplates.pretyPrintHints()
      }
//      if(filteredPositiveTemplates.isEmpty){
//        HintsSelection.moveRenameFile(GlobalParameters.get.fileName,"../benchmarks/exceptions/empty-mined-label/"+HintsSelection.getFileName(),"empty-mined-label")
//        sys.exit()
//      }
      val labeledTemplates=VerificationHints(for ((kp,vp)<-unlabeledTemplates.predicateHints;
                                                  (kf,vf)<-filteredPositiveTemplates.predicateHints;
                                                  if kp.name==kf.name) yield
        kp -> (for (p<-vp;if termContains(vf.map(getParametersFromVerifHintElement(_)),getParametersFromVerifHintElement(p))._1) yield p)
      )
      if (GlobalParameters.get.debugLog){
        println("-"*10+"unlabeledTemplates"+"-"*10)
        unlabeledTemplates.pretyPrintHints()
        println("-"*10+"labeledTemplates"+"-"*10)
        labeledTemplates.pretyPrintHints()
      }
      (positiveTemplates,labeledTemplates)
      //filteredPositiveTemplates
    }

    val combinationTemplates=generateCombinationTemplates(simplifiedClausesForGraph,onlyLoopHead = false)
    val unlabeledTemplates=combinationTemplates
    val (positiveTemplates,labeledTemplates)=labelTemplates(unlabeledTemplates)

//    if(labeledTemplates.totalPredicateNumber==0){
//      HintsSelection.moveRenameFile(GlobalParameters.get.fileName,"../benchmarks/exceptions/no-predicates-selected/"+HintsSelection.getFileName(),"labeledPredicates is empty")
//      sys.exit()
//    }
    HintsSelection.writeTemplatesToFile(unlabeledTemplates,"unlabeledPredicates")
    HintsSelection.writeTemplatesToFile(labeledTemplates,"labeledPredicates")
    HintsSelection.writeTemplatesToFile(positiveTemplates,"minedPredicates")
    //HintsSelection.writePredicatesToFiles(unlabeledTemplates,labeledTemplates,positiveTemplates)

    sys.exit()
  }

  def getHornGraphForTemplatesSelection(simpClauses:Seq[Clause]): Unit ={
    val simplifiedClausesForGraph = HintsSelection.normalizedClausesForGraphs(simpClauses, VerificationHints(Map()))
    if (GlobalParameters.get.debugLog)
      simplifiedClausesForGraph.map(_.toPrologString).foreach(println)

    if (GlobalParameters.get.getHornGraph == true) {
      HintsSelection.filterInvalidInputs(simplifiedClausesForGraph)
      HintsSelection.checkMaxNode(simplifiedClausesForGraph)
    }

    val unlabeledTemplates=HintsSelection.wrappedReadHintsCheckExistence(simplifiedClausesForGraph,".unlabeledPredicates",generateCombinationTemplates(simplifiedClausesForGraph, onlyLoopHead = true) )
    if (unlabeledTemplates.totalPredicateNumber == 0 ) {
      HintsSelection.moveRenameFile(GlobalParameters.get.fileName, "../benchmarks/exceptions/no-initial-predicates/" + GlobalParameters.get.fileName.substring(GlobalParameters.get.fileName.lastIndexOf("/"), GlobalParameters.get.fileName.length), message = "no initial predicates")
      sys.exit()
    }
    val truePredicates = if (new java.io.File(GlobalParameters.get.fileName + ".labeledPredicates" + ".tpl").exists)
      HintsSelection.wrappedReadHints(simplifiedClausesForGraph, ".labeledPredicates")
    else if (new java.io.File(GlobalParameters.get.fileName + "." + GlobalParameters.get.hornGraphType.toString + ".JSON").exists)
      HintsSelection.readPredicateLabelFromOneJSON(new VerificationHintsInfo(unlabeledTemplates, VerificationHints(Map()), VerificationHints(Map())), "templateRelevanceLabel")
    else VerificationHints(Map())

    val predictedPredicates = if ((new java.io.File(GlobalParameters.get.fileName + "." + GlobalParameters.get.hornGraphType.toString + ".JSON")).exists)
      HintsSelection.readPredictedHints(simplifiedClausesForGraph, unlabeledTemplates)
    else
      VerificationHints(Map())

    //    val argumentInfo = HintsSelection.getArgumentLabel(simplifiedClausesForGraph,simpHints,predGenerator,disjunctive,
    //      argumentOccurrence = GlobalParameters.get.argumentOccurenceLabel,argumentBound =GlobalParameters.get.argumentBoundLabel)
    val argumentList = (for (p <- HornClauses.allPredicates(simplifiedClausesForGraph)) yield (p, p.arity)).toArray.sortBy(_._1.name)
    val argumentInfo =getArgumentInfo(argumentList)

    val clauseCollection = new ClauseInfo(simplifiedClausesForGraph, Seq())

    val hintsCollection = new VerificationHintsInfo(unlabeledTemplates, truePredicates, VerificationHints(Map()),predictedPredicates)
    GraphTranslator.drawAllHornGraph(clauseCollection, hintsCollection, argumentInfo)

    sys.exit()

  }
  def getSMT2Files(simplifiedClauses:Seq[Clause]): Unit ={
    GlobalParameters.get.hornGraphType=HornGraphType.monoDirectionLayerGraph
    HintsSelection.normalizedClausesForGraphs(simplifiedClauses, VerificationHints(Map()))
    GlobalParameters.get.hornGraphType=HornGraphType.hyperEdgeGraph
    HintsSelection.normalizedClausesForGraphs(simplifiedClauses, VerificationHints(Map()))
    sys.exit()
  }



  def getRandomElement[A](seq: Seq[A]): A =
    {
      val random = new Random
      if (GlobalParameters.get.fixRandomSeed)
        random.setSeed(42)
      seq(random.nextInt(seq.length))
    }

}

class TemplateSelectionHornPredAbs[CC <% HornClauses.ConstraintClause]
(iClauses : Iterable[CC],
 initialPredicates : Map[Predicate, Seq[IFormula]],
 predicateGenerator : Dag[AndOrNode[NormClause, Unit]] =>
   Either[Seq[(Predicate, Seq[Conjunction])],
     Dag[(IAtom, NormClause)]],
 predicateGeneratorSeq : Seq[(StaticAbstractionBuilder.AbstractionType.Value,Dag[AndOrNode[NormClause, Unit]] =>
   Either[Seq[(Predicate, Seq[Conjunction])],
     Dag[(IAtom, NormClause)]])],
 counterexampleMethod : CEGAR.CounterexampleMethod.Value=
   CEGAR.CounterexampleMethod.FirstBestShortest

) extends HornPredAbs(iClauses, initialPredicates, predicateGenerator, counterexampleMethod=
  CEGAR.CounterexampleMethod.FirstBestShortest) {

  //println(Console.BLUE+"TemplateSelectionHornPredAbs DEBUG")

  //Why is my abstract or overridden val null?
  // https://docs.scala-lang.org/tutorials/FAQ/initialization-order.html
  override lazy val cegar = new TemplateSelectionCEGAR(context, predStore,
    predicateGenerator,predicateGeneratorSeq,counterexampleMethod)

}

class TemplateSelectionCEGAR[CC <% HornClauses.ConstraintClause]
(context : HornPredAbsContext[CC],
 predStore : PredicateStore[CC],
 predicateGenerator : Dag[AndOrNode[NormClause, Unit]] =>
   Either[Seq[(Predicate, Seq[Conjunction])],
     Dag[(IAtom, NormClause)]],
 predicateGeneratorSeq : Seq[(StaticAbstractionBuilder.AbstractionType.Value,Dag[AndOrNode[NormClause, Unit]] =>
   Either[Seq[(Predicate, Seq[Conjunction])],
     Dag[(IAtom, NormClause)]])],
 counterexampleMethod : CEGAR.CounterexampleMethod.Value =
 CEGAR.CounterexampleMethod.FirstBestShortest) extends CEGAR (context, predStore, predicateGenerator, counterexampleMethod=
  CEGAR.CounterexampleMethod.FirstBestShortest) {
  import CEGAR._
  import context._
  import predStore._

  println(Console.BLUE+" TemplateSelectionCEGAR start")

  println("predicateGeneratorSeq",predicateGeneratorSeq.map(_._1).toString())

  override lazy val rawResult : Either[Map[Predicate, Conjunction], Dag[(IAtom, CC)]] =
  /* SimpleAPI.withProver(enableAssert = lazabs.Main.assertions) { p =>
      inferenceAPIProver = p */ {

    if (lazabs.GlobalParameters.get.log) {
      println
      println("Starting CEGAR ... ...")
    }

    import TerForConvenience._
    var res : Either[Map[Predicate, Conjunction], Dag[(IAtom, CC)]] = null

    while (!nextToProcess.isEmpty && res == null) {
      lazabs.GlobalParameters.get.timeoutChecker()

      /*
            // The invariants supposed to be preserved by the subsumption mechanism
            assert(
              (maxAbstractStates forall {
                case (rs, preds) => (preds subsetOf activeAbstractStates(rs)) &&
                                    (preds forall { s => activeAbstractStates(rs) forall {
                                                         t => s == t || !subsumes(t, s) } }) }) &&
              (backwardSubsumedStates forall {
                case (s, t) => s != t && subsumes(t, s) &&
                               (activeAbstractStates(s.rs) contains s) &&
                               !(maxAbstractStates(s.rs) contains s) &&
                               (activeAbstractStates(t.rs) contains t) }) &&
              (forwardSubsumedStates forall {
                case (s, t) => s != t && subsumes(t, s) &&
                               !(activeAbstractStates(s.rs) contains s) &&
                               (activeAbstractStates(t.rs) contains t) }) &&
              (activeAbstractStates forall {
                case (rs, preds) =>
                               preds forall { s => (maxAbstractStates(rs) contains s) ||
                               (backwardSubsumedStates contains s) } }) &&
              (postponedExpansions forall {
                case (from, _, _, _) => from exists (backwardSubsumedStates contains _) })
            )
      */

      val expansion@(states, clause, assumptions, _) = nextToProcess.dequeue

      if (states exists (backwardSubsumedStates contains _)) {
        postponedExpansions += expansion
      } else {
        try {
          for (e <- genEdge(clause, states, assumptions))
            addEdge(e)
        } catch {
          case Counterexample(from, clause) => {
            // we might have to process this case again, since
            // the extracted counterexample might not be the only one
            // leading to this abstract state
            nextToProcess.enqueue(states, clause, assumptions)

            val clauseDag = extractCounterexample(from, clause)
            iterationNum = iterationNum + 1

            if (lazabs.GlobalParameters.get.log) {
              println
              print("Found counterexample #" + iterationNum + ", refining ... ")

              if (lazabs.GlobalParameters.get.logCEX) {
                println
                clauseDag.prettyPrint
              }
            }

            {
              //todo: merge preds from differernt predicateGenerator
              //todo: change predicateGenerator in differernt iterations
              val randomPredGenerator=TemplateSelectionUtils.getRandomElement(predicateGeneratorSeq)
              println(Console.BLUE+"current predGenerator",randomPredGenerator._1)

              val predStartTime = System.currentTimeMillis

              //val preds = predicateGenerator(clauseDag)
              val preds = randomPredGenerator._2(clauseDag)

              predicateGeneratorTime =
                predicateGeneratorTime + System.currentTimeMillis - predStartTime
              preds
            } match {
              case Right(trace) => {
                if (lazabs.GlobalParameters.get.log)
                  print(" ... failed, counterexample is genuine")
                val clauseMapping = normClauses.toMap
                res = Right(for (p <- trace) yield (p._1, clauseMapping(p._2)))
              }
              case Left(newPredicates) => {
                if (lazabs.GlobalParameters.get.log)
                  println(" ... adding predicates:")
                addPredicates(newPredicates)
              }
            }
          }
        }
      }
    }

    if (res == null) {
      assert(nextToProcess.isEmpty)

      implicit val order = sf.order

      res = Left((for ((rs, preds) <- maxAbstractStates.iterator;
                       if (rs.pred != HornClauses.FALSE)) yield {
        val rawFor = disj(for (AbstractState(_, fors) <- preds.iterator) yield {
          conj((for (f <- fors.iterator) yield f.rawPred) ++
            (Iterator single relationSymbolBounds(rs)))
        })
        val simplified = //!QuantifierElimProver(!rawFor, true, order)
          PresburgerTools elimQuantifiersWithPreds rawFor

        val symMap =
          (rs.arguments.head.iterator zip (
            for (i <- 0 until rs.arity) yield v(i)).iterator).toMap
        val subst = ConstantSubst(symMap, order)

        rs.pred -> subst(simplified)
      }).toMap)
    }

    //    inferenceAPIProver = null

    res
  }
}

