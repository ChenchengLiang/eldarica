/**
 * Copyright (c) 2011-2014 Philipp Ruemmer. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * 
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * 
 * * Neither the name of the authors nor the names of their
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
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

package lazabs.horn.bottomup

import lazabs.horn.bottomup.HornClauses._
import lazabs.horn.global._
import ap.basetypes.IdealInt
import ap.parser._
import IExpression._
import lazabs.prover.PrincessWrapper

import scala.collection.mutable.{HashSet => MHashSet, HashMap => MHashMap}


class HornPreprocessor(oriClauses : Seq[HornClauses.Clause]) {
  import IExpression._

  Console.err.println("Initially: " + oriClauses.size + " clauses")

  //////////////////////////////////////////////////////////////////////////////

  val result = {
    val clauses1 = oriClauses

    // inline simple definitions found in the clause constraints
    val clauses2 = for (clause@Clause(head, oriBody, constraint) <- clauses1) yield {
      val headSyms = SymbolCollector constants head
      var body = oriBody

      var conjuncts = LineariseVisitor(constraint, IBinJunctor.And)
      val oriConjunctNum = conjuncts.size
      val replacement = new MHashMap[ConstantTerm, ITerm]
      val replacedConsts = new MHashSet[ConstantTerm]

      var changed = true
      while (changed) {
        changed = false

        val remConjuncts = conjuncts filter {
          case IIntFormula(IIntRelation.EqZero,
                           IPlus(IConstant(c), 
                                 ITimes(IdealInt.MINUS_ONE, IConstant(d))))
            if (c == d) =>
              false
          case IIntFormula(IIntRelation.EqZero,
                           IPlus(IConstant(c), 
                                 ITimes(IdealInt.MINUS_ONE, IConstant(d))))
            if (c != d && !(replacedConsts contains c) && !(replacedConsts contains d)) =>
              if (!(headSyms contains c)) {
                replacement.put(c, IConstant(d))
                replacedConsts += c
                replacedConsts += d
                false
              } else if (!(headSyms contains d)) {
                replacement.put(d, IConstant(c))
                replacedConsts += c
                replacedConsts += d
                false
              } else {
                true
              }
          case _ => true
        }

        if (!replacement.isEmpty) {
          changed = true
          conjuncts =
            for (f <- remConjuncts) yield ConstantSubstVisitor(f, replacement)

          body = for (a <- body)
                 yield ConstantSubstVisitor(a, replacement).asInstanceOf[IAtom]

          replacement.clear
          replacedConsts.clear
        }
      }

      if (oriConjunctNum != conjuncts.size)
        Clause(head, body, and(conjuncts))
      else
        clause
    }

    val clauses3 = elimLinearDefs(clauses2)

    val clauses4 =
      if (lazabs.GlobalParameters.get.cegarHintsFile == "")
        for (c <- clauses3; d <- splitClauseBody(c)) yield d
      else
        // if hints were given, the behaviour of Eldarica becomes
        // very confusing if additional predicates are introduced.
        // for the time being, do not split clauses in this case
        clauses3

    val clauses5 =
      if (lazabs.GlobalParameters.get.splitClauses)
        // turn the resulting formula into CNF, and split positive equations
        // (which often gives better performance)
        (for (Clause(head, body, constraint) <- clauses4.iterator;
              constraint2 <- splitConstraint(~constraint))
         yield Clause(head, body, Transform2NNF(!constraint2))).toList
      else
        clauses4

/*
    val clauses5 =
      for (Clause(head, body, constraint) <- clauses4;
           cnf = lazabs.horn.parser.HornReader.CNFSimplifier(!constraint);
           constraint2 <- LineariseVisitor(cnf, IBinJunctor.And))
      yield Clause(head, body, Transform2NNF(!constraint2))
*/

    // apply acceleration of Horn clauses
    val clauses6 = if (!lazabs.GlobalParameters.get.staticAccelerate) {
      clauses5
    } else {
      HornAccelerate.accelerate(clauses5)
    }
    
    clauses6
  }

  //////////////////////////////////////////////////////////////////////////////

  def elimLinearDefs(allClauses : Seq[HornClauses.Clause]) : Seq[HornClauses.Clause] = {
    var changed = true
    var clauses = allClauses
    while (changed) {
      changed = false

      // determine relation symbols that are uniquely defined

      val uniqueDefs = extractUniqueDefs(clauses)
      val finalDefs = extractAcyclicDefs(uniqueDefs)

      val newClauses =
        for (clause@Clause(head@IAtom(p, _), _, _) <- clauses;
             if (!(finalDefs contains p)))
        yield applyDefs(clause, finalDefs)

      if (newClauses.size < clauses.size) {
        clauses = newClauses
        changed = true
      }
    }

    Console.err.println("After eliminating linear definitions: " + clauses.size + " clauses")

    clauses
  }

  //////////////////////////////////////////////////////////////////////////////

  def extractUniqueDefs(clauses : Iterable[Clause]) = {
      val uniqueDefs = new MHashMap[Predicate, Clause]
      val badHeads = new MHashSet[Predicate]
      badHeads += FALSE

      for (clause@Clause(head, body, constraint) <- clauses)
        if (!(badHeads contains head.pred)) {
          if ((uniqueDefs contains head.pred) ||
              body.size > 1 ||
              (body.size == 1 && head.pred == body.head.pred)) {
            badHeads += head.pred
            uniqueDefs -= head.pred
          } else {
            uniqueDefs.put(head.pred, clause)
          }
        }

    uniqueDefs.toMap
  }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Extract an acyclic subset of the definitions, and
   * shorten definition paths
   */
  def extractAcyclicDefs(uniqueDefs : Map[Predicate, Clause]) = {
      var remDefs = new MHashMap[Predicate, Clause] ++ uniqueDefs
      val finalDefs = new MHashMap[Predicate, Clause]

      while (!remDefs.isEmpty) {
        val bodyPreds =
          (for (Clause(_, body, _) <- remDefs.valuesIterator;
                IAtom(p, _) <- body.iterator)
           yield p).toSet
        val (remainingDefs, defsToInline) =
          remDefs partition { case (p, _) => bodyPreds contains p }

        if (defsToInline.isEmpty) {
          // we have to drop some definitions to eliminate cycles
          val pred =
            (for (Clause(IAtom(p, _), _, _) <- oriClauses.iterator;
                  if ((remDefs contains p) && (bodyPreds contains p)))
             yield p).next

          remDefs retain {
            case (_, Clause(_, Seq(), _)) => true
            case (_, Clause(_, Seq(IAtom(p, _)), _)) => p != pred
          }
        } else {
          remDefs = remainingDefs

          finalDefs transform {
            case (_, clause) => applyDefs(clause, defsToInline)
          }

          finalDefs ++= defsToInline
        }
      }

    finalDefs.toMap
  }

  //////////////////////////////////////////////////////////////////////////////

  def applyDefs(clause : Clause,
                defs : scala.collection.Map[Predicate, Clause]) : Clause = {
    val Clause(head, body, constraint) = clause
    assert(!(defs contains head.pred))

    var changed = false

    val (newBody, newConstraints) = (for (bodyLit@IAtom(p, args) <- body) yield {
      (defs get p) match {
        case None =>
          (List(bodyLit), i(true))
        case Some(defClause) => {
          changed = true
          defClause inline args
        }
      }
    }).unzip

    if (changed)
      Clause(head, newBody.flatten, constraint & and(newConstraints))
    else
      clause
  }

  //////////////////////////////////////////////////////////////////////////////

  var tempPredCounter = 0

  def splitClauseBody(clause : Clause) : List[Clause] = {
    val Clause(head, body, constraint) = clause

    if (body.size > 2) {
      val body1 = body take 2
      val remainingBody = body drop 2

      val body1Syms =
        (for (a <- body1;
              c <- SymbolCollector constantsSorted a) yield c).distinct
      val body1SymsSet = body1Syms.toSet

      val (constraintList1, constraintList2) =
        LineariseVisitor(Transform2NNF(constraint), IBinJunctor.And) partition {
          f => (SymbolCollector constants f) subsetOf body1SymsSet
        }

      val constraint1 = and(constraintList1)
      val constraint2 = and(constraintList2)

      val otherSyms =
        SymbolCollector constants (constraint2 & and(remainingBody) & head)

      val commonSyms = body1Syms filter otherSyms
      val tempPred = new Predicate ("temp" + tempPredCounter, commonSyms.size)
      tempPredCounter = tempPredCounter + 1

      val head1 = IAtom(tempPred, commonSyms)

      Clause(head1, body1, constraint1) ::
        splitClauseBody(Clause(head, head1 :: remainingBody, constraint2))
    } else {
      List(clause)
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  object CNFSimplifier extends Simplifier {
    override protected def furtherSimplifications(expr : IExpression) =
      expr match {
        case IBinFormula(IBinJunctor.Or, f,
                         IBinFormula(IBinJunctor.And, g1, g2)) =>
          (f | g1) & (f | g2)
        case IBinFormula(IBinJunctor.Or,
                         IBinFormula(IBinJunctor.And, g1, g2),
                         f) =>
          (g1 | f) & (g2 | f)
        case expr => expr
      }
  }

  def splitPosEquations(f : IFormula) : Seq[IFormula] = {
    val split =
      or(for (g <- LineariseVisitor(f, IBinJunctor.Or)) yield g match {
           case EqZ(t) => geqZero(t) & geqZero(-t)
           case g => g
         })
    LineariseVisitor(CNFSimplifier(split), IBinJunctor.And)
  }

  def splitConstraint(f : IFormula) : Iterator[IFormula] =
    for (g <- LineariseVisitor(CNFSimplifier(f), IBinJunctor.And).iterator;
         h <- splitPosEquations(g).iterator)
    yield h
}
