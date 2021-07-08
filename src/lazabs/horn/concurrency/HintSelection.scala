/**
 * Copyright (c) 2011-2020 Philipp Ruemmer, CHencheng Liang. All rights reserved.
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
import ap.parser.ConstantSubstVisitor
import ap.SimpleAPI.ProverStatus
import ap.{PresburgerTools, Prover, SimpleAPI}
import ap.basetypes.IdealInt
import ap.parser.IExpression._
import ap.parser.{IExpression, IFormula, _}
import ap.terfor.conjunctions.Conjunction
import ap.terfor.preds.Predicate
import ap.theories.TheoryCollector
import ap.types.{SortedPredicate, TypeTheory}
import ap.util.{Seqs, Timeout}
import lazabs.GlobalParameters
import lazabs.horn.abstractions.AbstractionRecord.AbstractionMap
import lazabs.horn.abstractions.VerificationHints.{VerifHintElement, _}
import lazabs.horn.abstractions.{TemplateType, VerificationHints, _}
import lazabs.horn.bottomup
import lazabs.horn.bottomup.DisjInterpolator.AndOrNode
import lazabs.horn.bottomup.HornClauses.Clause
import lazabs.horn.bottomup.CEGAR
import lazabs.horn.bottomup.Util.Dag
import lazabs.horn.bottomup.{HornClauses, _}
import lazabs.horn.concurrency.GraphTranslator.getBatchSize
import lazabs.horn.preprocessor.{ConstraintSimplifier, HornPreprocessor}
import lazabs.horn.preprocessor.HornPreprocessor.{Clauses, VerificationHints, simplify}

import java.io.{File, FilenameFilter, PrintWriter}
import java.lang.System.currentTimeMillis
import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap, HashSet => MHashSet}
import scala.io.Source
import play.api.libs.json._

case class wrappedHintWithID(ID:Int,head:String, hint:String)

class LiteralCollector extends CollectingVisitor[Unit, Unit] {
  val literals = new MHashSet[IdealInt]
  def postVisit(t : IExpression, arg : Unit,
                subres : Seq[Unit]) : Unit = t match {
    case IIntLit(v)  =>
      literals += v
    case _ => ()
  }
}

object HintsSelection {
  val sp =new Simplifier
  val spAPI = ap.SimpleAPI.spawn
  val cs=new ConstraintSimplifier
  val timeoutForPredicateDistinct = 2000 // timeout in milli-seconds used in containsPred

  def getPredGenerator(absMaps:Seq[AbstractionMap],outStream : java.io.OutputStream):  Util.Dag[DisjInterpolator.AndOrNode[NormClause, Unit]] => Either[Seq[(Predicate, Seq[Conjunction])], Util.Dag[(IAtom, NormClause)]] ={
    val predGenerator = Console.withErr(outStream) {
      if (lazabs.GlobalParameters.get.templateBasedInterpolation) {
        val fullAbstractionMap =absMaps.reduce(AbstractionRecord.mergeMaps(_,_))
        if (fullAbstractionMap.isEmpty)
          DagInterpolator.interpolatingPredicateGenCEXAndOr _
        else
          TemplateInterpolator.interpolatingPredicateGenCEXAbsGen(
            fullAbstractionMap,
            lazabs.GlobalParameters.get.templateBasedInterpolationTimeout)
      } else {
        DagInterpolator.interpolatingPredicateGenCEXAndOr _
      }
    }
    predGenerator
  }

  def generateCombinationTemplates(simplifiedClauses:Clauses): VerificationHints ={
    val loopDetector = new LoopDetector(simplifiedClauses)
    VerificationHints((for (pred <- loopDetector.loopHeads.toSeq) yield{
      val argumentArities=0 to pred.arity-1
      val singleTerms= (for (i<-argumentArities) yield Seq(IVariable(i),-IVariable(i))).flatten
      val argumentComb=argumentArities.combinations(2).map(listToTuple2(_)).toSeq
      val combinationsTermsForEq=(for ((v1,v2)<-argumentComb) yield{
        Seq(IVariable(v1)-IVariable(v2),IVariable(v1)+IVariable(v2))
      }).flatten
      val combinationsTermsForInEq=(for ((v1,v2)<-argumentComb) yield{
        Seq(IVariable(v1)-IVariable(v2),IVariable(v2)-IVariable(v1),IVariable(v1)+IVariable(v2),-IVariable(v1)-IVariable(v2))
      }).flatten
      val allTermsEq=singleTerms++combinationsTermsForEq
      val allTermsInEq=singleTerms++combinationsTermsForInEq
      val allTypeElements=Seq(allTermsEq.map(VerifHintTplEqTerm(_,0)),
        allTermsInEq.map(VerifHintTplInEqTerm(_,0)))
      pred->allTypeElements.reduce(_++_)
    }).toMap)
  }

  def resetElementCost(element:VerifHintElement,c:Int):VerifHintElement=element match {
    case VerifHintTplPred(f,cost)=>{VerifHintTplPred(f,c)}
    case VerifHintTplPredPosNeg(f,cost)=>{VerifHintTplPredPosNeg(f,c)}
    case VerifHintTplEqTerm(t,cost)=>{VerifHintTplEqTerm(t,c)}
    case VerifHintTplInEqTerm(t,cost)=>{VerifHintTplInEqTerm(t,c)}
    case VerifHintTplInEqTermPosNeg(t,cost)=>{VerifHintTplInEqTermPosNeg(t,c)}
  }
//  def mergeTemplates(first:VerificationHints,second:VerificationHints): VerificationHints ={
//    val locations=first.predicateHints.keys ++ second.predicateHints.keys
//    VerificationHints((for(p<-locations) yield {
//      p->(first.predicateHints(p).map(resetElementCost(_,0))++
//        second.predicateHints(p).map(resetElementCost(_,0))).distinct //todo: need logic distinct
//    }).toMap)
//  }
  def setAllCost(hints : VerificationHints): VerificationHints ={
    VerificationHints(for ((pred, els) <- hints.predicateHints) yield {
      val modifiedEls= for(e<-els) yield {
        e match {
          case VerifHintTplEqTerm(t,c)=>VerifHintTplEqTerm(t,0)
          case VerifHintTplInEqTerm(t,c)=>VerifHintTplInEqTerm(t,0)
        }
      }
      pred->modifiedEls
    })
}

  def mergeTemplates(hints : VerificationHints) : VerificationHints = {
    val newPredHints =
      (for ((pred, els) <- hints.predicateHints) yield {
        val sorted = els.sortBy {
          case el : VerifHintTplElement => el.cost
          case _ => Int.MinValue
        }

        val res = new ArrayBuffer[VerifHintElement]

        for (el <- sorted) el match {
          case VerifHintTplEqTerm(s, _) =>
            if (!(res exists {
              case VerifHintTplEqTerm(t, _) =>
                equalTerms(s, t) || equalMinusTerms(s, t)
              case _ =>
                false
            })) {
              res += el
            }
          case VerifHintTplInEqTerm(s, _) =>
            if (!(res exists {
              case VerifHintTplInEqTerm(t, _) =>
                equalTerms(s, t)
              case VerifHintTplEqTerm(t, _) =>
                equalTerms(s, t) || equalMinusTerms(s, t)
              case _ =>
                false
            }))
              res += el
        }

        pred -> res.toSeq
      }).toMap

    VerificationHints(newPredHints)
  }

  def equalTerms(s : ITerm, t : ITerm) : Boolean = (s, t) match {
    case (s, t)
      if s == t => true
    case (Difference(s1, s2), Difference(t1, t2))
      if equalTerms(s1, t1) && equalTerms(s2, t2) => true
    case _ =>
      false
  }

  def equalMinusTerms(s : ITerm, t : ITerm) : Boolean = (s, t) match {
    case (ITimes(IdealInt.MINUS_ONE, s), t)
      if equalTerms(s, t) => true
    case (s, ITimes(IdealInt.MINUS_ONE, t))
      if equalTerms(s, t) => true
    case (Difference(s1, s2), Difference(t1, t2))
      if equalTerms(s1, t2) && equalTerms(s2, t1) => true
    case _ =>
      false
  }


  def getParametersFromVerifHintElement(element:VerifHintElement):(ITerm,Int,TemplateType.Value)=element match {
//    case VerifHintTplPred(f,cost)=>{(f,cost,TemplateType.TplPred)}
//    case VerifHintTplPredPosNeg(f,cost)=>{(f,cost,TemplateType.TplPredPosNeg)}
    case VerifHintTplEqTerm(t,cost)=>{(t,cost,TemplateType.TplEqTerm)}
    case VerifHintTplInEqTerm(t,cost)=>{(t,cost,TemplateType.TplInEqTerm)}
    //case VerifHintTplInEqTermPosNeg(t,cost)=>{(t,cost,TemplateType.TplInEqTermPosNeg)}
  }

  def getInitialPredicates(simplifiedClausesForGraph:Clauses,simpHints:VerificationHints): VerificationHints ={
    if (GlobalParameters.get.readHints == true) {
      val initialPredicates = VerificationHints(HintsSelection.wrappedReadHints(simplifiedClausesForGraph, "unlabeledPredicates").toInitialPredicates.mapValues(_.map(sp(_)).map(VerificationHints.VerifHintInitPred(_)))) //simplify after read
      val initialHintsCollection = new VerificationHintsInfo(initialPredicates, VerificationHints(Map()), VerificationHints(Map()))
      val truePositiveHints = if (new java.io.File(GlobalParameters.get.fileName + "." + "labeledPredicates" + ".tpl").exists == true)
        VerificationHints(HintsSelection.wrappedReadHints(simplifiedClausesForGraph, "labeledPredicates").toInitialPredicates.mapValues(_.map(sp(_)).map(VerificationHints.VerifHintInitPred(_))))
      else HintsSelection.readPredicateLabelFromOneJSON(initialHintsCollection,"templateRelevanceLabel")
      truePositiveHints
    }
    else if (GlobalParameters.get.generateSimplePredicates == true) {
      val (simpleGeneratedPredicates, _, _) =
        HintsSelection.getSimplePredicates(simplifiedClausesForGraph,verbose=false,deduplicate = false)
      transformPredicateMapToVerificationHints(simpleGeneratedPredicates) ++simpHints
    }
    else simpHints
  }


  def checkSolvability(simplePredicatesGeneratorClauses: HornPreprocessor.Clauses, originalPredicates: Map[Predicate, Seq[IFormula]],
                       predicateGen: Dag[DisjInterpolator.AndOrNode[NormClause, Unit]] =>
                         Either[Seq[(Predicate, Seq[Conjunction])],
                           Dag[(IAtom, NormClause)]], counterexampleMethod: CEGAR.CounterexampleMethod.Value,outStream:java.io.OutputStream,
                       fileName: String = "noFileName", moveFileFolder: String = "solvability-timeout", moveFile: Boolean = true,
                       exit: Boolean = true, coefficient: Int = 1): (Int, Map[Predicate, Seq[IFormula]], Either[Map[Predicate, IFormula], Dag[(IAtom, Clause)]]) = {
    println("check solvability using current predicates")
    var solveTime = (GlobalParameters.get.solvabilityTimeout / 1000).toInt
    var satisfiability = false
    val solvabilityTimeoutChecker = clonedTimeChecker(GlobalParameters.get.solvabilityTimeout, coefficient)
    val startTime = currentTimeMillis()
    var cegarGeneratedPredicates: Map[Predicate, Seq[IFormula]] = Map()
    var res: Either[Map[Predicate, IFormula], Dag[(IAtom, Clause)]] = Left(Map())
    try GlobalParameters.parameters.withValue(solvabilityTimeoutChecker) {
      val cegar = Console.withOut(outStream) {new HornPredAbs(simplePredicatesGeneratorClauses,
        originalPredicates, predicateGen,
        counterexampleMethod)}
      solveTime = ((currentTimeMillis - startTime) / 1000).toInt
      res = cegar.result
      res match {
        case Left(a) => {
          satisfiability = true
          cegarGeneratedPredicates = transformPredicatesToCanonical(cegar.relevantPredicates, simplePredicatesGeneratorClauses)
        }
        case Right(b) => {
          println(Console.RED + "-" * 10 + "unsat" + "-" * 10)
          if (moveFile == true)
            HintsSelection.moveRenameFile(GlobalParameters.get.fileName, "../benchmarks/exceptions/unsat/" + fileName)
          if (exit == true)
            sys.exit()
        }
      }

    }
    catch {
      case lazabs.Main.TimeoutException => {
        println(Console.RED + "-" * 10 + moveFileFolder + "-" * 10)
        if (moveFile == true)
          HintsSelection.moveRenameFile(GlobalParameters.get.fileName, "../benchmarks/exceptions/" + moveFileFolder + "/" + fileName)
        if (exit == true)
          sys.exit() //throw TimeoutException
        //solveTime = ((currentTimeMillis - startTime) / 1000).toInt
      }
      case _ => println(Console.RED + "-" * 10 + "solvability-debug" + "-" * 10)
    }
    (solveTime, cegarGeneratedPredicates, res)
  }

  def transformPredicatesToCanonical(lastPredicates:Map[Predicate,Seq[IFormula]],simplePredicatesGeneratorClauses: HornPreprocessor.Clauses):
  Map[Predicate, Seq[IFormula]] ={
    val atomList=(for(c<-simplePredicatesGeneratorClauses;a<-c.allAtoms) yield a.pred->a.args).toMap
    val predicateFromCEGAR = for ((head, preds) <- lastPredicates) yield{
      // transfor Map[relationSymbols.values,ArrayBuffer[RelationSymbolPred]] to Map[Predicate, Seq[IFormula]]
      //val subst = (for ((c, n) <- atomList(head).zipWithIndex) yield (new ConstantTerm(c.toString), IVariable(n))).toMap
      val subst = getSubst(atomList(head),head)
      //val headPredicate=new Predicate(head.name,head.arity) //class Predicate(val name : String, val arity : Int)
      val predicateSequence = for (p <- preds) yield {
        val simplifiedPredicate = spAPI.simplify(p)
        val varPred = ConstantSubstVisitor(simplifiedPredicate, subst) //transform variables to _1,_2,_3...
        varPred
      }
      head -> predicateSequence.distinct.toSeq
    }
    predicateFromCEGAR
  }

  def getSubst(args:Seq[ITerm],pred:Predicate):  Map[ConstantTerm, IVariable] ={
      val sorts = HornPredAbs predArgumentSorts pred
      (for (((IConstant(arg), s), n) <- (args zip sorts).zipWithIndex)
        yield arg -> IVariable(n, s)).toMap
  }

  def measurePredicates(simplePredicatesGeneratorClauses:Clauses,predGenerator:  Dag[DisjInterpolator.AndOrNode[NormClause, Unit]] => Either[Seq[(Predicate, Seq[Conjunction])], Dag[(IAtom, NormClause)]],
                        counterexampleMethod: CEGAR.CounterexampleMethod.Value,outStream:java.io.OutputStream,absBuilder: StaticAbstractionBuilder,
                        predictedPredicates:VerificationHints,
                        fullPredicates:VerificationHints,
                        minimizedPredicates:VerificationHints): Unit ={
    val measurementList = if (GlobalParameters.get.generateTemplates) {
      val predictedTemplatesAbstraction=absBuilder.loopDetector.hints2AbstractionRecord(predictedPredicates)
      val emptyTemplatesAbstraction=absBuilder.loopDetector.hints2AbstractionRecord(VerificationHints(Map()))
      val fullTemplatesAbstraction=absBuilder.loopDetector.hints2AbstractionRecord(fullPredicates)
      val trueTemplatesAbstraction=absBuilder.loopDetector.hints2AbstractionRecord(minimizedPredicates)
      val predictedAbstractionPredGenerator=getPredGenerator(Seq(predictedTemplatesAbstraction),outStream)
      val emptyAbstractionPredGenerator=getPredGenerator(Seq(emptyTemplatesAbstraction),outStream)
      val fullAbstractionPredGenerator=getPredGenerator(Seq(fullTemplatesAbstraction),outStream)
      val trueAbstractionPredGenerator=getPredGenerator(Seq(trueTemplatesAbstraction),outStream)
      val (solveTime,_,_)=HintsSelection.checkSolvability(simplePredicatesGeneratorClauses, Map(), predictedAbstractionPredGenerator, counterexampleMethod, outStream, moveFile = false)
      val measurementAverageTime= if (solveTime<60) 20 else 5
      //run trails to reduce time consumption deviation
      val trial_predicted = measureCEGAR(simplePredicatesGeneratorClauses, Map(), predictedAbstractionPredGenerator, counterexampleMethod,outStream)
      val trial_empty = measureCEGAR(simplePredicatesGeneratorClauses, Map(), emptyAbstractionPredGenerator, counterexampleMethod,outStream)
      val trial_full = measureCEGAR(simplePredicatesGeneratorClauses, Map(), fullAbstractionPredGenerator, counterexampleMethod,outStream)
      val trial_true = if (minimizedPredicates.isEmpty) Seq() else measureCEGAR(simplePredicatesGeneratorClauses, Map(), trueAbstractionPredGenerator, counterexampleMethod,outStream)
      val measurementWithPredictedLabel = averageMeasureCEGAR(simplePredicatesGeneratorClauses, Map(), predictedAbstractionPredGenerator, counterexampleMethod,outStream,measurementAverageTime)
      val measurementWithEmptyLabel = averageMeasureCEGAR(simplePredicatesGeneratorClauses, Map(), emptyAbstractionPredGenerator, counterexampleMethod,outStream,measurementAverageTime)
      val measurementWithFullLabel = averageMeasureCEGAR(simplePredicatesGeneratorClauses, Map(), fullAbstractionPredGenerator, counterexampleMethod,outStream,measurementAverageTime)
      val measurementWithTrueLabel = if (minimizedPredicates.isEmpty) measurementWithEmptyLabel else averageMeasureCEGAR(simplePredicatesGeneratorClauses, Map(), trueAbstractionPredGenerator, counterexampleMethod,outStream,measurementAverageTime)
      Seq(("measurementWithTrueLabel", measurementWithTrueLabel), ("measurementWithFullLabel", measurementWithFullLabel),
        ("measurementWithEmptyLabel", measurementWithEmptyLabel), ("measurementWithPredictedLabel", measurementWithPredictedLabel))
    } else {
      val(solveTime,_,_)=HintsSelection.checkSolvability(simplePredicatesGeneratorClauses, predictedPredicates.toInitialPredicates, predGenerator, counterexampleMethod, outStream, moveFile = false)
      val measurementAverageTime= if (solveTime<60) 20 else 5
      //run trails to reduce time consumption deviation
      val trial_predicted = measureCEGAR(simplePredicatesGeneratorClauses, predictedPredicates.toInitialPredicates, predGenerator, counterexampleMethod,outStream)
      val trial_empty = measureCEGAR(simplePredicatesGeneratorClauses, Map(), predGenerator, counterexampleMethod,outStream)
      val trial_full = measureCEGAR(simplePredicatesGeneratorClauses, fullPredicates.toInitialPredicates, predGenerator, counterexampleMethod,outStream)
      val trial_true = if (minimizedPredicates.isEmpty) Seq() else measureCEGAR(simplePredicatesGeneratorClauses, minimizedPredicates.toInitialPredicates, predGenerator, counterexampleMethod,outStream)
      val measurementWithPredictedLabel = averageMeasureCEGAR(simplePredicatesGeneratorClauses, predictedPredicates.toInitialPredicates, predGenerator, counterexampleMethod,outStream,measurementAverageTime)
      val measurementWithEmptyLabel = averageMeasureCEGAR(simplePredicatesGeneratorClauses, Map(), predGenerator, counterexampleMethod,outStream,measurementAverageTime)
      val measurementWithFullLabel = averageMeasureCEGAR(simplePredicatesGeneratorClauses, fullPredicates.toInitialPredicates, predGenerator, counterexampleMethod,outStream,measurementAverageTime)
      val measurementWithTrueLabel = if (minimizedPredicates.isEmpty) measurementWithEmptyLabel else averageMeasureCEGAR(simplePredicatesGeneratorClauses, minimizedPredicates.toInitialPredicates, predGenerator, counterexampleMethod,outStream,measurementAverageTime)

      Seq(("measurementWithTrueLabel", measurementWithTrueLabel), ("measurementWithFullLabel", measurementWithFullLabel),
        ("measurementWithEmptyLabel", measurementWithEmptyLabel), ("measurementWithPredictedLabel", measurementWithPredictedLabel))
    }

    HintsSelection.writeMeasurementToJSON(measurementList)
  }
  def getExceptionalPredicatedGenerator():  Dag[AndOrNode[NormClause, Unit]] => Either[Seq[(Predicate, Seq[Conjunction])], Dag[(IAtom, NormClause)]] ={
      (x: Dag[DisjInterpolator.AndOrNode[NormClause, Unit]]) =>
        //throw new RuntimeException("interpolator exception")
        throw lazabs.Main.TimeoutException //if catch Counterexample and generate predicates, throw timeout exception
  }
  def getCounterexampleMethod(disjunctive:Boolean):  CEGAR.CounterexampleMethod.Value ={
    if (disjunctive)
      CEGAR.CounterexampleMethod.AllShortest
    else
      CEGAR.CounterexampleMethod.FirstBestShortest
  }
  def getFileName(): String ={
    GlobalParameters.get.fileName.substring(GlobalParameters.get.fileName.lastIndexOf("/")+1,GlobalParameters.get.fileName.length)
  }
  def getMinimumSetPredicates(originalPredicates: Map[Predicate, Seq[IFormula]],simplePredicatesGeneratorClauses:Clauses,
                              exceptionalPredGen: Dag[AndOrNode[NormClause, Unit]] => Either[Seq[(Predicate, Seq[Conjunction])], Dag[(IAtom, NormClause)]]=getExceptionalPredicatedGenerator(),
                              counterexampleMethod: CEGAR.CounterexampleMethod.Value=getCounterexampleMethod(GlobalParameters.get.disjunctive)):
  (Map[Predicate, Seq[IFormula]],Long) ={
    val startTimeForExtraction = System.currentTimeMillis()
    val mainTimeoutChecker = () => {
      if ((currentTimeMillis - startTimeForExtraction) > GlobalParameters.get.mainTimeout)
        throw lazabs.Main.MainTimeoutException //Main.TimeoutException
    }
    val predicatesExtractingBeginTime=System.currentTimeMillis
    var currentInitialPredicates:Map[Predicate,Seq[IFormula]]=sortHints(originalPredicates)
    var pIndex = 0
    if (!originalPredicates.isEmpty) {
      for ((head, preds) <- sortHints(originalPredicates)) {
        //var leftPredicates:Seq[IFormula]=preds
        for (p <- preds) {
          //delete p and useless predicates
          currentInitialPredicates=currentInitialPredicates.transform((k,v)=>if (k.name==head.name) {
            pIndex = v.indexWhere(x=>x.toString==p.toString)
            v.filterNot(x=>x.toString==p.toString)
          } else v)
          //leftPredicates=leftPredicates.filterNot(x=>x.toString==p.toString)
          //currentInitialPredicates=sortHints(currentInitialPredicates.filterNot(_._1==head)  ++ Map(head->leftPredicates))
          val predicateUsefulnessTimeoutChecker=clonedTimeChecker(GlobalParameters.get.threadTimeout)
          try GlobalParameters.parameters.withValue(predicateUsefulnessTimeoutChecker){
            println("----------------------------------- CEGAR --------------------------------------")
            new HornPredAbs(simplePredicatesGeneratorClauses, currentInitialPredicates, exceptionalPredGen,counterexampleMethod).result
            //p is useless
            mainTimeoutChecker()//check total used time for minimizing process
          }catch{
            case lazabs.Main.TimeoutException=>{
              //p is useful,append p to usefulPredicatesInCurrentHead, add it back to left predicates
              //leftPredicates=leftPredicates.:+(p)
              currentInitialPredicates=currentInitialPredicates.transform((k,v)=>if(k.name==head.name) {
                v.take(pIndex).:+(p) ++ v.drop(pIndex)
                //v.:+(p)
              } else v)
            }
            case _=>{throw lazabs.Main.MainTimeoutException}
          }
        }
        //minimumSetPredicate=minimumSetPredicate++Map(head->leftPredicates)
      }
    }
    val predicatesExtractingTime=(System.currentTimeMillis-predicatesExtractingBeginTime)/1000
    (currentInitialPredicates.mapValues(_.map(sp(_)).filter(!_.isTrue).filter(!_.isFalse)),predicatesExtractingTime)
  }


  def conjunctTwoPredicates(A: Map[Predicate, Seq[IFormula]],
                            B: Map[Predicate, Seq[IFormula]]): Map[Predicate, Seq[IFormula]] = {
    if (A.isEmpty || B.isEmpty)
      Map()
    else
      for ((h, preds) <- A; if B.exists(_._1 == h)) yield {
        h -> (for (p <- preds; if containsPred(p, B(h))) yield p)
      }
  }

  def differentTwoPredicated(A: Map[Predicate, Seq[IFormula]],
                             B: Map[Predicate, Seq[IFormula]]): Map[Predicate, Seq[IFormula]] = {
    if (B.isEmpty)
      A
    else
      for ((h, preds) <- A; if B.exists(_._1 == h)) yield {
        h -> (for (p <- preds; if !containsPred(p, B(h))) yield p)
      }
  }
 

  def printPredicateInMapFormat(p:Map[Predicate,Seq[IFormula]],message:String=""): Unit ={
    println(message)
    for ((h,b)<-p) {
      println(h)
      for (bb<-b)
        println(bb)
    }
  }

  def clonedTimeChecker(to: Int, coefficient: Int = 1): GlobalParameters = {
    val startTimePredicateGenerator = currentTimeMillis
    val clonedGlovalParameter = GlobalParameters.get.clone
    clonedGlovalParameter.timeoutChecker = () => {
      //println("time check point:solvabilityTimeout", ((System.currentTimeMillis - startTimePredicateGenerator)/1000).toString + "/" + ((GlobalParameters.get.solvabilityTimeout * 5)/1000).toString)
      if ((currentTimeMillis - startTimePredicateGenerator) > (to * coefficient)) //timeout seconds
        throw lazabs.Main.TimeoutException //Main.TimeoutException
    }
    clonedGlovalParameter
  }

  def simplifyClausesForGraphs(simplifiedClauses:Clauses,hints:VerificationHints): Clauses ={
    //if the body has two same predicates not move this example
    for (c<-simplifiedClauses){
      val pbodyStrings= new MHashSet[String]
      for(pbody<-c.body; if !pbodyStrings.add(pbody.pred.toString)){
          println("pbodyStrings",pbodyStrings)
          println(pbody.pred.toString)
          moveRenameFile(GlobalParameters.get.fileName,"../benchmarks/exceptions/lia-lin-multiple-predicates-in-body/"+getFileName(),"multiple-predicates-in-body")
          sys.exit()
      }
    }
    val uniqueClauses = HintsSelection.distinctByString(simplifiedClauses)
    val (csSimplifiedClauses,_,_)=cs.process(uniqueClauses.distinct,hints)

    val simplePredicatesGeneratorClauses = GlobalParameters.get.hornGraphType match {
      case DrawHornGraph.HornGraphType.hyperEdgeGraph | DrawHornGraph.HornGraphType.equivalentHyperedgeGraph | DrawHornGraph.HornGraphType.concretizedHyperedgeGraph => {
        for(clause<-csSimplifiedClauses) yield clause.normalize()
      }
      case _ => csSimplifiedClauses
    }
    simplePredicatesGeneratorClauses
    //csSimplifiedClauses
  }



  def writeInfoToJSON[A](fields:Seq[(String, A)],suffix:String=""): Unit ={
    val writer = new PrintWriter(new File(GlobalParameters.get.fileName + "." + suffix + ".JSON"))
    writer.write("{\n")
    writeFildToJSON(writer,fields)
    writer.write("}")
    writer.close()
  }

  def writeMeasurementToJSON(measurementList:Seq[(String,Seq[(String, Double)])]): Unit ={
    val writer = new PrintWriter(new File(GlobalParameters.get.fileName + "." + "measurement" + ".JSON"))
    writer.write("{\n")
    for(m<-measurementList.dropRight(1)){
      writer.write(DrawHornGraph.addQuotes(m._1)+": {\n")
      writeFildToJSON(writer,m._2)
      writer.write("},\n")
    }
    val last=measurementList.last
    writer.write(DrawHornGraph.addQuotes(last._1)+": {\n")
    writeFildToJSON(writer,last._2)
    writer.write("}\n")

    writer.write("\n}")
    writer.close()
  }
  def writeFildToJSON[A](writer:PrintWriter,fields:Seq[(String, A)]): Unit ={
    for (field<-fields.dropRight(1)){
      writer.write(DrawHornGraph.addQuotes(field._1)+":"+DrawHornGraph.addQuotes(field._2.toString)+",\n")
    }
    val last=fields.last
    writer.write(DrawHornGraph.addQuotes(last._1)+":"+DrawHornGraph.addQuotes(last._2.toString)+"\n")
  }

  def averageMeasureCEGAR(simplePredicatesGeneratorClauses: HornPreprocessor.Clauses,initialHints: Map[Predicate, Seq[IFormula]],predicateGenerator :  Dag[DisjInterpolator.AndOrNode[NormClause, Unit]]=>
    Either[Seq[(Predicate, Seq[Conjunction])],
      Dag[(IAtom,NormClause)]],counterexampleMethod : CEGAR.CounterexampleMethod.Value =
  CEGAR.CounterexampleMethod.FirstBestShortest,outStream:java.io.OutputStream,adverageTime:Int=20): Seq[Tuple2[String,Double]] ={
    val avg=(for (i<-Range(0,adverageTime,1)) yield{
      val mList=measureCEGAR(simplePredicatesGeneratorClauses,initialHints,predicateGenerator,counterexampleMethod,outStream)
      for (x<-mList) yield x._2
    }).transpose.map(_.sum/adverageTime)
    val measurementNameList=Seq("timeConsumptionForCEGAR","itearationNumber","generatedPredicateNumber","averagePredicateSize","predicateGeneratorTime","averagePredicateSize")
    for((m,name)<-avg.zip(measurementNameList)) yield Tuple2(name,m)
  }

  def measureCEGAR(simplePredicatesGeneratorClauses: HornPreprocessor.Clauses,
                   initialHints: Map[Predicate, Seq[IFormula]],
                   predicateGenerator :  Dag[DisjInterpolator.AndOrNode[NormClause, Unit]] =>
    Either[Seq[(Predicate, Seq[Conjunction])],
      Dag[(IAtom, NormClause)]],counterexampleMethod : CEGAR.CounterexampleMethod.Value =
  CEGAR.CounterexampleMethod.FirstBestShortest,outStream:java.io.OutputStream): Seq[Tuple2[String,Double]] ={
    val startCEGARTime=currentTimeMillis()

    val Cegar = Console.withOut(outStream){
      new HornPredAbs(simplePredicatesGeneratorClauses,
        initialHints,
        predicateGenerator,
        counterexampleMethod).cegar
    }
    val timeConsumptionForCEGAR=(currentTimeMillis()-startCEGARTime)
    //println(Console.GREEN + "timeConsumptionForCEGAR (ms)",timeConsumptionForCEGAR)
    val measurementList:Seq[Tuple2[String,Double]]=Seq(Tuple2("timeConsumptionForCEGAR",timeConsumptionForCEGAR),Tuple2("itearationNumber",Cegar.iterationNum),
      Tuple2("generatedPredicateNumber",Cegar.generatedPredicateNumber),Tuple2("averagePredicateSize",Cegar.averagePredicateSize),
      Tuple2("predicateGeneratorTime",Cegar.predicateGeneratorTime))

    measurementList
  }

  def writePredicatesToFiles(unlabeledPredicates:VerificationHints,labeledPredicates:VerificationHints,fileName:String=GlobalParameters.get.fileName): Unit ={
    Console.withOut(new java.io.FileOutputStream(fileName+".unlabeledPredicates.tpl")) {
      AbsReader.printHints(unlabeledPredicates)}
    Console.withOut(new java.io.FileOutputStream(fileName+".labeledPredicates.tpl")) {
      AbsReader.printHints(labeledPredicates)}
  }
  def writePredicateDistributionToFiles(initialPredicates:VerificationHints,selectedPredicates:VerificationHints,
                                        labeledPredicates:VerificationHints,unlabeledPredicates:VerificationHints,simpleGeneratedPredicates:VerificationHints,
                                        constraintPredicates:VerificationHints,pairwisePredicate:VerificationHints,
                                        clauses:Clauses,writeToFile:Boolean=true):  Seq[(String, Int)] ={

    val guardMap=
      (for (clause<-clauses) yield{
        val (dataflow,guardList)=HintsSelection.getDataFlowAndGuardWitoutPrint(clause)
        clause->guardList
      }).toMap

    var currentGuardList:Seq[IFormula]=Seq()

    for ((clause,guardList)<-guardMap;atom<-clause.allAtoms){
      val replacedGuardSet=for (g<-guardList) yield{
        //val subst=(for(c<-SymbolCollector.constants(g);(arg,n)<-a.args.zipWithIndex ; if c.name==arg.toString)yield  c->IVariable(n)).toMap
        val subst = getSubst(atom.args,atom.pred)
        predicateQuantify(ConstantSubstVisitor(g,subst))
      }
      currentGuardList=nonredundantSet(currentGuardList,replacedGuardSet)
    }

    val positiveGaurd=for ((k,v)<-selectedPredicates.toInitialPredicates) yield{
      for (vv<-v;if HintsSelection.containsPred(vv,currentGuardList)) yield {k->vv}
    }
    val guardSize=currentGuardList.size//guardMap.values.flatten.size
    val redundantGuardSize=guardMap.values.flatten.size
//    println("guardSize",guardSize)
//    println("positiveGaurd",positiveGaurd.size)


    val initialPredicateSize=initialPredicates.toInitialPredicates.values.flatten.size
    val positiveSimpleGeneratedPredicates=conjunctTwoPredicates(simpleGeneratedPredicates.toInitialPredicates,selectedPredicates.toInitialPredicates)
    val positiveConstraintPredicates = conjunctTwoPredicates(constraintPredicates.toInitialPredicates,selectedPredicates.toInitialPredicates)
    val positiveConstraintPredicatesSize = positiveConstraintPredicates.values.flatten.size
    val positivePairwisePredicateTemp = conjunctTwoPredicates(pairwisePredicate.toInitialPredicates,selectedPredicates.toInitialPredicates)
    val positivePairwisePredicate= for ((k,v)<-positivePairwisePredicateTemp) yield {k->nonredundantSet(Seq(),v)}
    val positivePairwisePredicateSize=positivePairwisePredicate.values.flatten.size

    val predicatesFromCEGAR = differentTwoPredicated(initialPredicates.toInitialPredicates,simpleGeneratedPredicates.toInitialPredicates)
    val predicatesFromCEGARSize = predicatesFromCEGAR.values.flatten.size
    val positivePredicatesFromCEGAR = conjunctTwoPredicates(predicatesFromCEGAR,selectedPredicates.toInitialPredicates)
    val positivePredicatesFromCEGARSize=positivePredicatesFromCEGAR.values.flatten.size

    val differntiatedPairwisePredicates=differentTwoPredicated(simpleGeneratedPredicates.toInitialPredicates,constraintPredicates.toInitialPredicates)
    val differntiatedPairwisePredicatesSize=differntiatedPairwisePredicates.values.flatten.size
    val positiveDifferntiatedPairwisePredicates=conjunctTwoPredicates(differntiatedPairwisePredicates,selectedPredicates.toInitialPredicates)
    val positiveDifferntiatedPairwisePredicatesSize=positiveDifferntiatedPairwisePredicates.values.flatten.size

    val repeatativePairwisePredicates=differentTwoPredicated(pairwisePredicate.toInitialPredicates,differntiatedPairwisePredicates)
    val repeatativePairwisePredicatesSize=repeatativePairwisePredicates.values.flatten.size

    val simpleGeneratedPredicatesSize=simpleGeneratedPredicates.toInitialPredicates.values.flatten.size
    val constraintPredicatesSize=constraintPredicates.toInitialPredicates.values.flatten.size
    val pairwisePredicatesSize=pairwisePredicate.toInitialPredicates.values.flatten.size

    val fields=Seq(
      ("initialPredicates (initial predicatesFromCEGAR, heuristic simpleGeneratedPredicates)",initialPredicateSize),
      ("minimizedPredicates (initialPredicates go through CEGAR Filter)",selectedPredicates.toInitialPredicates.values.flatten.size),
      ("simpleGeneratedPredicates",simpleGeneratedPredicatesSize),
      ("positiveSimpleGeneratedPredicates",positiveSimpleGeneratedPredicates.values.flatten.size),
      ("constraintPredicates",constraintPredicatesSize),
      ("positiveConstraintPredicates",positiveConstraintPredicatesSize),
      ("differentiatedPairwisePredicate",differntiatedPairwisePredicatesSize),
      ("positiveDifferntiatedPairwisePredicatesSize",positiveDifferntiatedPairwisePredicatesSize),
      ("redundantPairwisePredicate",pairwisePredicatesSize),
      ("positiveRedundantPairwisePredicate",positivePairwisePredicateSize),
      ("total guards",guardSize),
      ("redundant guards",redundantGuardSize),
      ("positiveGuards",positiveGaurd.size),
      ("predicatesFromCEGAR",predicatesFromCEGARSize),
      ("positivePredicatesFromCEGAR",positivePredicatesFromCEGARSize),
      ("unlabeledPredicates",unlabeledPredicates.toInitialPredicates.values.flatten.size),
      ("labeledPredicates",labeledPredicates.toInitialPredicates.values.flatten.size)
    )
    if(writeToFile){
      writeInfoToJSON(fields,"predicateDistribution")//write to json
      val writer=new PrintWriter(new File(GlobalParameters.get.fileName + ".predicateDistribution"))//write to file
      writer.println("vary predicates: " + (if(GlobalParameters.get.varyGeneratedPredicates==true) "on" else "off"))
      writer.println("Predicate distributions: ")
      writer.println("initialPredicates (initial predicatesFromCEGAR, heuristic simpleGeneratedPredicates):"+initialPredicateSize.toString)
      writer.println("minimizedPredicates (initialPredicates go through CEGAR Filter):"+selectedPredicates.toInitialPredicates.values.flatten.size.toString)
      writer.println("simpleGeneratedPredicates:"+simpleGeneratedPredicatesSize.toString)
      writer.println("positiveSimpleGeneratedPredicates:"+positiveSimpleGeneratedPredicates.values.flatten.size.toString)
      writer.println("   constraintPredicates:"+constraintPredicatesSize.toString)
      writer.println("       positiveConstraintPredicates:"+positiveConstraintPredicatesSize.toString)
      writer.println("       negativeConstraintPredicates:"+ (constraintPredicatesSize - positiveConstraintPredicatesSize).toString)

      writer.println("   differentiatedPairwisePredicate:"+differntiatedPairwisePredicatesSize.toString)
      writer.println("       positiveDifferentiatedPairwisePredicate:"+positiveDifferntiatedPairwisePredicatesSize.toString)
      writer.println("       negativeDifferentiatedPairwisePredicate:"+ (differntiatedPairwisePredicatesSize - positiveDifferntiatedPairwisePredicatesSize).toString)

      writer.println("   redundantPairwisePredicate:"+pairwisePredicatesSize.toString)
      writer.println("       positiveRedundantPairwisePredicate:"+positivePairwisePredicateSize.toString)
      writer.println("       negativeRedundantPairwisePredicate:"+ (pairwisePredicate.toInitialPredicates.values.flatten.size - positivePairwisePredicateSize).toString)

      //writer.println("   repeatativePairwisePredicatesSize:"+repeatativePairwisePredicatesSize.toString)

      writer.println("predicatesFromCEGAR(initialPredicates - simpleGeneratedPredicates):"+predicatesFromCEGARSize.toString)
      writer.println("       positivePredicatesFromCEGAR:"+positivePredicatesFromCEGARSize.toString)
      writer.println("       negativePredicatesFromCEGAR:"+(predicatesFromCEGAR.values.flatten.size-positivePredicatesFromCEGARSize).toString)

      writer.println("total guards:"+guardSize.toString)
      writer.println("       redundantGuards:"+redundantGuardSize.toString)
      writer.println("       positiveGuards:"+positiveGaurd.size.toString)
      writer.println("       negativeGuards:"+(guardSize-positiveGaurd.size).toString)

      writer.println("unlabeledPredicates:"+unlabeledPredicates.toInitialPredicates.values.flatten.size.toString)
      writer.println("labeledPredicates:"+labeledPredicates.toInitialPredicates.values.flatten.size.toString)
      writer.close()
    }




    if (GlobalParameters.get.debugLog==true){
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".simpleGenerated.tpl")) {
        AbsReader.printHints(simpleGeneratedPredicates)}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".constraintPredicates.tpl")) {
        AbsReader.printHints(constraintPredicates)}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".positiveConstraintPredicates.tpl")) {
        AbsReader.printHints(transformPredicateMapToVerificationHints(positiveConstraintPredicates))}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".argumentConstantEqualPredicate.tpl")) {
        AbsReader.printHints(pairwisePredicate)}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".positiveArgumentConstantEqualPredicate.tpl")) {
        AbsReader.printHints(transformPredicateMapToVerificationHints(positivePairwisePredicate))}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".initial.tpl")) {
        AbsReader.printHints(initialPredicates)}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".predicatesFromCEGAR.tpl")) {
        AbsReader.printHints(transformPredicateMapToVerificationHints(predicatesFromCEGAR))}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".positivePredicatesFromCEGAR.tpl")) {
        AbsReader.printHints(transformPredicateMapToVerificationHints(positivePredicatesFromCEGAR))}
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName+".selected.tpl")) {
        AbsReader.printHints(selectedPredicates)}
    }
    fields
  }


  def intersectPredicatesByString(first: Map[Predicate, Seq[IFormula]],
                                  second:Map[Predicate, Seq[IFormula]]): Map[Predicate, Seq[IFormula]] ={
    for ((fk,fv)<-first;(sk,sv)<-second;if fk.equals(sk)) yield {
      fk->(for (p<-fv if sv.map(_.toString).contains(p.toString)) yield p)
    }
  }

  //associativity
  //replace a-b to -1*x + b
  def varyPredicateWithOutLogicChanges(f: IFormula): IFormula =
    f match {
      case Eq(a, b) => {
        //println(a.toString,"=",b.toString)
        Eq(varyTerm(a), varyTerm(b))
      }
      case Geq(a, b) => {
        //println(a.toString,">=",b.toString)
        Geq(varyTerm(a), varyTerm(b))
      }
      case INot(subformula) => INot(varyPredicateWithOutLogicChanges(subformula))
      case IQuantified(quan, subformula) => IQuantified(quan, varyPredicateWithOutLogicChanges(subformula))
      case IBinFormula(j, f1, f2) => IBinFormula(j, varyPredicateWithOutLogicChanges(f1), varyPredicateWithOutLogicChanges(f2))
      case _ => f
    }


  def varyTerm(e: ITerm): ITerm =
    e match {
      case IPlus(t1, t2) => IPlus(varyTerm(t2), varyTerm(t1))
      case Difference(t1, t2) => IPlus(varyTerm(t1), varyTerm(-1 * t2))
      //case ITimes(coeff, subterm) => ITimes(coeff, varyTerm(subterm))
      case _ => e
    }


  def varyPredicates(optimizedPredicate: Map[Predicate, Seq[IFormula]],verbose:Boolean=false): Map[Predicate, Seq[IFormula]] ={
    val transformedPredicates=optimizedPredicate.mapValues(_.map(HintsSelection.varyPredicateWithOutLogicChanges(_)).map(sp(_)))
    val mergedPredicates= mergePredicateMaps(transformedPredicates,optimizedPredicate)
    if (verbose==true){
      println("predicates before transform")
      transformPredicateMapToVerificationHints(optimizedPredicate).pretyPrintHints()
      //optimizedPredicate.foreach(k=>{println(k._1);k._2.foreach(println)})
      println("transformed predicates")
      transformPredicateMapToVerificationHints(transformedPredicates).pretyPrintHints()
      //transformedPredicates.foreach(k=>{println(k._1);k._2.foreach(println)})
      println("merged predicates")
      transformPredicateMapToVerificationHints(mergedPredicates).pretyPrintHints()
      //mergedPredicates.foreach(k=>{println(k._1);k._2.foreach(println)})
    }
    mergedPredicates.mapValues(distinctByString(_)).mapValues(_.filterNot(_.isFalse).filterNot(_.isTrue))
  }


  def readPredictedHints(simplifiedClausesForGraph: Clauses, fullInitialPredicates: VerificationHints): VerificationHints = {
    val predictedHints = {
      if (new java.io.File(GlobalParameters.get.fileName + "." + "predictedHints" + ".tpl").exists == true) {
        VerificationHints(HintsSelection.wrappedReadHints(simplifiedClausesForGraph, "predictedHints").toInitialPredicates.mapValues(_.map(sp(_)).map(VerificationHints.VerifHintInitPred(_))))
      }
      else {
        val initialHintsCollection = new VerificationHintsInfo(fullInitialPredicates, VerificationHints(Map()), VerificationHints(Map()))
        if (GlobalParameters.get.separateByPredicates == true)
          HintsSelection.readPredicateLabelFromMultipleJSON(initialHintsCollection, simplifiedClausesForGraph, "predictedLabel")
        else
          HintsSelection.readPredicateLabelFromOneJSON(initialHintsCollection, "predictedLabel")
      }
    }
    if (GlobalParameters.get.debugLog == true)
      Console.withOut(new java.io.FileOutputStream(GlobalParameters.get.fileName + ".predictedHints.tpl")) {
        AbsReader.printHints(predictedHints)
      }
    predictedHints
  }

  def detectIfAJSONFieldExists(readLabel: String = "predictedLabel",fileName:String=GlobalParameters.get.fileName): Boolean ={
    val input_file = fileName+".hyperEdgeHornGraph.JSON"
    val json_content = scala.io.Source.fromFile(input_file).mkString
    val json_data = Json.parse(json_content)
    try{(json_data \ readLabel).validate[Array[Int]] match {
      case JsSuccess(templateLabel,_)=> true}
    }catch {
      case _=>{
        println("read json file field error")
        false}
    }
  }

  def readPredicateLabelFromMultipleJSON(initialHintsCollection: VerificationHintsInfo,
                                         simplifiedClausesForGraph: Clauses,readLabel:String="predictedLabel"): VerificationHints ={
    //restore predicates from separated files
    val emptyMap:Map[Predicate,Seq[VerifHintElement]]=Map()
    val totalPredicateNumber=initialHintsCollection.initialHints.totalPredicateNumber
    val batch_size=getBatchSize(simplifiedClausesForGraph,totalPredicateNumber)
    val trunk=(totalPredicateNumber/batch_size.toFloat).ceil.toInt
    val fimeNameList= for (t<- (0 until trunk))yield{GlobalParameters.get.fileName+"-"+t.toString}
    val allPositiveList=(for (fileName<-fimeNameList)yield{
      if(new java.io.File(fileName+".hyperEdgeHornGraph.JSON").exists == true){
        val currenInitialHints=wrappedReadHints(simplifiedClausesForGraph,"unlabeledPredicates",fileName).predicateHints.toSeq sortBy (_._1.name)
        readPredicateLabelFromJSON(fileName, currenInitialHints, readLabel)
      }else{
        emptyMap
      }

    }).reduceLeft(mergePredicateMaps(_,_))
    VerificationHints(allPositiveList)
  }

  def readPredicateLabelFromOneJSON(initialHintsCollection: VerificationHintsInfo,readLabel:String="predictedLabel"): VerificationHints ={
    val initialHints=initialHintsCollection.initialHints.predicateHints.toSeq sortBy (_._1.name)
    VerificationHints(readPredicateLabelFromJSON(GlobalParameters.get.fileName, initialHints, readLabel))
  }

  def readPredicateLabelFromJSON(fileName:String, initialHints:Seq[(Predicate, Seq[VerifHintElement])],
                                 readLabel:String="predictedLabel"): Map[Predicate, Seq[VerifHintElement]]={
    val input_file=fileName+".hyperEdgeHornGraph.JSON"
    if(detectIfAJSONFieldExists(readLabel,fileName)==true){
      val json_content = scala.io.Source.fromFile(input_file).mkString
      val json_data = Json.parse(json_content)
      val predictedLabel= (json_data \ readLabel).validate[Array[Int]] match {
        case JsSuccess(templateLabel,_)=> templateLabel
      }

      val mapLengthList=for ((k,v)<-initialHints) yield v.length
      var splitTail=predictedLabel
      val splitedPredictedLabel = for(l<-mapLengthList) yield {
        val temp=splitTail.splitAt(l)._1
        splitTail=splitTail.splitAt(l)._2
        temp
      }

      val labeledPredicates=(for (((k,v),label)<-initialHints zip splitedPredictedLabel) yield {
        k-> (for ((p,l)<-v zip label if l==1) yield p) //match labels with predicates
      }).filterNot(_._2.isEmpty) //delete empty head
      if(GlobalParameters.get.debugLog==true){
        println("input_file",input_file)
        println("predictedLabel",predictedLabel.toList.length,predictedLabel.toList)
        for (x<-splitedPredictedLabel)
          println(x.toSeq,x.size)
        println("--------Filtered initial predicates---------")
        for((k,v)<-labeledPredicates) {
          println(k)
          for(p<-v)
            println(p)
        }
      }
      labeledPredicates.toMap
    }else Map()

  }

  def wrappedReadHints(simplifiedClausesForGraph:Seq[Clause],hintType:String="",fileName:String=GlobalParameters.get.fileName):VerificationHints={
    val name2Pred =
      (for (Clause(head, body, _) <- simplifiedClausesForGraph.iterator;
            IAtom(p, _) <- (head :: body).iterator)
        yield (p.name -> p)).toMap
    HintsSelection.readHints(fileName+"."+hintType+".tpl", name2Pred)
  }

  def readHints(filename : String,
                name2Pred : Map[String, Predicate])
  : VerificationHints = filename match {
    case "" =>
      EmptyVerificationHints
    case hintsFile => {
      val reader = new AbsReader (
        new java.io.BufferedReader (
          new java.io.FileReader(hintsFile)))
      val hints =
        (for ((predName, hints) <- reader.allHints.toSeq.sortBy(_._1).iterator;
              pred = name2Pred get predName;
              if {
                if (pred.isDefined) {
                  if (pred.get.arity != reader.predArities(predName))
                    throw new Exception(
                      "Hints contain predicate with wrong arity: " +
                        predName + " (should be " + pred.get.arity + " but is " +
                        reader.predArities(predName) + ")")
                } else {
                  Console.err.println(
                    "   Ignoring hints for " + predName + "\n")
                }
                pred.isDefined
              }) yield {
          (pred.get, hints)
        }).toMap
      VerificationHints(hints)
    }
  }

  def predicateQuantify(predicate: IFormula): IFormula = {
    val constants = SymbolCollector.constants(predicate)
    if (constants.isEmpty)
      predicate
    else {
      IExpression.quanConsts(Quantifier.EX, constants, predicate)
    }
  }

  def clauseConstraintQuantify(clause: Clause): IFormula = {
    //println(Console.BLUE + "clauseConstraintQuantify begin")
    SimpleAPI.withProver { p =>
      p.scope {
        p.addConstantsRaw(clause.constants.toSeq.sortBy(_.toString()))
        val constants = for (a <- clause.allAtoms; c <- SymbolCollector.constants(a)) yield c
        try {
          p.withTimeout(5*1000) {
            p.projectEx(clause.constraint, constants)
          }
        }
        catch {
          case SimpleAPI.TimeoutException => clause.constraint
          case _ => clause.constraint
        }
      }
    }
  }
  def getSimplifiedClauses(clause: Clause): Clause = {
    val simplifyedConstraints = clauseConstraintQuantify(clause)
    //println(Console.BLUE + "clauseConstraintQuantify finished")
    Clause(clause.head, clause.body, simplifyedConstraints)
  }
  def getDataFlowAndGuardWitoutPrint(clause: Clause): (Seq[IFormula], Seq[IFormula]) ={
    val normalizedClause=clause.normalize()
    //replace intersect arguments in body and add arg=arg' to constrains
    val replacedClause = DrawHyperEdgeHornGraph.replaceIntersectArgumentInBody(normalizedClause)
    val simplifyedClauses=getSimplifiedClauses(replacedClause)
    val finalSimplifiedClauses=simplifyedClauses //change to replacedClause see not simplified constraints
    var dataflowList = Set[IFormula]()
    var guardList = Set[IFormula]()
    val bodySymbolsSet = (for (body <- finalSimplifiedClauses.body; arg <- body.args) yield arg).toSet
    var counter=0
    for (x <- finalSimplifiedClauses.head.args) {
      val SE = IExpression.SymbolEquation(x)
      //for (f <- LineariseVisitor(finalSimplifiedClauses.constraint, IBinJunctor.And)) counter=counter+1
      for (f <- LineariseVisitor(finalSimplifiedClauses.constraint, IBinJunctor.And)) f match {
        case SE(coefficient, rhs) if !coefficient.isZero=> { //<1>
          //counter=counter+1
          if (!(dataflowList.map(_.toString) contains f.toString) // f is not in dataflowList
            && SymbolCollector.constants(rhs).map(_.toString).subsetOf(bodySymbolsSet.map(_.toString)) // <2>
          ) {
            // discovered dataflow from body to x
            dataflowList += f
          }else{
            guardList+=f
          }
        }
        case _ => { guardList+=f//println(Console.BLUE + f)
          //counter=counter+1
        }
      }
    }
    //println("counter",counter)
    //val guardList = (for (f <- LineariseVisitor(finalSimplifiedClauses.constraint, IBinJunctor.And)) yield f).diff(for (df <- dataflowList) yield df)//.map(sp(_))
    val dataFlowSeq = dataflowList.toSeq.sortBy(_.toString)
    val guardSeq = guardList.toSeq.sortBy(_.toString)
    (dataFlowSeq, guardSeq)
  }
  def getSimplePredicates( simplePredicatesGeneratorClauses: HornPreprocessor.Clauses,
                           verbose:Boolean=false,deduplicate:Boolean=true):  (Map[Predicate, Seq[IFormula]],Map[Predicate, Seq[IFormula]],Map[Predicate, Seq[IFormula]]) ={
    println("ap.CmdlMain.version",ap.CmdlMain.version)
    println("begin generating initial predicates")
    val generatePredicatesBeginTime=System.currentTimeMillis
    var constraintPredicates:Map[Predicate,Seq[IFormula]]=Map()
    var pairWiseVariablePredicates:Map[Predicate,Seq[IFormula]]=Map()
    var predicateMap:Map[Predicate,Seq[IFormula]]=Map()

    def addNewPredicateList(pMap: Map[Predicate, Seq[IFormula]], pred: Predicate, newList: Seq[IFormula]): Map[Predicate, Seq[IFormula]] = {
      val distinctedNewList=distinctByString(newList)
      var startMap:Map[Predicate,Seq[IFormula]]=pMap
      if (pMap.keys.map(_.name).toSeq.contains(pred.name)) {
        if(!distinctedNewList.isEmpty) {
          val differntiatedNewList=distinctedNewList.diff(pMap(pred)) //differentiate existed predicates
          //println("startMap(pred)",startMap(pred).size)
          //println("differntiatedNewList",differntiatedNewList.size)
          startMap = pMap.updated(pred, nonredundantSet(pMap(pred), differntiatedNewList))
          //println("startMap(pred) after",startMap(pred).size)
        }
      } else {
        startMap += (pred -> distinctedNewList)
      }
      startMap
    }
    //generate predicates from constraint
    val generatePredicatesFromConstraintBeginTime=System.currentTimeMillis
    for (clause<-simplePredicatesGeneratorClauses;atom<-clause.allAtoms){
      val subst = getSubst(atom.args,atom.pred)
      val argumentReplacedConstraints= ConstantSubstVisitor(clause.constraint,subst)
      val quantifiedConstraints=predicateQuantify(argumentReplacedConstraints)
      val simplifiedConstraint= {
      try{
        spAPI.withTimeout(1000){
          spAPI.simplify(quantifiedConstraints)
        }
      }
      catch {case _=>quantifiedConstraints}// new IBoolLit(true)
      }
      val variablesInConstraint = SymbolCollector.variables(simplifiedConstraint)

      val freeVariableReplacedPredicates= {
        if(clause.body.map(_.toString).contains(atom.toString)) {
          (for (p<-LineariseVisitor(simplifiedConstraint.unary_!,IBinJunctor.And)) yield p) ++ (for (p<-LineariseVisitor(simplifiedConstraint,IBinJunctor.And)) yield p.unary_!)
        } else {
          LineariseVisitor(simplifiedConstraint,IBinJunctor.And)
        }
      }.map(spAPI.simplify(_)).filterNot(_.isFalse).filterNot(_.isTrue)
//      println("--"+tempCounter+"--")
//      tempCounter=tempCounter+1
//      println("clause",clause.toPrologString)
//      val argsType=for(a<-atom.args) yield if(isArgBoolean(a)) "Bool" else "Int"
//      println("atom",atom,"type",argsType)
//      println("argumentReplacedPredicates",argumentReplacedPredicates)
//      println("quantifiedConstraints",quantifiedConstraints)
      println("simplifiedConstraint",simplifiedConstraint)
//      println("freeVariableReplacedPredicates",freeVariableReplacedPredicates)
      if (atom.args.size>=variablesInConstraint.size){
        if (constraintPredicates.keys.map(_.name).toSeq.contains(atom.pred.name))
            constraintPredicates=constraintPredicates.updated(atom.pred,(constraintPredicates(atom.pred)++freeVariableReplacedPredicates).distinct)
         else
            constraintPredicates=constraintPredicates++Map(atom.pred->freeVariableReplacedPredicates)
      }
    }
    val generatePredicatesFromConstraintTime=(System.currentTimeMillis-generatePredicatesFromConstraintBeginTime)/1000
    //HintsSelection.transformPredicateMapToVerificationHints(constraintPredicates).pretyPrintHints()
    println(Console.BLUE + "number of predicates from constraint:"+(for (k<-constraintPredicates) yield k._2).flatten.size,"time consumption:"+generatePredicatesFromConstraintTime+" s")

    //generate pairwise predicates from constraint
    val generatePredicatesFromPairwiseBeginTime=System.currentTimeMillis
    val integerConstantVisitor = new LiteralCollector
    val variableConstantPairs=Seq((1,1),(-1,1)).map(x=>Tuple2(IdealInt(x._1),IdealInt(x._2)))//(-1,-1), (1,1),(-1,1),(1,-1) is redundant for constant=0 or +-constant
    val constantList= (for(clause <- simplePredicatesGeneratorClauses) yield {
      integerConstantVisitor.visitWithoutResult(clause.constraint,()) //collect integer constant in clause
      val constantListTemp = (integerConstantVisitor.literals.toSeq ++ Seq(IdealInt(0),IdealInt(-1),IdealInt(1)) ++ (for (x<-integerConstantVisitor.literals.toSeq ) yield x.*(-1)))
      integerConstantVisitor.literals.clear()
      constantListTemp
    }).flatten.map(_.intValue).distinct.map(IdealInt(_))
    val uniqueAtoms= (for(clause <- simplePredicatesGeneratorClauses; atom <- clause.allAtoms) yield atom).groupBy(_.pred.name).map(_._2.head)
    for (atom <- uniqueAtoms; if !atom.args.isEmpty){
      val pairVariables=(for ((arg, n) <- atom.args.zipWithIndex) yield (arg,n)).combinations(2).map(listToTuple2(_))
      val preList=
      if (pairVariables.isEmpty) {
        (for ((arg, n) <- atom.args.zipWithIndex; if !isArgBoolean(arg)) yield argumentEquationGenerator(n, constantList)).flatten
      } else {
        //(1,0),(0,1) (-1,0),(0,-1) is redundant for combination
        val singleVariablePredicates= (for ((arg, n) <- atom.args.zipWithIndex;if !isArgBoolean(arg)) yield argumentEquationGenerator(n, constantList)).flatten
        val pairwisePredicates=(for ((v1,v2)<-pairVariables;if !isArgBoolean(v1._1) && !isArgBoolean(v2._1)) yield pairWiseEquationGenerator(v1,v2,variableConstantPairs,constantList)).flatten.toSeq
        singleVariablePredicates++pairwisePredicates
      }

      if (pairWiseVariablePredicates.keys.map(_.name).toSeq.contains(atom.pred.name))
        pairWiseVariablePredicates=pairWiseVariablePredicates.updated(atom.pred,(pairWiseVariablePredicates(atom.pred)++preList).distinct)
      else
        pairWiseVariablePredicates=pairWiseVariablePredicates++Map(atom.pred->preList)
    }
    val generatePredicatesFromPairwiseTime=(System.currentTimeMillis-generatePredicatesFromPairwiseBeginTime)/1000
    println(Console.BLUE + "number of pairwise predicates:"+(for (k<-pairWiseVariablePredicates) yield k._2).flatten.size,"time consumption:"+generatePredicatesFromPairwiseTime+" s")

//    val variedPredicates=
//      if(GlobalParameters.get.varyGeneratedPredicates==true)
//        HintsSelection.varyPredicates(predicateMap)
//      else
//        predicateMap
    val finalGeneratedPredicates=
      if (deduplicate==true) {
        //add constraint predicates to predicateMap
        val deduplicateConstraintPredicateBeginTime=System.currentTimeMillis
        for ((k,v)<-constraintPredicates){
          predicateMap=addNewPredicateList(predicateMap,k,v)
        }
        val deduplicateConstraintPredicateTime=(System.currentTimeMillis-deduplicateConstraintPredicateBeginTime)/1000
        println(Console.BLUE +"adding and deduplicating predicates from constraint to initial predicates, time consumption:"+deduplicateConstraintPredicateTime+" s")
        println(Console.BLUE + "number of predicates in initial predicates:"+(for (k<-predicateMap) yield k._2).flatten.size)
        println("-"*10)
        //add pairwise variable to predicateMap
        val deduplicatePairwisePredicateBeginTime=System.currentTimeMillis
        for ((k,v)<-pairWiseVariablePredicates){
          predicateMap=addNewPredicateList(predicateMap,k,v)
        }
        val deduplicatePairwisePredicateTime=(System.currentTimeMillis-deduplicatePairwisePredicateBeginTime)/1000
        println(Console.BLUE +"adding and deduplicating predicates from pairwise predicates to initial predicates, time consumption:"+deduplicatePairwisePredicateTime+" s")
        //println(Console.BLUE + "number of predicates in initial predicates:"+(for (k<-predicateMap) yield k._2).flatten.size)
        predicateMap
      } else {
        mergePredicateMaps(constraintPredicates,pairWiseVariablePredicates)
      }
    println(Console.BLUE + "number of predicates in initial predicates:"+finalGeneratedPredicates.values.flatten.size)

    val initialPredicateGeneratingTime=(System.currentTimeMillis-generatePredicatesBeginTime)/1000
    println("end generating initial predicates, time consumption",initialPredicateGeneratingTime,"(s)")
    if (verbose==true){
      println("--------predicates from constrains---------")
      for((k,v)<-constraintPredicates;p<-v) println(k,p)
//      println("--------predicates from constant and argumenteEqation---------")
//      for(cc<-argumentConstantEqualPredicate; b<-cc._2) println(cc._1,b)
      println("--------predicates from pairwise variables---------")
      for(cc<-pairWiseVariablePredicates; b<-cc._2) println(cc._1,b)
//      println("--------predicates from merged---------")
//      for(cc<-merge; b<-cc._2) println(cc._1,b)
      println("--------all generated predicates---------")
      for((k,v)<-finalGeneratedPredicates;(p,i)<-v.zipWithIndex) println(k,i,p)
    }
    (finalGeneratedPredicates,constraintPredicates,pairWiseVariablePredicates)
  }
  def mergePredicateMaps[A](first:Map[Predicate,Seq[A]],second:Map[Predicate,Seq[A]]): Map[Predicate,Seq[A]] ={
    if (first.isEmpty)
      second
    else if (second.isEmpty)
      first
    else
      (first.toSeq ++ second.toSeq).groupBy(_._1).map{case(k, v) => k -> v.flatMap(_._2)}
  }

  def distinctByString[A](formulas:Seq[A]): Seq[A] ={
    val FormulaStrings = new MHashSet[String]
    val uniqueFormulas= formulas filter {f => FormulaStrings.add(f.toString)} //de-duplicate formulas
    uniqueFormulas
  }

  def nonredundantSet(startSet : Seq[IFormula], newElements : Seq[IFormula]): Seq[IFormula] = {
    val res = new ArrayBuffer[IFormula]
    res ++= startSet
    //println("startSet",Console.YELLOW + startSet)
    for (q <- newElements) {
      //println("newElements",Console.YELLOW + q)
//      println("res.size",Console.YELLOW + res.size)
//      println("res",Console.YELLOW + res)
      if (!containsPred(q, res)) {
        res += q
      }//else println("q",q)
    }
    res.toSeq
  }

  def containsPred(pred : IFormula,
                   otherPreds : Iterable[IFormula]): Boolean = try {
    SimpleAPI.withProver { p =>
      implicit val _ = p
      import p._
      import IExpression._
      var qCounter=0
      val predSyms =
        SymbolCollector.variables(pred) ++ SymbolCollector.constants(pred)

      withTimeout(timeoutForPredicateDistinct) {
        otherPreds exists {
          q => {
            //println(Console.GREEN + "q",qCounter,q)
            qCounter=qCounter+1
            val qSyms =
              SymbolCollector.variables(q) ++ SymbolCollector.constants(q)

            if (!predSyms.isEmpty && !qSyms.isEmpty &&
              Seqs.disjoint(predSyms, qSyms)) {
              // if the predicates do not share any symbols, we can
              // assume they are not equivalent
              false
            } else scope {
              //val c = pred <=> q
              val c = (pred <=> q) ||| (pred==q)
              val vars =
                SymbolCollector.variables(c)

              // replace variables with constants
              val varSorts =
                (for (ISortedVariable(n, s) <- vars.iterator)
                  yield (n -> s)).toMap
              val maxVar = if(vars.isEmpty) 0 else
                (for (IVariable(n) <- vars.iterator) yield n).max
              val varSubst =
                (for (n <- 0 to maxVar) yield (varSorts get n) match {
                  case Some(s) => createConstant(s)
                  case None => v(n)
                }).toList
              ??(subst(c, varSubst, 0))
//              println("pred",pred)
//              println("vars",vars)
//              println("c",c)
//              if(??? == ProverStatus.Valid)
//                println("pred",pred,"q",q)
              ??? == ProverStatus.Valid
            }
          }
        }
      }
    }
  } catch
    {
      case SimpleAPI.TimeoutException => false
    }


  def isArgBoolean(arg: ITerm): Boolean =
    Sort.sortOf(arg) match {
      case Sort.MultipleValueBool => {
        true
      }
      case _ => {
        false
      }
    }

  def argumentEquationGenerator(n:Int,eqConstant:Seq[IdealInt]): Seq[IFormula] ={
      (for (c<-eqConstant) yield Seq(Eq(IVariable(n),c),Geq(IVariable(n),c))).flatten
    //Geq(IVariable(n).minusSimplify,-c)
  }

  def pairWiseEquationGenerator(v1: Tuple2[ITerm,Int], v2: Tuple2[ITerm,Int],variableConstantPairs: Seq[(IdealInt, IdealInt)],constantList: Seq[IdealInt]): Seq[IFormula] ={
    val equations=  for ((v1Const,v2Const)<-variableConstantPairs;c<-constantList; if !(v1Const.intValue<0 &&v2Const.intValue<0 && c.intValue<=0)) yield
      Eq(IPlus(IVariable(v1._2).*(v1Const),IVariable(v2._2).*(v2Const)),c)
    val inequations=for ((v1Const,v2Const)<-variableConstantPairs;c<-constantList) yield
      Geq(IPlus(IVariable(v1._2).*(v1Const),IVariable(v2._2).*(v2Const)),c)
    equations++inequations
  }
  def listToTuple2[A](x:Seq[A]): Tuple2[A,A] = x match {
      case Seq(a, b) => Tuple2(a, b)
    }

  def moveRenameFile(sourceFilename: String, destinationFilename: String,message:String=""): Unit = {
    if (GlobalParameters.get.moveFile==true){
      println(Console.RED+"-"*5+message+"-"*5)
      val path = Files.move(
        Paths.get(sourceFilename),
        Paths.get(destinationFilename),
        StandardCopyOption.REPLACE_EXISTING
      )
      if (path != null) {
        println(s"moved the file $sourceFilename to $destinationFilename successfully")
        removeRelativeFiles(sourceFilename)
      } else {
        println(s"could NOT move the file $sourceFilename")
      }
    }
    println(Console.RED+message)
  }
  def removeRelativeFiles(fileName:String): Unit ={
    val currentDirectory = new java.io.File(GlobalParameters.get.fileName).getParentFile.getPath
    println(currentDirectory)
    val relativeFiles = new java.io.File(currentDirectory).listFiles(new FilenameFilter {
      override def accept(dir: java.io.File, name: String): Boolean = {
        name.startsWith(HintsSelection.getFileName())
      }
    })
    try{
      for (file<-relativeFiles)
        Files.delete(file.toPath)
    }catch {case _=>println("no relative files")}
  }
  def copyRenameFile(sourceFilename: String, destinationFilename: String): Unit = {
    val path = Files.copy(
      Paths.get(sourceFilename),
      Paths.get(destinationFilename),
      StandardCopyOption.REPLACE_EXISTING
    )
    if (path != null) {
      println(s"moved the file $sourceFilename successfully")
    } else {
      println(s"could NOT move the file $sourceFilename")
    }
  }

  def getClausesInCounterExamples(result: Either[Map[Predicate, IFormula], Dag[(IAtom, Clause)]], clauses: Clauses): Clauses = {
    if (result.isRight)
    (result match {
      case Right(cex) => {
        for (node <- cex.subdagIterator; clau <- clauses if (node.d._2.equals(clau))) yield clau
      }
    }).toSeq
    else
      Seq()
  }

  def sortHints[A](hints: A): A =
    hints match {
      case h: VerificationHints => {
        var tempHints = VerificationHints(Map()) //sort the hints
        for ((oneHintKey, oneHintValue) <- h.getPredicateHints()) {
          val tempSeq = oneHintValue.sortBy(_.toString)
          tempHints = tempHints.addPredicateHints(Map(oneHintKey -> tempSeq))
        }
        tempHints.asInstanceOf[A]
      }
      case h: Map[Predicate, Seq[IFormula]] => {
        val sortedByKey = h.toSeq.sortBy(_._1.name)
        (for ((oneHintKey, oneHintValue) <- sortedByKey) yield {
          val tempSeq = oneHintValue.sortBy(_.toString)
          oneHintKey -> tempSeq
        }).toMap.asInstanceOf[A]
      }
    }






  def tryAndTestSelectionTemplates(encoder: ParametricEncoder, simpHints: VerificationHints,
                                   simpClauses: Clauses, file: String, InitialHintsWithID: Seq[wrappedHintWithID],
                                   predicateFlag: Boolean = true): (VerificationHints, Either[Map[Predicate, IFormula], Dag[(IAtom, Clause)]]) = {


    val fileName = file.substring(file.lastIndexOf("/") + 1)
    val timeOut = GlobalParameters.get.threadTimeout //timeout
    var currentTemplates = simpHints
    var optimizedTemplates = VerificationHints(Map())
    var PositiveHintsWithID: Seq[wrappedHintWithID] = Seq()
    var NegativeHintsWithID: Seq[wrappedHintWithID] = Seq()

    println("-------------------------Hints selection begins------------------------------------------")
    if (simpHints.isEmpty) {
      val result: Either[Map[Predicate, IFormula], Dag[(IAtom, Clause)]] = Left(Map())
      (simpHints, result)
    } else {
      for ((head, preds) <- simpHints.predicateHints) {
        var criticalTemplatesSeq: Seq[VerifHintElement] = Seq()
        var redundantTemplatesSeq: Seq[VerifHintElement] = Seq()
        for (p <- preds) {
          //delete on template in a head
          val currentTemplatesList = currentTemplates.predicateHints(head).filter(_ != p)
          currentTemplates = currentTemplates.filterNotPredicates(Set(head))

          currentTemplates = currentTemplates.addPredicateHints(Map(head -> currentTemplatesList))

          //try cegar

          val startTime = currentTimeMillis
          val toParams = GlobalParameters.get.clone
          toParams.timeoutChecker = () => {
            if ((currentTimeMillis - startTime) > timeOut ) //timeout milliseconds
              throw lazabs.Main.TimeoutException //Main.TimeoutException
          }
          try {
            GlobalParameters.parameters.withValue(toParams) {
              //interpolator
              val interpolator = if (GlobalParameters.get.templateBasedInterpolation)
                Console.withErr(Console.out) {
                  val builder =
                    new StaticAbstractionBuilder(
                      simpClauses,
                      GlobalParameters.get.templateBasedInterpolationType)


                  val autoAbstractionMap =
                    builder.abstractionRecords
                  val abstractionMap =
                    if (encoder.globalPredicateTemplates.isEmpty) {
                      autoAbstractionMap
                    } else {
                      val loopDetector = builder.loopDetector
                      print("Using interpolation templates provided in program: ")
                      val hintsAbstractionMap =
                        loopDetector hints2AbstractionRecord currentTemplates //emptyHints criticalHints
                      //DEBUG
                      println(hintsAbstractionMap.keys.toSeq sortBy (_.name) mkString ", ")

                      AbstractionRecord.mergeMaps(Map(), hintsAbstractionMap) //autoAbstractionMap=Map()
                    }

                  TemplateInterpolator.interpolatingPredicateGenCEXAbsGen(
                    abstractionMap,
                    GlobalParameters.get.templateBasedInterpolationTimeout)
                } else {
                DagInterpolator.interpolatingPredicateGenCEXAndOr _
              }

              println(
                "----------------------------------- CEGAR --------------------------------------")

              new HornPredAbs(simpClauses, // loop
                currentTemplates.toInitialPredicates, //emptyHints
                interpolator).result

              // not timeout ...
              println("Delete a redundant hint:\n" + head + "\n" + p)
              redundantTemplatesSeq = redundantTemplatesSeq ++ Seq(p)

              for (wrappedHint <- InitialHintsWithID) { //add useless hint to NegativeHintsWithID   //ID:head->hint
                if (head.name.toString == wrappedHint.head && wrappedHint.hint == p.toString) {
                  NegativeHintsWithID = NegativeHintsWithID ++ Seq(wrappedHint)
                }
              }


            }

          } catch {
            // ,... Main.TimeoutException
            //time out
            case lazabs.Main.TimeoutException => {
              println("Add a critical hint\n" + head + "\n" + p)
              criticalTemplatesSeq = criticalTemplatesSeq ++ Seq(p)
              currentTemplates = currentTemplates.filterNotPredicates(Set(head))
              //currentTemplates=currentTemplates.filterPredicates(GSet(oneHintKey))
              currentTemplates = currentTemplates.addPredicateHints(Map(head -> (currentTemplatesList ++ Seq(p)))) //beforeDeleteHints

              for (wrappedHint <- InitialHintsWithID) { //add useful hint to PositiveHintsWithID
                if (head.name.toString() == wrappedHint.head && wrappedHint.hint == p.toString) {
                  PositiveHintsWithID = PositiveHintsWithID ++ Seq(wrappedHint)
                }
              }

            }

          } //catch end

        } // second for end
        if (!criticalTemplatesSeq.isEmpty) {
          optimizedTemplates = optimizedTemplates.addPredicateHints(Map(head -> criticalTemplatesSeq))
        }

      } // first for end


      println("\n------------DEBUG-Select critical hints end-------------------------")

      println("\n------------test selected hints-------------------------")
      val result = GlobalParameters.parameters.withValue(GlobalParameters.get.clone) {
        //interpolator
        val interpolator = if (GlobalParameters.get.templateBasedInterpolation)
          Console.withErr(Console.out) {
            val builder =
              new StaticAbstractionBuilder(
                simpClauses,
                GlobalParameters.get.templateBasedInterpolationType)
            val autoAbstractionMap =
              builder.abstractionRecords
            val abstractionMap =
              if (encoder.globalPredicateTemplates.isEmpty) {
                autoAbstractionMap
              } else {
                val loopDetector = builder.loopDetector
                print("Using interpolation templates provided in program: ")
                val hintsAbstractionMap =
                  loopDetector hints2AbstractionRecord currentTemplates //emptyHints criticalHints
                //DEBUG
                println(hintsAbstractionMap.keys.toSeq sortBy (_.name) mkString ", ")

                AbstractionRecord.mergeMaps(Map(), hintsAbstractionMap) //autoAbstractionMap=Map()
              }

            TemplateInterpolator.interpolatingPredicateGenCEXAbsGen(
              abstractionMap,
              GlobalParameters.get.templateBasedInterpolationTimeout)
          } else {
          DagInterpolator.interpolatingPredicateGenCEXAndOr _
        }

        println(
          "----------------------------------- CEGAR --------------------------------------")

        new HornPredAbs(simpClauses, // loop
          optimizedTemplates.toInitialPredicates, //emptyHints
          interpolator).result
      }

      //println("\nsimpHints Hints:")
      //simpHints.pretyPrintHints()
      println("\nOptimized Hints:")
      println("!@@@@")
      optimizedTemplates.pretyPrintHints()
      println("@@@@!")
      println("timeout:" + GlobalParameters.get.threadTimeout)
      //GlobalParameters.get.printHints=optimizedHints
      (optimizedTemplates, result)
    }
  }



  def transformPredicateMapToVerificationHints(originalPredicates:Map[Predicate, Seq[IFormula]]):  VerificationHints ={
    VerificationHints(originalPredicates.mapValues(_.map(VerificationHints.VerifHintInitPred(_))))
  }

  def initialIDForHints(simpHints: VerificationHints): Seq[wrappedHintWithID] = {
    var counter = 0
    val wrappedHintsList = for ((head,hints) <- simpHints.getPredicateHints();oneHint <- hints) yield{
      counter = counter + 1
      wrappedHintWithID(counter, head.name, oneHint.toString)
    }
    wrappedHintsList.toSeq
  }


  def writeHornClausesToFile(file: String, simpClauses: Clauses): Unit = {
    val writer = new PrintWriter(new File(file + ".horn"))
    for (clause <- simpClauses) {
      writer.write(clause.toPrologString + "\n")
    }
    writer.close()
  }


  def writeSMTFormatToFile(simpClauses: Clauses, fileName: String): Unit = {
    //val filename = basename + ".smt2"
    println("write " + fileName + " to smt format file")
    //val out = new java.io.FileOutputStream("trainData/"+fileName+".smt2")
    val out = new java.io.FileOutputStream(fileName + ".smt2") //python path
    Console.withOut(out) {
      val clauseFors =
        for (c <- simpClauses) yield {
          val f = c.toFormula
          // eliminate remaining operators like eps
          Transform2Prenex(EquivExpander(PartialEvaluator(f)))
        }

      val allPredicates =
        HornClauses allPredicates simpClauses

      SMTLineariser("C_VC", "HORN", "unknown",
        List(), allPredicates.toSeq.sortBy(_.name),
        clauseFors)
    }
    out.close

  }


  def getArgumentInfo(argumentList: Array[(IExpression.Predicate, Int)]): ArrayBuffer[argumentInfo] = {
    var argID = 0
    var arguments: ArrayBuffer[argumentInfo] = ArrayBuffer[argumentInfo]()
    for ((arg, i) <- argumentList.zipWithIndex) {
      for ((a, j) <- (0 to arg._2 - 1).zipWithIndex) {
        arguments += new argumentInfo(argID, arg._1, a)
        argID = argID + 1
      }
    }
    arguments
  }

  def getArgumentInfoFromFile(argumentList: Array[(IExpression.Predicate, Int)]): ArrayBuffer[argumentInfo] = {
    val arguments = getArgumentInfo(argumentList)
    val argumentFileName = GlobalParameters.get.fileName + ".arguments"
    if (scala.reflect.io.File(argumentFileName).exists) {
      //read score from .argument file
      for (arg <- arguments; line <- Source.fromFile(argumentFileName).getLines) {
        val parsedLine = line.split(":")
        if (arg.head == parsedLine(1) && ("argument" + arg.index.toString) == parsedLine(2))
          arg.score = parsedLine(3).toInt
      }
    }

    arguments
  }

  def getArgumentBoundForSmt(argumentList: Array[(IExpression.Predicate, Int)], disjunctive: Boolean, simplifiedClauses: Seq[Clause], simpHints: VerificationHints
                             , predGenerator:  Dag[DisjInterpolator.AndOrNode[NormClause, Unit]] => Either[Seq[(Predicate, Seq[Conjunction])], Dag[(IAtom, NormClause)]]): ArrayBuffer[argumentInfo] = {
    val counterexampleMethod =
      if (disjunctive)
        CEGAR.CounterexampleMethod.AllShortest
      else
        CEGAR.CounterexampleMethod.FirstBestShortest
    val simpPredAbs =
      new simplifiedHornPredAbsForArgumentBounds(simplifiedClauses, //HornPredAbs
        simpHints.toInitialPredicates, predGenerator,
        counterexampleMethod)
    getArgumentBound(argumentList, simpPredAbs.argumentBounds)
  }

  def getArgumentBound(argumentList: Array[(IExpression.Predicate, Int)], argumentBounds: scala.collection.mutable.Map[Predicate, Array[(String, String)]]): ArrayBuffer[argumentInfo] = {
    val arguments = getArgumentInfo(argumentList)
    for ((k, v) <- argumentBounds; arg <- arguments) if (arg.location.toString() == k.toString()) {
      arg.bound = v(arg.index)
    }
    arguments
  }

  def getArgumentOccurrenceInHints(file: String,
                                   argumentList: Array[(IExpression.Predicate, Int)],
                                   positiveHints: VerificationHints,
                                   countOccurrence: Boolean = true): ArrayBuffer[argumentInfo] ={
    val arguments = getArgumentInfo(argumentList)
    if (countOccurrence == true) {
      //get hint info list
      var positiveHintInfoList: ArrayBuffer[hintInfo] = ArrayBuffer[hintInfo]()
      for ((head, hintList) <- positiveHints.getPredicateHints()) {
        for (h <- hintList) h match {
          case VerifHintInitPred(p) => {
            positiveHintInfoList += new hintInfo(p, "VerifHintInitPred", head)
          }
          case VerifHintTplPred(p, _) => {
            positiveHintInfoList += new hintInfo(p, "VerifHintTplPred", head)
          }
          case VerifHintTplPredPosNeg(p, _) => {
            positiveHintInfoList += new hintInfo(p, "VerifHintTplPredPosNeg", head)
          }
          case VerifHintTplEqTerm(t, _) => {
            positiveHintInfoList += new hintInfo(t, "VerifHintTplEqTerm", head)
          }
          case VerifHintTplInEqTerm(t, _) => {
            positiveHintInfoList += new hintInfo(t, "VerifHintTplInEqTerm", head)
          }
          case VerifHintTplInEqTermPosNeg(t, _) => {
            positiveHintInfoList += new hintInfo(t, "VerifHintTplInEqTermPosNeg", head)
          }
          case _ => {}
        }
      }
      //go through all predicates and arguments count occurrence
      for (arg <- arguments) {
        for (hint <- positiveHintInfoList) {
          if (arg.location.equals(hint.head) && ContainsSymbol(hint.expression, IVariable(arg.index))) {
            arg.score = arg.score + 1
            arg.binaryOccurenceLabel = 1
          }
        }
      }

    }
    //normalize score
    //    val argumentIDList=for(arg<-arguments) yield arg.ID
    //    val argumentNameList=for(arg<-arguments) yield arg.location.toString()+":"+"argument"+arg.index
    //    val argumentOccurrence=for(arg<-arguments) yield arg.score
    (arguments)

  }
  def writeArgumentOccurrenceInHintsToFile(file: String,
                               argumentList: Array[(IExpression.Predicate, Int)],
                               positiveHints: VerificationHints,
                               countOccurrence: Boolean = true,writeToFile:Boolean=false): ArrayBuffer[argumentInfo]  = {
    val arguments = getArgumentOccurrenceInHints(file,argumentList,positiveHints,countOccurrence)
    if (writeToFile==true){
      println("Write arguments to file")
      val writer = new PrintWriter(new File(file + ".arguments")) //python path
      for (arg <- arguments) {
        writer.write(arg.ID + ":" + arg.location.toString() + ":" + "argument" + arg.index + ":" + arg.score + "\n")
      }
      writer.close()
    }
    arguments
  }

}


class VerificationHintsInfo(val initialHints: VerificationHints, val positiveHints: VerificationHints, val negativeHints: VerificationHints)

class ClauseInfo(val simplifiedClause: Clauses, val clausesInCounterExample: Clauses)

class hintInfo(e: IExpression, t: String, h: IExpression.Predicate) {
  val head = h
  val expression = e
  val hintType = t
}

class argumentInfo(id: Int, loc: IExpression.Predicate, ind: Int) {
  val ID = id
  val location = loc
  val index = ind
  var score = 0
  val head = location.toString()
  val headName = location.name
  var bound: Pair[String, String] = ("\"None\"", "\"None\"")
  var binaryOccurenceLabel = 0
}

class simplifiedHornPredAbsForArgumentBounds[CC <% HornClauses.ConstraintClause](iClauses: Iterable[CC],
                                                                                 initialPredicates: Map[Predicate, Seq[IFormula]],
                                                                                 predicateGenerator:  Dag[DisjInterpolator.AndOrNode[NormClause, Unit]] =>
                                                                                   Either[Seq[(Predicate, Seq[Conjunction])],
                                                                                     Dag[(IAtom, NormClause)]], counterexampleMethod: CEGAR.CounterexampleMethod.Value = CEGAR.CounterexampleMethod.FirstBestShortest) {
  val theories = {
    val coll = new TheoryCollector
    coll addTheory TypeTheory
    for (c <- iClauses)
      c collectTheories coll
    coll.theories
  }
  implicit val sf = new SymbolFactory(theories)
  val relationSymbols =
    (for (c <- iClauses.iterator;
          lit <- (Iterator single c.head) ++ c.body.iterator;
          p = lit.predicate)
      yield (p -> RelationSymbol(p))).toMap

  // make sure that arguments constants have been instantiated
  for (c <- iClauses) {
    val preds = for (lit <- c.head :: c.body.toList) yield lit.predicate
    for (p <- preds.distinct) {
      val rs = relationSymbols(p)
      for (i <- 0 until (preds count (_ == p)))
        rs arguments i
    }
  }
  // translate clauses to internal form
  val (normClauses, relationSymbolBounds) = {
    val rawNormClauses = new LinkedHashMap[NormClause, CC]

    for (c <- iClauses) {
      lazabs.GlobalParameters.get.timeoutChecker()
      rawNormClauses.put(NormClause(c, (p) => relationSymbols(p)), c)
    }

    if (lazabs.GlobalParameters.get.intervals) {
      val res = new LinkedHashMap[NormClause, CC]

      val propagator =
        new IntervalPropagator(rawNormClauses.keys.toIndexedSeq,
          sf.reducerSettings)

      for ((nc, oc) <- propagator.result)
        res.put(nc, rawNormClauses(oc))
      (res.toSeq, propagator.rsBounds)
    } else {
      val emptyBounds =
        (for (rs <- relationSymbols.valuesIterator)
          yield (rs -> Conjunction.TRUE)).toMap
      (rawNormClauses.toSeq, emptyBounds)
    }
  }
  // print argument bounds
  var argumentBounds: scala.collection.mutable.Map[Predicate, Array[Tuple2[String, String]]] = scala.collection.mutable.Map()
  for ((rs, bounds) <- relationSymbolBounds; if (rs.pred.name != "FALSE")) { //don't learn from FALSE predicate
    //println(rs.pred.name + ":")
    var argumentBoundList: Array[Tuple2[String, String]] = Array()
    if (bounds.isTrue) { //FALSE's bounds is true
      for (s <- rs.arguments(0))
        argumentBoundList :+= Tuple2("\"None\"", "\"None\"")
    } else if (bounds.isFalse) {
      for (s <- rs.arguments(0))
        argumentBoundList :+= Tuple2("\"False\"", "\"False\"")
    } else {
      for (s <- rs.arguments(0)) {
        //print("  " + s + ": ")
        val lc = ap.terfor.linearcombination.LinearCombination(s, bounds.order)
        val lowerBound = PresburgerTools.lowerBound(lc, bounds) match {
          case Some(x) => x.toString()
          case _ => "\"None\""
        }
        //print(", ")
        val upperBound = (for (b <- PresburgerTools.lowerBound(-lc, bounds)) yield -b) match {
          case Some(x) => x.toString()
          case _ => "\"None\""
        }
        argumentBoundList :+= Tuple2(lowerBound, upperBound)
        //println()
      }
    }
    argumentBounds(rs.pred) = argumentBoundList
  }
}