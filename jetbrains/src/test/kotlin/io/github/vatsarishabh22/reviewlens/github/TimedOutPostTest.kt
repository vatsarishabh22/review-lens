package io.github.vatsarishabh22.reviewlens.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Finish post stopped unanswered may still have landed (measured on rl-thread-test#21: gh killed 0.6 s into a submit,
 * the review posted); one read of the review by its id says what it did before anything is undone or marked posted.
 */
class TimedOutPostTest {
    @Test
    fun `a submit that hung is posted only when the review is submitted holding exactly what was sent`() =
        assertEquals(true, timedOutPost(submitting = true, fresh = true, expected = 2, now = ReviewRead(pending = false, comments = 2)))

    @Test
    fun `a review submitted with other comments than sent can't be told`() =
        assertNull(timedOutPost(submitting = true, fresh = false, expected = 2, now = ReviewRead(pending = false, comments = 3)))

    @Test
    fun `a review submitted while no submit was sent was submitted elsewhere, so it can't be told`() =
        assertNull(timedOutPost(submitting = false, fresh = true, expected = 2, now = ReviewRead(pending = false, comments = 2)))

    @Test
    fun `when the read fails or the review is gone, it can't tell`() =
        assertNull(timedOutPost(submitting = true, fresh = true, expected = 2, now = null))

    @Test
    fun `a review it started still pending is undone, whatever it holds, as it is its own by id`() =
        assertEquals(false, timedOutPost(submitting = false, fresh = true, expected = 2, now = ReviewRead(pending = true, comments = 3)))

    @Test
    fun `an adopted review holding one comment more than known got it from a call never answered, so it can't tell`() =
        assertNull(timedOutPost(submitting = false, fresh = false, expected = 3, now = ReviewRead(pending = true, comments = 4)))

    @Test
    fun `an adopted review still pending is never undone, as the call that hung may land after the undo`() =
        assertNull(timedOutPost(submitting = true, fresh = false, expected = 3, now = ReviewRead(pending = true, comments = 3)))

    @Test
    fun `a review read is its state and comment count`() {
        assertEquals(ReviewRead(pending = false, comments = 2), parseReviewRead("COMMENTED\t2\n"))
        assertEquals(ReviewRead(pending = true, comments = 0), parseReviewRead("PENDING\t0\n"))
        assertEquals("every submitted state is not pending", ReviewRead(pending = false, comments = 1), parseReviewRead("APPROVED\t1\n"))
    }
}
