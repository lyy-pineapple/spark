/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.types.{BooleanType, StringType}

class LikeJoniSimplificationSuite extends PlanTest {
  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("Like Simplification", Once,
        LikeSimplification) :: Nil
  }

  val testRelation = LocalRelation('a.string)

  test("simplify Like into StartsWith") {
    val originalQuery =
      testRelation
        .where(('a jLike "abc%") || ('a jLike "abc\\%"))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where(StartsWith('a, "abc") || ('a jLike "abc\\%"))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("simplify Like into EndsWith") {
    val originalQuery =
      testRelation
        .where('a jLike "%xyz")

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where(EndsWith('a, "xyz"))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("simplify Like into startsWith and EndsWith") {
    val originalQuery =
      testRelation
        .where(('a jLike "abc\\%def") || ('a jLike "abc%def"))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where(('a jLike "abc\\%def") ||
        (Length('a) >= 6 && (StartsWith('a, "abc") && EndsWith('a, "def"))))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("simplify Like into Contains") {
    val originalQuery =
      testRelation
        .where(('a jLike "%mn%") || ('a jLike "%mn\\%"))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where(Contains('a, "mn") || ('a jLike "%mn\\%"))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("simplify Like into EqualTo") {
    val originalQuery =
      testRelation
        .where(('a jLike "") || ('a jLike "abc"))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where(('a === "") || ('a === "abc"))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("null pattern") {
    val originalQuery = testRelation.where('a jLike Literal(null, StringType)).analyze
    val optimized = Optimize.execute(originalQuery)
    comparePlans(optimized, testRelation.where(Literal(null, BooleanType)).analyze)
  }

  test("test like escape syntax") {
    val originalQuery1 = testRelation.where('a.jLike("abc#%", '#'))
    val optimized1 = Optimize.execute(originalQuery1.analyze)
    comparePlans(optimized1, originalQuery1.analyze)

    val originalQuery2 = testRelation.where('a.jLike("abc#%abc", '#'))
    val optimized2 = Optimize.execute(originalQuery2.analyze)
    comparePlans(optimized2, originalQuery2.analyze)
  }

  test("SPARK-33677: LikeSimplification should be skipped if pattern contains any escapeChar") {
    val originalQuery1 =
      testRelation
        .where(('a jLike "abc%") || ('a jLike "\\abc%"))
    val optimized1 = Optimize.execute(originalQuery1.analyze)
    val correctAnswer1 = testRelation
      .where(StartsWith('a, "abc") || ('a jLike "\\abc%"))
      .analyze
    comparePlans(optimized1, correctAnswer1)

    val originalQuery2 =
      testRelation
        .where(('a jLike "%xyz") || ('a jLike "%xyz\\"))
    val optimized2 = Optimize.execute(originalQuery2.analyze)
    val correctAnswer2 = testRelation
      .where(EndsWith('a, "xyz") || ('a jLike "%xyz\\"))
      .analyze
    comparePlans(optimized2, correctAnswer2)

    val originalQuery3 =
      testRelation
        .where(('a jLike("@bc%def", '@')) || ('a jLike "abc%def"))
    val optimized3 = Optimize.execute(originalQuery3.analyze)
    val correctAnswer3 = testRelation
      .where(('a jLike("@bc%def", '@')) ||
        (Length('a) >= 6 && (StartsWith('a, "abc") && EndsWith('a, "def"))))
      .analyze
    comparePlans(optimized3, correctAnswer3)

    val originalQuery4 =
      testRelation
        .where(('a jLike "%mn%") || ('a jLike("%mn%", '%')))
    val optimized4 = Optimize.execute(originalQuery4.analyze)
    val correctAnswer4 = testRelation
      .where(Contains('a, "mn") || ('a jLike("%mn%", '%')))
      .analyze
    comparePlans(optimized4, correctAnswer4)

    val originalQuery5 =
      testRelation
        .where(('a jLike "abc") || ('a jLike("abbc", 'b')))
    val optimized5 = Optimize.execute(originalQuery5.analyze)
    val correctAnswer5 = testRelation
      .where(('a === "abc") || ('a jLike("abbc", 'b')))
      .analyze
    comparePlans(optimized5, correctAnswer5)
  }

  test("simplify LikeAllJoni") {
    val originalQuery =
      testRelation
        .where(('a jLikeAll(
          "abc%", "abc\\%", "%xyz", "abc\\%def", "abc%def", "%mn%", "%mn\\%", "", "abc")))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where((((((StartsWith('a, "abc") && EndsWith('a, "xyz")) &&
        (Length('a) >= 6 && (StartsWith('a, "abc") && EndsWith('a, "def")))) &&
        Contains('a, "mn")) && ('a === "")) && ('a === "abc")) &&
        ('a jLikeAll("abc\\%", "abc\\%def", "%mn\\%")))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("simplify NotLikeAllJoni") {
    val originalQuery =
      testRelation
        .where(('a jNotLikeAll(
          "abc%", "abc\\%", "%xyz", "abc\\%def", "abc%def", "%mn%", "%mn\\%", "", "abc")))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where((((((Not(StartsWith('a, "abc")) && Not(EndsWith('a, "xyz"))) &&
        Not(Length('a) >= 6 && (StartsWith('a, "abc") && EndsWith('a, "def")))) &&
        Not(Contains('a, "mn"))) && Not('a === "")) && Not('a === "abc")) &&
        ('a jNotLikeAll("abc\\%", "abc\\%def", "%mn\\%")))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("simplify LikeAnyJoni") {
    val originalQuery =
      testRelation
        .where(('a jLikeAny(
          "abc%", "abc\\%", "%xyz", "abc\\%def", "abc%def", "%mn%", "%mn\\%", "", "abc")))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where((((((StartsWith('a, "abc") || EndsWith('a, "xyz")) ||
        (Length('a) >= 6 && (StartsWith('a, "abc") && EndsWith('a, "def")))) ||
        Contains('a, "mn")) || ('a === "")) || ('a === "abc")) ||
        ('a jLikeAny("abc\\%", "abc\\%def", "%mn\\%")))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("simplify NotLikeAnyJoni") {
    val originalQuery =
      testRelation
        .where(('a jNotLikeAny(
          "abc%", "abc\\%", "%xyz", "abc\\%def", "abc%def", "%mn%", "%mn\\%", "", "abc")))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .where((((((Not(StartsWith('a, "abc")) || Not(EndsWith('a, "xyz"))) ||
        Not(Length('a) >= 6 && (StartsWith('a, "abc") && EndsWith('a, "def")))) ||
        Not(Contains('a, "mn"))) || Not('a === "")) || Not('a === "abc")) ||
        ('a jNotLikeAny("abc\\%", "abc\\%def", "%mn\\%")))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

}
