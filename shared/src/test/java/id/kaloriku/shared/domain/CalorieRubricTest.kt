package id.kaloriku.shared.domain

import id.kaloriku.shared.ai.JevAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieRubricTest {

    @Test
    fun `interpret maps a mid score into the right bucket`() {
        val answer = JevAnswer(
            type = "score",
            score = 3.0,
            probabilities = mapOf("3" to 1.0),
        )
        val (point, low, high) = CalorieRubric.interpret(answer, FoodCategory.NASI)
        val ranges = CalorieRubric.buckets.getValue(FoodCategory.NASI)
        assertTrue("point in bucket 3", point in ranges[3])
        assertEquals(ranges[2].first, low)
        assertEquals(ranges[4].last, high)
    }

    @Test
    fun `interpret clamps out-of-range scores`() {
        val high = JevAnswer(type = "score", score = 99.0)
        val (point, _, highBound) = CalorieRubric.interpret(high, FoodCategory.SAYUR)
        val ranges = CalorieRubric.buckets.getValue(FoodCategory.SAYUR)
        assertTrue(point <= ranges.last().last)
        assertEquals(ranges.last().last, highBound)
    }

    @Test
    fun `weighted index uses the probability distribution not the raw score`() {
        val answer = JevAnswer(
            type = "score",
            score = 5.0,
            probabilities = mapOf("0" to 0.5, "1" to 0.5),
        )
        assertEquals(0.5, answer.weightedIndex()!!, 0.0001)
    }

    @Test
    fun `weighted index falls back to score when no probabilities`() {
        val answer = JevAnswer(type = "score", score = 2.5)
        assertEquals(2.5, answer.weightedIndex()!!, 0.0001)
    }

    @Test
    fun `criteria has the same size as the bucket list`() {
        FoodCategory.entries.forEach { category ->
            val ranges = CalorieRubric.buckets.getValue(category)
            val criteria = CalorieRubric.criteriaFor(category)
            assertEquals(ranges.size, criteria.size)
            assertTrue(criteria.all { it.isNotBlank() })
        }
    }
}
